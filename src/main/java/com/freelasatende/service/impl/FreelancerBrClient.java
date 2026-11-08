package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.FreelancerBrConfig;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import lombok.extern.slf4j.Slf4j;

/**
 * Cliente HTTP do Freelancer.com.br (marketplace BR, scraping de HTML).
 *
 * <p>Cookie-based com sessao capturada manualmente (Cloudflare Managed Challenge).
 * Detecta a pagina de desafio do Cloudflare e a trata como sessao invalida, para
 * o polling acionar o alerta de renovacao no Telegram.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class FreelancerBrClient {

    private final FreelancerBrConfig config;
    private final ObjectMapper objectMapper;
    private final FreelancerBrHttp http;

    private HttpClient httpClient;
    private final Map<Long, String> conversaComposta = new ConcurrentHashMap<>();
    /** id numerico estavel -> id composto de envio ({@code <data-id>-<data-partition>}). */
    private final Map<Long, String> projetoComposto = new ConcurrentHashMap<>();

    /**
     * O envio exige o id COMPOSTO do projeto: 32 hex + "-" + partition (base64 das
     * categorias). Medido: com o id simples o backend responde HTTP 500; com o
     * composto responde 302 e o interesse e registrado.
     */
    private static final Pattern CARD_PROJETO = Pattern.compile(
        "data-id=\"(0[0-9a-f]{31})\"\\s+data-partition=\"([A-Za-z0-9=+/]*)\"");

    /** Registra o id composto descoberto para um projeto. */
    void registrarComposto(long idInterno, String composto) {
        if (composto != null && !composto.isBlank()) {
            projetoComposto.put(idInterno, composto);
        }
    }

    String compostoDe(long idInterno) {
        return projetoComposto.get(idInterno);
    }

    static Pattern padraoCardProjeto() {
        return CARD_PROJETO;
    }

    /** Baixa o HTML de um projeto especifico pelo caminho relativo (slug). */
    String baixarProjetoHtml(String pathRelativo) {
        if (pathRelativo.startsWith("http://") || pathRelativo.startsWith("https://")) {
            return getHtml(pathRelativo, "Freelancer.com.br projeto");
        }
        String path = pathRelativo.startsWith("/") ? pathRelativo : "/" + pathRelativo;
        return getHtml(config.getUrlBase() + path, "Freelancer.com.br projeto");
    }

    /** Baixa o HTML da inbox/mensagens (exige sessao valida). */
    String baixarInboxHtml() {
        // Caminho real da inbox mapeado na sessao logada.
        String url = config.getUrlBase() + "/account/inbox";
        return getHtml(url, "Freelancer.com.br inbox");
    }

    /**
     * Tenta baixar a listagem publica {@code /projetos} pelo transporte HTTP
     * com fingerprint de Chrome e os cookies da sessao renovada.
     *
     * <p>Retorna {@code null} quando nao ha cookies, quando o Cloudflare devolve
     * o desafio ou quando o corpo nao tem cards de projeto — nesse caso o
     * chamador deve cair para o navegador real. A listagem nao exige login:
     * qualquer cookie valido (principalmente {@code cf_clearance}) junto do
     * mesmo User-Agent do Chrome costuma liberar a rota sem subir o navegador.</p>
     */
    String baixarListagemProjetosPorHttp() {
        try {
            Map<String, String> cookies = carregarCookies();
            if (cookies.isEmpty()) {
                log.info("FreelancerBR discovery HTTP: sem cookies, cairá para o Chrome");
                return null;
            }
            String url = config.getUrlBase() + "/projetos";
            FreelancerBrHttp.Resposta resposta = http.get(
                url, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                config.getUrlBase() + "/");
            String body = resposta.body();
            boolean temCards = !extrairProjetosDaListagem(body).isEmpty();
            boolean desafio = isDesafioCloudflare(resposta.status(), body);
            log.info("FreelancerBR discovery HTTP: status={}, bytes={}, cards={}, desafio={}",
                resposta.status(), body == null ? 0 : body.length(), temCards, desafio);
            if (resposta.status() != 200 || desafio || !temCards) {
                return null;
            }
            return body;
        } catch (Exception e) {
            log.warn("FreelancerBR discovery HTTP falhou: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Cookies armazenados no formato que o {@link JavaDriverless#addCookie(Map)}
     * aceita, para o Chrome iniciar a sessao com a mesma identidade do
     * transporte HTTP (mesmo {@code freelancer_live_session} e
     * {@code cf_clearance}; o clearance tambem vale para o desafio da
     * {@code /projetos} quando o Chrome o renova).
     */
    List<Map<String, Object>> cookiesParaChrome() {
        List<Map<String, Object>> lista = new ArrayList<>();
        for (Map.Entry<String, String> entrada : carregarCookies().entrySet()) {
            Map<String, Object> cookie = new HashMap<>();
            cookie.put("name", entrada.getKey());
            cookie.put("value", entrada.getValue());
            lista.add(cookie);
        }
        return lista;
 }

    /**
     * Extrai projetos do HTML da listagem, capturando o id COMPOSTO necessario
     * para o envio. Cada card traz {@code data-id} + {@code data-partition}, e o
     * link do projeto vem logo em seguida.
     */
    List<ProjetoBr> extrairProjetosDaListagem(String html) {
        List<ProjetoBr> encontrados = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return encontrados;
        }
        var doc = Jsoup.parse(html, config.getUrlBase());
        for (Element card : doc.select("[data-id][data-partition]")) {
            String id = card.attr("data-id");
            String partition = card.attr("data-partition");
            if (id.isBlank()) {
                continue;
            }
            Element link = card.selectFirst("a[href*=/projetos/]");
            String url = link == null ? "" : link.absUrl("href");
            String titulo = link == null ? "" : link.text().trim();
            String composto = partition.isBlank() ? id : id + "-" + partition;
            encontrados.add(new ProjetoBr(composto, url, titulo, textoDe(card, ".section-budget .bold"),
                textoDe(card, ".section-category .main-text")));
        }
        return encontrados;
    }

    private String textoDe(Element card, String seletor) {
        Element alvo = card.selectFirst(seletor);
        return alvo == null ? "" : alvo.text().trim();
    }

    /** Projeto do catalogo proprio do .com.br, com o id composto usado no envio. */
    record ProjetoBr(String idComposto, String url, String titulo, String orcamento, String categoria) {
    }

    List<ConversaResumo> listarConversasInbox() {
        return FreelancerBrInboxMapper.listarConversas(baixarInboxHtml(), conversaComposta);
    }

    List<MensagemConversa> listarMensagensConversa(long conversaId) {
        String chave = resolverConversa(conversaId);
        String html = getHtml(config.getUrlBase() + "/account/inbox/" + chave,
            "Freelancer.com.br conversa");
        return FreelancerBrInboxMapper.listarMensagens(html, conversaId);
    }

    /**
     * Envia o "Estou interessado" pelo endpoint nativo do .com.br.
     *
     * <p>Este e o caminho REAL de primeiro contato da plataforma: a pagina do
     * projeto fica sob {@code /projetos}, unica rota bloqueada pelo Cloudflare,
     * mas o POST em {@code /project/{composto}/interest} responde normalmente.
     * O token vem de {@code /csrfresh}, e nao do HTML do formulario.</p>
     */
    boolean enviarInteresseNativo(String idComposto, String mensagem) {
        if (idComposto == null || idComposto.isBlank()) {
            throw falhaEnvio("Id composto do projeto ausente; sincronize o discovery");
        }
        if (mensagem == null || mensagem.isBlank()) {
            throw falhaEnvio("Mensagem de interesse ausente");
        }
        String token = obterCsrfToken();
        String corpo = "_token=" + encode(token) + "&message=" + encode(mensagem);
        String url = urlEnvioInteresse() + "/project/" + idComposto + "/interest";
        FreelancerBrHttp.Resposta resposta = http.postForm(url, corpo, config.getUrlBase() + "/account/dashboard");
        log.info("FreelancerBR interesse: projeto={}, destino={}, status={}, bytes={}",
            idComposto, url, resposta.status(), resposta.body() == null ? 0 : resposta.body().length());
        // 302 = interesse registrado (o site redireciona de volta ao projeto).
        if (resposta.status() == 302 || resposta.status() == 200) {
            return true;
        }
        if (resposta.status() == 401 || resposta.status() == 419) {
            throw sessaoInvalida("envio de interesse retornou HTTP " + resposta.status());
        }
        throw falhaEnvio("Envio de interesse retornou HTTP " + resposta.status());
    }

    /**
     * O endpoint publico de interesse canonicou para o host sem "www"; o POST
     * feito pelo host antigo devolve 301 sem processar o formulario.
     */
    private String urlEnvioInteresse() {
        String base = config.getUrlBase();
        return base.replaceFirst("^https://www\\.freelancer\\.com\\.br$", "https://freelancer.com.br");
    }

    /** Token CSRF do Laravel; obrigatorio em todo POST autenticado do .com.br. */
    private String obterCsrfToken() {
        FreelancerBrHttp.Resposta resposta =
            http.getJson(config.getUrlBase() + "/csrfresh", config.getUrlBase() + "/account/inbox");
        if (!resposta.sucesso()) {
            throw sessaoInvalida("CSRF nao renovado (HTTP " + resposta.status() + ")");
        }
        try {
            String token = objectMapper.readTree(resposta.body()).path("token").asText("");
            if (token.isBlank()) {
                throw sessaoInvalida("CSRF vazio");
            }
            return token;
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(PlataformaFreela.FREELANCER_BR,
                MarketplaceAdapterErrorType.FALHA_PARSE, "Falha ao ler CSRF Freelancer.com.br", e);
        }
    }

    MensagemConversa enviarMensagemConversa(long conversaId, String texto) {
        if (texto == null || texto.isBlank()) throw falhaEnvio("Mensagem vazia");
        String chave = resolverConversa(conversaId);
        Map<String, String> cookies = new HashMap<>(carregarCookies());
        String csrf = atualizarCsrf(cookies);
        String boundary = "----FreelasAtende" + System.currentTimeMillis();
        String refId = "freelas-atende-" + System.currentTimeMillis();
        String body = multipart(boundary, Map.of("message", texto, "refId", refId));
        String url = config.getUrlBase() + "/account/inbox/" + chave;
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                .header("User-Agent", config.getUserAgent())
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Cookie", construirCookieHeader(cookies))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400 || !objectMapper.readTree(response.body()).path("success").asBoolean()) {
                throw falhaEnvio("Envio de mensagem retornou HTTP " + response.statusCode());
            }
            return MensagemConversa.builder().plataforma(PlataformaFreela.FREELANCER_BR)
                .id(FreelancerBrInboxMapper.idEstavel(refId)).idConversa(conversaId).texto(texto)
                .dataCriacaoMillis(System.currentTimeMillis()).enviadaPorMim(true).visualizada(true)
                .excluida(false).sistema(false).build();
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(PlataformaFreela.FREELANCER_BR,
                MarketplaceAdapterErrorType.FALHA_REDE, "Falha ao enviar mensagem Freelancer.com.br", e);
        }
    }

    private String resolverConversa(long conversaId) {
        String chave = conversaComposta.get(conversaId);
        if (chave == null) {
            listarConversasInbox();
            chave = conversaComposta.get(conversaId);
        }
        if (chave == null) throw falhaEnvio("Conversa nao encontrada; sincronize a inbox");
        return chave;
    }

    private String atualizarCsrf(Map<String, String> cookies) {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(config.getUrlBase() + "/csrfresh"))
                .header("User-Agent", config.getUserAgent()).header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest").header("Cookie", construirCookieHeader(cookies))
                .timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
            var json = objectMapper.readTree(response.body());
            String token = json.path("token").asText("");
            String xsrf = json.path("xsrf").asText("");
            if (response.statusCode() >= 400 || token.isBlank() || xsrf.isBlank()) throw falhaEnvio("CSRF nao renovado");
            cookies.put("XSRF-TOKEN", xsrf);
            return token;
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(PlataformaFreela.FREELANCER_BR,
                MarketplaceAdapterErrorType.FALHA_REDE, "Falha ao renovar CSRF Freelancer.com.br", e);
        }
    }

    private String multipart(String boundary, Map<String, String> campos) {
        StringBuilder body = new StringBuilder();
        campos.forEach((nome, valor) -> body.append("--").append(boundary).append("\r\n")
            .append("Content-Disposition: form-data; name=\"").append(nome).append("\"\r\n\r\n")
            .append(valor).append("\r\n"));
        return body.append("--").append(boundary).append("--\r\n").toString();
    }

    private String encode(String valor) {
        return URLEncoder.encode(valor == null ? "" : valor, StandardCharsets.UTF_8);
    }

    private MarketplaceAdapterException falhaEnvio(String mensagem) {
        return new MarketplaceAdapterException(PlataformaFreela.FREELANCER_BR,
            MarketplaceAdapterErrorType.FALHA_ENVIO, mensagem);
    }

    private String getHtml(String url, String descricao) {
        try {
            Map<String, String> cookies = carregarCookies();
            if (cookies.isEmpty()) {
                throw sessaoInvalida(descricao + ": cookies ausentes (faca login manual e atualize)");
            }
            // Sai por curl_cffi com fingerprint de Chrome: o Cloudflare recusa o
            // handshake TLS do HttpClient do Java mesmo com cookies validos.
            FreelancerBrHttp.Resposta response = http.get(
                url, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                config.getUrlBase() + "/account/dashboard");
            String body = response.body();
            if (isDesafioCloudflare(response.status(), body)) {
                throw sessaoInvalida(descricao + ": Cloudflare bloqueou (cf_clearance/sessao expirados)");
            }
            if (response.status() >= 400) {
                throw new MarketplaceAdapterException(
                    PlataformaFreela.FREELANCER_BR,
                    MarketplaceAdapterErrorType.FALHA_REDE,
                    descricao + " retornou HTTP " + response.status());
            }
            if (isPaginaDeslogada(body)) {
                throw sessaoInvalida(descricao + ": sessao nao autenticada (pagina mostra Conecte-se)");
            }
            return body;
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(
                PlataformaFreela.FREELANCER_BR,
                MarketplaceAdapterErrorType.FALHA_REDE,
                "Falha ao consultar " + descricao,
                e);
        }
    }

    private boolean isDesafioCloudflare(int status, String body) {
        return avaliarDesafio(status, body);
    }

    private MarketplaceAdapterException sessaoInvalida(String motivo) {
        return new MarketplaceAdapterException(
            PlataformaFreela.FREELANCER_BR,
            MarketplaceAdapterErrorType.FALHA_AUTENTICACAO,
            "Sessao Freelancer.com.br invalida: " + motivo);
    }

    boolean avaliarDesafio(int status, String body) {
        if (body == null) {
            return false;
        }
        // Paginas legítimas do site incorporam scripts do Cloudflare
        // (ex.: challenge-platform) em todas as respostas 200. Marcar desafio
        // apenas quando o status indica bloqueio ou o corpo e a pagina de
        // challenge exclusiva ("Just a moment"), nunca por script embutido.
        if (status == 403 || status == 503) {
            return true;
        }
        String lower = body.toLowerCase();
        boolean textoDesafio = lower.contains("verifica\u00e7\u00e3o de seguran\u00e7a")
            || lower.contains("just a moment")
            || lower.contains("cf-browser-verification")
            // NAO usar "cloudflare" + "bot" como sinal: a fonte Roboto ("ro-BOT-o")
            // aparece em toda pagina legitima e o script do Cloudflare tambem, o
            // que marcava sessao valida como bloqueada e disparava o alerta falso
            // de "Sessao Freelancer.com.br expirada" no Telegram a cada ciclo.
            || lower.contains("attention required! | cloudflare")
            || lower.contains("checking your browser before accessing");
        return textoDesafio;
    }

    private boolean isPaginaDeslogada(String body) {
        if (body == null) {
            return true;
        }
        String lower = body.toLowerCase();
        // Marcadores de conta logada; se nenhum aparece e ha "conecte-se", esta deslogado.
        boolean logado = lower.contains("/logout") || lower.contains("meu perfil")
            || lower.contains("minha conta") || lower.contains("data-user-id")
            || lower.contains("nav-profile");
        boolean deslogado = lower.contains("conecte-se") || lower.contains("fazer login")
            || lower.contains("/login");
        return !logado && deslogado;
    }

    Map<String, String> carregarCookies() {
        String arquivo = config.getCookiesArquivo();
        if (arquivo.isBlank() || !java.nio.file.Files.exists(java.nio.file.Path.of(arquivo))) {
            return Map.of();
        }
        try {
            var root = objectMapper.readTree(java.nio.file.Path.of(arquivo).toFile());
            Map<String, String> cookies = new HashMap<>();
            if (root.isObject()) {
                root.fields().forEachRemaining(e -> cookies.put(e.getKey(), e.getValue().asText("")));
            } else if (root.isArray()) {
                for (var cookie : root) {
                    String name = cookie.path("name").asText("");
                    String value = cookie.path("value").asText("");
                    if (!name.isBlank() && !value.isBlank()) {
                        cookies.put(name, value);
                    }
                }
            }
            cookies.entrySet().removeIf(e -> e.getKey().isBlank() || e.getValue().isBlank());
            return cookies;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(
                PlataformaFreela.FREELANCER_BR,
                MarketplaceAdapterErrorType.FALHA_PARSE,
                "Falha ao carregar cookies Freelancer.com.br",
                e);
        }
    }

    boolean temSessaoValida() {
        // O discovery migrou para a API publica e nao exige sessao. A sessao
        // cookie-based so e necessaria para a inbox autenticada (atras do
        // Cloudflare), entao a validade da sessao e aferida pela inbox.
        try {
            if (carregarCookies().isEmpty()) {
                return false;
            }
            String html = baixarInboxHtml();
            return html != null && !html.isBlank();
        } catch (MarketplaceAdapterException e) {
            return false;
        }
    }

    private String construirCookieHeader(Map<String, String> cookies) {
        List<String> partes = new ArrayList<>();
        cookies.forEach((nome, valor) -> partes.add(nome + "=" + valor));
        return String.join("; ", partes);
    }

    private synchronized HttpClient client() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        }
        return httpClient;
    }
}
