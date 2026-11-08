package com.freelasatende.service.impl;

import com.freelasatende.config.FreelasConfig;
import com.freelasatende.config.OperacaoDefaults;
import com.freelasatende.model.dto.ConexoesDisponiveis;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.Cliente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import com.freelasatende.service.interfaceServico.ScraperService;
import com.freelasatende.util.FreelasApiUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Scraper para 99Freelas usando Jsoup para parsing robusto de HTML.
 * Suporta paginação e fallback para API interna.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScraperServiceImpl implements ScraperService {

    private final AutenticacaoService autenticacaoService;
    private final FreelasConfig freelasConfig;
    private final ObjectMapper objectMapper;

    private ScraperHttpClient httpClient;
    private ScraperClienteParser clienteParser;
    private ScraperInboxClient inboxClient;

    @Value("${sistema.categoria-filtro:web-mobile-e-software}")
    private String categoriaFiltro;

    @Value("${scraper.max-paginas:0}")
    private int maxPaginasConfigurado;

    @Value("${scraper.delay-entre-paginas-ms:-1}")
    private long delayEntrePaginasMsConfigurado;

    @Value("${scraper.login-cache-millis:8000}")
    private long loginCacheMillis;

    @Value("${auth.cookies-arquivo:data/cookies.json}")
    private String arquivoCookies;

    @Value("${freelancer-br.http-script:scripts/freelas_http.py}")
    private String httpScript;

    private volatile Boolean loginCacheValor = null;
    private volatile long loginCacheAteMillis = 0;
    private volatile long loginCacheVersaoCookies = Long.MIN_VALUE;

    private synchronized ScraperHttpClient httpClient() {
        if (httpClient == null) {
            httpClient = new ScraperHttpClient(
                autenticacaoService, freelasConfig, objectMapper, arquivoCookies, httpScript);
        }
        return httpClient;
    }

    private synchronized ScraperClienteParser clienteParser() {
        if (clienteParser == null) {
            clienteParser = new ScraperClienteParser(objectMapper);
        }
        return clienteParser;
    }

    private ScraperProjetoParser projetoParser() {
        return new ScraperProjetoParser(urlBase());
    }

    private synchronized ScraperInboxClient inboxClient() {
        if (inboxClient == null) {
            inboxClient = new ScraperInboxClient(autenticacaoService, urlBase());
        }
        return inboxClient;
    }

    private String urlBase() {
        return freelasConfig.getUrlBase();
    }

    private String refererProjects() {
        return urlBase() + "/projects";
    }

    private int maxPaginas() {
        if (maxPaginasConfigurado > 0) {
            return maxPaginasConfigurado;
        }
        return OperacaoDefaults.SCRAPER_MAX_PAGINAS;
    }

    private long delayEntrePaginasMs() {
        if (delayEntrePaginasMsConfigurado >= 0) {
            return delayEntrePaginasMsConfigurado;
        }
        return OperacaoDefaults.SCRAPER_DELAY_ENTRE_PAGINAS_MS;
    }

    @Override
    public List<Projeto> buscarProjetosNovos() {
        List<Projeto> todosProjetos = new ArrayList<>();
        Map<Long, Projeto> projetosPorId = new LinkedHashMap<>();

        try {
            if (!verificarLogin()) {
                log.warn("Não está logado no 99Freelas");
                return todosProjetos;
            }

            int maxPaginas = maxPaginas();
            int paginasLidas = 0;
            for (int pagina = 1; pagina <= maxPaginas; pagina++) {
                String url = urlBase() + "/projects?order=mais-recentes"
                    + montarFiltroCategoriaQuery()
                    + (pagina > 1 ? "&page=" + pagina : "");
                String html = fazerGet(url);
                paginasLidas = pagina;

                List<Projeto> projetos = projetoParser().parsearProjetos(html);
                for (Projeto projeto : projetos) {
                    PlataformaProjetoHelper.normalizarProjeto(projeto);
                    if (projeto != null && projeto.getId() != null) {
                        projetosPorId.putIfAbsent(projeto.getId(), projeto);
                    }
                }

                log.trace("Página {}: {} projetos encontrados ({} únicos até agora)", pagina, projetos.size(), projetosPorId.size());

                if (projetos.isEmpty()) {
                    break; // Sem mais projetos
                }

                if (pagina < maxPaginas) {
                    Thread.sleep(delayEntrePaginasMs());
                }
            }

            log.debug("Total: {} projetos encontrados em {} páginas",
                projetosPorId.size(), paginasLidas);

        } catch (Exception e) {
            log.debug("Erro ao buscar projetos: {}", e.getMessage());
        }

        todosProjetos.addAll(projetosPorId.values());
        return todosProjetos;
    }

    @Override
    public Projeto buscarDetalhesProjeto(Long id, String slug) {
        try {
            String path = (slug != null && !slug.isBlank())
                ? "/project/" + slug + "-" + id + "?fs=t"
                : "/project/projeto-" + id;
            String html = fazerGet(urlBase() + path);
            return PlataformaProjetoHelper.normalizarProjeto(projetoParser().parsearDetalhes(html, id));
        } catch (Exception e) {
            log.error("Erro ao buscar detalhes do projeto {}: {}", id, e.getMessage());
        }
        return null;
    }

    @Override
    public Cliente buscarCliente(Long id) {
        try {
            String json = fazerGet(
                urlBase() + "/services/project/getMediaRankingGeralDoCliente?idProjeto=" + id
            );
            return clienteParser().parsearCliente(json);
        } catch (Exception e) {
            log.error("Erro ao buscar cliente {}: {}", id, e.getMessage());
        }
        return null;
    }

    @Override
    public boolean enviarPergunta(Long projetoId, String mensagem) {
        try {
            String url = urlBase() + "/services/project/enviarPerguntaParaCliente/" + projetoId;

            String body = objectMapper.writeValueAsString(Map.of(
                "mensagem", mensagem,
                "mensagem_htmlid", "mensagem-pergunta",
                "existeConversa", true,
                "confirmarAcao", false
            ));

            HttpResponse<String> response = fazerPostJson(url, body, refererProjects());
            boolean enviada = response.statusCode() >= 200 && response.statusCode() < 400;
            if (enviada) {
                log.debug("Pergunta enviada para projeto {}", projetoId);
            }
            return enviada;

        } catch (Exception e) {
            log.error("Erro ao enviar pergunta para projeto {}: {}", projetoId, e.getMessage());
            return false;
        }
    }

    @Override
    public Proposta enviarProposta(Projeto projeto, Proposta proposta) {
        try {
            if (projeto == null || projeto.getId() == null || proposta == null) {
                return null;
            }

            String url = urlBase() + "/services/project/enviarProposta";
            Map<String, Object> payload = new HashMap<>();
            payload.put("idProjeto", String.valueOf(projeto.getId()));
            payload.put("isAdmin", false);
            payload.put("oferta", proposta.getValorProposto() != null ? proposta.getValorProposto().setScale(2).toPlainString() : "0.00");
            payload.put("oferta_htmlid", "oferta");
            payload.put("duracaoEstimada", 1);
            payload.put("duracaoEstimada_htmlid", "duracao-estimada");
            payload.put("proposta", proposta.getMensagem() != null ? proposta.getMensagem() : "");
            payload.put("proposta_htmlid", "proposta");
            payload.put("promover", false);
            payload.put("promover_htmlid", "highlight-bid");
            payload.put("novaProposta", true);
            payload.put("valorMinimoCents", proposta.getValorMinimo() != null
                ? proposta.getValorMinimo().multiply(BigDecimal.valueOf(100)).intValue()
                : 0);
            payload.put("confirmarAcao", false);

            HttpResponse<String> response = fazerPostJson(url, objectMapper.writeValueAsString(payload), refererProjetoBid(projeto));
            if (!respostaSucesso(response)) {
                log.error("Falha HTTP ao enviar proposta para projeto {}: status {}",
                    projeto.getId(),
                    response != null ? response.statusCode() : null);
                return null;
            }

            proposta.setStatus("ENVIADA");
            proposta.setFoiMinha(true);
            proposta.setDataProposta(LocalDateTime.now());
            return proposta;
        } catch (Exception e) {
            log.error("Erro ao enviar proposta para projeto {}: {}", projeto != null ? projeto.getId() : null, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean cancelarProposta(Long projetoId) {
        try {
            String url = urlBase() + "/services/project/cancelarProposta?idProjeto=" + projetoId;
            HttpResponse<String> response = fazerPostSemBody(url, refererProjects());
            return response.statusCode() >= 200 && response.statusCode() < 400;
        } catch (Exception e) {
            log.error("Erro ao cancelar proposta {}: {}", projetoId, e.getMessage());
            return false;
        }
    }

    @Override
    public List<ConversaResumo> listarConversasInbox() {
        return inboxClient().listarConversas(this::fazerGetServico, this::fazerGet);
    }

    @Override
    public List<MensagemConversa> listarMensagensConversa(Long conversaId) {
        return listarMensagensConversa(conversaId, false);
    }

    @Override
    public List<MensagemConversa> listarMensagensConversa(Long conversaId, boolean marcarComoVisualizada) {
        return inboxClient().listarMensagens(
            conversaId,
            marcarComoVisualizada,
            this::fazerGetServico,
            this::fazerGet
        );
    }

    @Override
    public MensagemConversa enviarMensagemConversa(Long conversaId, String texto) {
        try {
            String url = urlBase() + "/services/user/enviarMensagemConversa";
            String body = FreelasApiUtils.encodeDoubleFormData(objectMapper, Map.of(
                "idConversa", conversaId,
                "texto", texto
            ));

            HttpResponse<String> response = fazerPostForm(
                url,
                body,
                urlBase() + "/messages/inbox/" + conversaId
            );
            if (!respostaSucesso(response)) {
                log.error("Falha HTTP ao enviar mensagem na conversa {}: status {}",
                    conversaId,
                    response != null ? response.statusCode() : null);
                return null;
            }

            JsonNode root = FreelasApiUtils.decodeUrlEncodedJson(objectMapper, response.body());
            long mensagemId = root.path("result").path("id").asLong(0L);
            if (mensagemId <= 0) {
                log.error("Resposta de envio de conversa {} sem id de mensagem válido", conversaId);
                return null;
            }
            return MensagemConversa.builder()
                .id(mensagemId)
                .idConversa(root.path("result").path("idConversa").asLong())
                .idPessoa(root.path("result").path("idPessoa").asLong())
                .texto(root.path("result").path("texto").asText(null))
                .enviadaPorMim(true)
                .visualizada(true)
                .build();
        } catch (Exception e) {
            log.error("Erro ao enviar mensagem na conversa {}: {}", conversaId, e.getMessage());
            return null;
        }
    }

    private boolean respostaSucesso(HttpResponse<String> response) {
        return response != null && response.statusCode() >= 200 && response.statusCode() < 400;
    }

    @Override
    public ConexoesDisponiveis obterConexoesDisponiveis() {
        try {
            String html = fazerGet(urlBase() + "/connections/stock");
            return FreelasApiUtils.extractConnectionStock(html);
        } catch (Exception e) {
            log.error("Erro ao obter conexoes disponiveis: {}", e.getMessage());
            return ConexoesDisponiveis.builder().build();
        }
    }

    @Override
    public boolean confirmarMfaSessao(Long sessaoId, String codigo) {
        try {
            String url = urlBase() + "/services/user/confirmarMfaSessao/" + sessaoId;
            String body = objectMapper.writeValueAsString(Map.of("code", codigo));
            HttpResponse<String> response = fazerPostJson(url, body, urlBase() + "/mfa/session/confirm/" + sessaoId);
            return response.statusCode() >= 200 && response.statusCode() < 400;
        } catch (Exception e) {
            log.error("Erro ao confirmar MFA da sessao {}: {}", sessaoId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean verificarLogin() {
        long agora = System.currentTimeMillis();
        long versaoCookies = autenticacaoService.versaoCookies();
        Boolean valorCache = loginCacheValor;
        if (valorCache != null && agora < loginCacheAteMillis && loginCacheVersaoCookies == versaoCookies) {
            return valorCache;
        }
        try {
            String html = fazerGet(urlBase() + "/dashboard");
            boolean logado = html != null
                && html.length() > 1000
                && !html.contains("frmEfetuarLogin")
                && !html.contains("Faça seu login");
            loginCacheValor = logado;
            loginCacheVersaoCookies = versaoCookies;
            loginCacheAteMillis = System.currentTimeMillis() + Math.max(1000, loginCacheMillis);
            return logado;
        } catch (Exception e) {
            if (MarketplaceSessionFailure.isSessao99FreelasInvalida(e)) {
                log.warn("Sessao 99Freelas invalida ao verificar login: {}", e.getMessage());
            } else if (MarketplaceSessionFailure.isFalhaTemporariaRede(e)) {
                log.warn("99Freelas indisponivel ao verificar login: {}", e.getMessage());
            } else {
                log.error("Erro ao verificar login: {}", e.getMessage());
            }
            loginCacheValor = false;
            loginCacheVersaoCookies = versaoCookies;
            loginCacheAteMillis = System.currentTimeMillis() + Math.max(1000, loginCacheMillis);
            return false;
        }
    }

    private String fazerGet(String url) throws Exception {
        return httpClient().get(url, refererParaGet(url));
    }

    private String refererParaGet(String url) {
        if (url == null || url.isBlank()) {
            return urlBase() + "/dashboard";
        }
        if (url.contains("/projects")) {
            return urlBase() + "/dashboard";
        }
        if (url.contains("/project/")) {
            return refererProjects();
        }
        if (url.contains("/connections/stock")) {
            return urlBase() + "/dashboard";
        }
        return urlBase() + "/dashboard";
    }

    private HttpResponse<String> fazerPostJson(String url, String body, String referer) throws Exception {
        return httpClient().postJson(url, body, referer != null ? referer : refererProjects());
    }

    private HttpResponse<String> fazerPostForm(String url, String body, String referer) throws Exception {
        return httpClient().postForm(url, body, referer);
    }

    private HttpResponse<String> fazerPostSemBody(String url, String referer) throws Exception {
        return httpClient().postSemBody(url, referer);
    }

    private String montarFiltroCategoriaQuery() {
        if (categoriaFiltro == null || categoriaFiltro.isBlank()) {
            return "";
        }
        String valorNormalizado = categoriaFiltro.trim().toLowerCase();
        if ("todas".equals(valorNormalizado) || "all".equals(valorNormalizado)) {
            return "";
        }
        return "&categoria=" + valorNormalizado;
    }

    private JsonNode fazerGetServico(String path, Map<String, Object> data, String referer) throws Exception {
        String url = urlBase() + path + "?data=" + FreelasApiUtils.encodeQueryData(objectMapper, data);
        return httpClient().getServico(url, path, referer);
    }

    private String refererProjetoBid(Projeto projeto) {
        if (projeto != null && projeto.getUrlProjeto() != null && !projeto.getUrlProjeto().isBlank()) {
            return projeto.getUrlProjeto().replace("/project/", "/project/bid/");
        }
        return refererProjects();
    }

    private long safeLong(Long value) {
        return value != null ? value : -1L;
    }
}
