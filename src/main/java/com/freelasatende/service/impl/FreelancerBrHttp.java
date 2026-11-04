package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.FreelancerBrConfig;
import com.freelasatende.model.enums.PlataformaFreela;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Transporte HTTP do Freelancer.com.br com o HttpClient padrão do Java e os cookies
 * importados manualmente. Não imita navegador: se o Cloudflare recusar, a resposta volta
 * com o desafio e o FreelancerBrClient registra sessão inválida.
 *
 * <p>O backend Laravel do .com.br <b>rotaciona o cookie de sessão a cada resposta</b>:
 * o transporte regrava o arquivo com os valores novos, mantendo a sessão viva. Sem isso
 * ela morria no {@code Max-Age} original e o sistema pedia login manual todo dia.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class FreelancerBrHttp {

    private static final int TIMEOUT_SEGUNDOS = 60;
    private static final int CONNECT_TIMEOUT_SEGUNDOS = 30;

    private final FreelancerBrConfig config;
    private final ObjectMapper objectMapper;

    private HttpClient comRedirect, semRedirect;

    /** Resposta bruta do transporte: status, URL final e corpo já decodificado. */
    record Resposta(int status, String url, String body) {
        boolean sucesso() {
            return status >= 200 && status < 400;
        }
    }

    Resposta get(String url, String accept, String referer) {
        return executar("GET", url, accept, referer, null, null, true);
    }

    Resposta getJson(String url, String referer) {
        return executar("GET", url, "application/json, text/plain, */*", referer, null, null, true);
    }

    /** POST de formulário: não segue redirect (o 302 de volta ao /projetos viraria falso 403). */
    Resposta postForm(String url, String corpo, String referer) {
        return executar("POSTFORM", url, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            referer, corpo, "application/x-www-form-urlencoded", false);
    }

    private Resposta executar(String metodo, String url, String accept, String referer,
                              String corpo, String contentType, boolean seguirRedirect) {
        Map<String, String> cookies = lerCookies();
        try {
            HttpRequest.Builder pedido = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_SEGUNDOS))
                .header("User-Agent", userAgentEfetivo())
                .header("Accept", accept)
                .header("Accept-Language", "pt-BR,pt;q=0.9");
            if (referer != null && !referer.isBlank()) pedido.header("Referer", referer);
            if (!cookies.isEmpty()) pedido.header("Cookie", cookies.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; ")));
            if (contentType != null && !contentType.isBlank()) pedido.header("Content-Type", contentType);
            if ("POSTFORM".equals(metodo)) {
                pedido.POST(HttpRequest.BodyPublishers.ofString(corpo == null ? "" : corpo));
            } else {
                pedido.GET();
            }
            HttpResponse<String> resposta = cliente(seguirRedirect)
                .send(pedido.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            regravaCookiesSeMudaram(cookies, resposta.headers().allValues("Set-Cookie"));
            return new Resposta(resposta.statusCode(), resposta.uri().toString(),
                resposta.body() == null ? "" : resposta.body());
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw falhaRede("Transporte HTTP Freelancer.com.br interrompido", e);
        } catch (HttpTimeoutException e) {
            throw falhaRede("Timeout no transporte HTTP Freelancer.com.br", e);
        } catch (Exception e) {
            throw falhaRede("Falha no transporte HTTP Freelancer.com.br", e);
        }
    }

    private synchronized HttpClient cliente(boolean seguirRedirect) {
        if (seguirRedirect && comRedirect == null) comRedirect = novoCliente(HttpClient.Redirect.NORMAL);
        if (!seguirRedirect && semRedirect == null) semRedirect = novoCliente(HttpClient.Redirect.NEVER);
        return seguirRedirect ? comRedirect : semRedirect;
    }

    private static HttpClient novoCliente(HttpClient.Redirect redirect) {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEGUNDOS))
            .followRedirects(redirect).build();
    }

    /** Cookies no formato {@code {"nome": "valor"}}; ausente/vazio/ilegível = mapa vazio. */
    private Map<String, String> lerCookies() {
        try {
            String arquivo = config == null || objectMapper == null ? null : config.getCookiesArquivo();
            if (arquivo == null || arquivo.isBlank()) return Map.of();
            Path caminho = Path.of(arquivo);
            if (!Files.exists(caminho) || Files.size(caminho) == 0) return Map.of();
            var raiz = objectMapper.readTree(caminho.toFile());
            if (!raiz.isObject()) return Map.of();
            Map<String, String> cookies = new LinkedHashMap<>();
            raiz.fields().forEachRemaining(campo -> {
                String valor = campo.getValue().asText("");
                if (!campo.getKey().isBlank() && !valor.isBlank()) cookies.put(campo.getKey(), valor);
            });
            return cookies;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Regrava o arquivo quando a resposta rotacionou algum valor; falha só gera warn. */
    private void regravaCookiesSeMudaram(Map<String, String> atuais, List<String> setCookies) {
        if (setCookies == null || setCookies.isEmpty()) return;
        Map<String, String> novos = new LinkedHashMap<>(atuais);
        for (String cabecalho : setCookies) {
            if (cabecalho == null) continue;
            String par = cabecalho.split(";", 2)[0].trim();
            int igual = par.indexOf('=');
            if (igual <= 0) continue;
            String nome = par.substring(0, igual).trim();
            String valor = par.substring(igual + 1).trim();
            if (!nome.isEmpty() && !valor.isEmpty() && !"deleted".equalsIgnoreCase(valor)) {
                novos.put(nome, valor);
            }
        }
        if (novos.equals(atuais)) return;
        try {
            Path destino = Path.of(config.getCookiesArquivo());
            Path temporario =
                Files.createTempFile(destino.toAbsolutePath().getParent(), "cookies", ".json");
            try {
                Files.writeString(temporario, objectMapper.writeValueAsString(novos), StandardCharsets.UTF_8);
                try {
                    Files.move(temporario, destino,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception semAtomico) {
                    Files.move(temporario, destino, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporario);
            }
        } catch (Exception e) {
            log.warn("FreelancerBrHttp: nao foi possivel regravar o arquivo de cookies");
        }
    }

    /** UA do cf_clearance armazenado (mesmo UA do Chrome que o gerou); sem ele, cai no default. */
    private String userAgentEfetivo() {
        try {
            Path arquivo = Path.of(config.getCookiesArquivo() + ".ua");
            if (Files.exists(arquivo)) {
                String ua = Files.readString(arquivo).trim();
                if (!ua.isBlank()) return ua;
            }
        } catch (Exception e) {
            // Sem UA exportado: segue com o default.
        }
        return config.getUserAgent();
    }

    private MarketplaceAdapterException falhaRede(String mensagem, Throwable causa) {
        return new MarketplaceAdapterException(
            PlataformaFreela.FREELANCER_BR, MarketplaceAdapterErrorType.FALHA_REDE, mensagem, causa);
    }
}
