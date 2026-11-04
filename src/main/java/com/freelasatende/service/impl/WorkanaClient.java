package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.WorkanaConfig;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.enums.PlataformaFreela;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import static com.freelasatende.service.impl.WorkanaClientSupport.absolutizarUrl;
import static com.freelasatende.service.impl.WorkanaClientSupport.carregarCookies;
import static com.freelasatende.service.impl.WorkanaClientSupport.construirCookieHeader;
import static com.freelasatende.service.impl.WorkanaClientSupport.encodePathSegment;
import static com.freelasatende.service.impl.WorkanaClientSupport.param;
import static com.freelasatende.service.impl.WorkanaClientSupport.resumirResposta;
import static com.freelasatende.service.impl.WorkanaErrorClassifier.tipoErroEnvio;
import static com.freelasatende.service.impl.WorkanaErrorClassifier.indicaAcessoNegado;
import static com.freelasatende.service.impl.WorkanaErrorClassifier.tipoAcessoNegado;

@Component
class WorkanaClient {

    private final WorkanaConfig config;
    private final ObjectMapper objectMapper;
    private HttpClient httpClient;
    private final WorkanaDiscoveryClient discoveryClient;
    private final Map<Long, WorkanaConversaContext> conversas = Collections.synchronizedMap(
        new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, WorkanaConversaContext> eldest) {
                return size() > 200;
            }
        }
    );

    WorkanaClient(WorkanaConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.discoveryClient = new WorkanaDiscoveryClient(config, objectMapper, this::client);
    }

    JsonNode buscarProjetosJson() {
        return discoveryClient.buscarProjetosJson();
    }

    JsonNode verificarLoginEPrepararDiscovery() {
        return discoveryClient.verificarLoginEPrepararDiscovery();
    }

    JsonNode buscarProjetosJsonParaDiscovery() {
        return discoveryClient.buscarProjetosJsonParaDiscovery();
    }

    boolean enviarPergunta(String projectSlug, String mensagem) {
        if (projectSlug == null || projectSlug.isBlank()) {
            throw erroValidacao("Slug do projeto Workana ausente");
        }
        if (mensagem == null || mensagem.isBlank()) {
            throw erroValidacao("Mensagem Workana vazia");
        }

        Map<String, String> cookies = carregarCookies(config, objectMapper);
        if (cookies.isEmpty()) {
            throw erroValidacao("Cookies Workana ausentes em " + config.getCookiesArquivo());
        }

        String slug = projectSlug.trim();
        try {
            WorkanaPermissaoContato permissao = WorkanaPermissionClient.consultar(
                client(), config, objectMapper, slug, cookies
            );
            if (!permissao.permitido()) {
                throw new MarketplaceAdapterException(
                    PlataformaFreela.WORKANA,
                    permissao.semConexoes()
                        ? MarketplaceAdapterErrorType.LIMITE_CONEXOES
                        : MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL,
                    "Workana negou o contato inicial: " + permissao.motivo()
                );
            }

            String formUrl = config.getUrlBase() + "/messages/bid/" + encodePathSegment(slug) + "/?tab=message";
            HttpResponse<String> formResponse = enviar(
                HttpRequest.newBuilder()
                    .uri(URI.create(formUrl))
                    .header("User-Agent", config.getUserAgent())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                    .header("Cookie", construirCookieHeader(cookies))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build()
            );
            if (formResponse.statusCode() >= 400) {
                MarketplaceAdapterErrorType tipoErro = tipoErroEnvio(formResponse);
                throw new MarketplaceAdapterException(
                    PlataformaFreela.WORKANA,
                    tipoErro,
                    "Workana form de pergunta retornou HTTP " + formResponse.statusCode()
                        + ": " + resumirResposta(formResponse.body())
                );
            }

            if (indicaAcessoNegado(formResponse.body())) {
                throw new MarketplaceAdapterException(
                    PlataformaFreela.WORKANA,
                    tipoAcessoNegado(formResponse.body()),
                    "Workana negou permissao para iniciar contato (make_bid_action) em " + formUrl
                        + "; verifique conexoes disponiveis ou plano da conta"
                );
            }

            WorkanaQuestionFormExtractor.WorkanaFormPergunta form =
                new WorkanaQuestionFormExtractor(config).extrair(formResponse.body(), slug, cookies);
            String body = montarFormPergunta(form.campos(), mensagem);
            HttpRequest.Builder postBuilder = HttpRequest.newBuilder()
                .uri(URI.create(form.actionUrl()))
                .header("User-Agent", config.getUserAgent())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", config.getUrlBase())
                .header("Referer", formUrl)
                .header("Cookie", construirCookieHeader(cookies))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body));
            if (!form.csrfHeaderName().isBlank() && !form.csrfHeaderValue().isBlank()) {
                postBuilder.header(form.csrfHeaderName(), form.csrfHeaderValue());
            }
            HttpResponse<String> response = enviar(postBuilder.build());

            if (response.statusCode() < 200 || response.statusCode() >= 400) {
                MarketplaceAdapterErrorType tipoErro = tipoErroEnvio(response);
                throw new MarketplaceAdapterException(
                    PlataformaFreela.WORKANA,
                    tipoErro,
                    "Workana envio de pergunta retornou HTTP " + response.statusCode()
                        + " em " + form.actionUrl()
                        + ": " + resumirResposta(response.body())
                );
            }
            return true;
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(
                PlataformaFreela.WORKANA,
                MarketplaceAdapterErrorType.FALHA_REDE,
                "Falha ao enviar pergunta Workana",
                e
            );
        }
    }

    List<ConversaResumo> listarConversasInbox() {
        Map<String, String> cookies = carregarCookies(config, objectMapper);
        if (cookies.isEmpty()) {
            throw erroValidacao("Cookies Workana ausentes em " + config.getCookiesArquivo());
        }

        String url = config.getUrlBase() + "/roaster/search/";
        try {
            HttpResponse<String> response = enviar(
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", config.getUserAgent())
                    .header("Accept", "application/json,text/plain,*/*")
                    .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Cookie", construirCookieHeader(cookies))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build()
            );
            if (response.statusCode() >= 400) {
                throw new MarketplaceAdapterException(
                    PlataformaFreela.WORKANA,
                    MarketplaceAdapterErrorType.FALHA_REDE,
                    "Workana inbox retornou HTTP " + response.statusCode()
                );
            }
            JsonNode projetos = objectMapper.readTree(response.body());
            return WorkanaInboxMapper.listarConversas(config, projetos, conversas);
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(
                PlataformaFreela.WORKANA,
                MarketplaceAdapterErrorType.FALHA_REDE,
                "Falha ao listar inbox Workana",
                e
            );
        }
    }

    List<MensagemConversa> listarMensagensConversa(Long conversaId) {
        WorkanaConversaContext contexto = contexto(conversaId);
        Map<String, String> cookies = carregarCookies(config, objectMapper);
        try {
            HttpResponse<String> response = buscarPaginaMensagens(conversaId, contexto, cookies);
            JsonNode messageIndexData = extrairMessageIndexData(response.body());
            atualizarContextoDePagina(conversaId, messageIndexData);
            return WorkanaInboxMapper.listarMensagens(conversaId, messageIndexData);
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(
                PlataformaFreela.WORKANA,
                MarketplaceAdapterErrorType.FALHA_REDE,
                "Falha ao listar mensagens Workana",
                e
            );
        }
    }

    MensagemConversa enviarMensagemConversa(Long conversaId, String texto) {
        if (texto == null || texto.isBlank()) {
            throw erroValidacao("Mensagem Workana vazia");
        }
        WorkanaConversaContext contexto = contexto(conversaId);
        Map<String, String> cookies = carregarCookies(config, objectMapper);
        try {
            HttpResponse<String> formResponse = buscarPaginaMensagens(conversaId, contexto, cookies);
            JsonNode messageIndexData = extrairMessageIndexData(formResponse.body());
            atualizarContextoDePagina(conversaId, messageIndexData);
            WorkanaQuestionFormExtractor.WorkanaFormPergunta form =
                new WorkanaQuestionFormExtractor(config).extrair(formResponse.body(), contexto.slugProjeto(), cookies);
            String body = montarFormMensagem(form.campos(), texto, false);
            HttpRequest.Builder postBuilder = HttpRequest.newBuilder()
                .uri(URI.create(form.actionUrl()))
                .header("User-Agent", config.getUserAgent())
                .header("Accept", "application/json,text/html,application/xhtml+xml,*/*")
                .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", config.getUrlBase())
                .header("Referer", contexto.inboxUrl())
                .header("Cookie", construirCookieHeader(cookies))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body));
            if (!form.csrfHeaderName().isBlank() && !form.csrfHeaderValue().isBlank()) {
                postBuilder.header(form.csrfHeaderName(), form.csrfHeaderValue());
            }
            HttpResponse<String> response = enviar(postBuilder.build());
            if (response.statusCode() < 200 || response.statusCode() >= 400) {
                MarketplaceAdapterErrorType tipoErro = tipoErroEnvio(response);
                throw new MarketplaceAdapterException(
                    PlataformaFreela.WORKANA,
                    tipoErro,
                    "Workana envio de mensagem retornou HTTP " + response.statusCode()
                        + " em " + form.actionUrl()
                        + ": " + resumirResposta(response.body())
                );
            }
            return MensagemConversa.builder()
                .plataforma(PlataformaFreela.WORKANA)
                .id(System.currentTimeMillis())
                .idConversa(conversaId)
                .nomePessoa(config.getWorkerSlug())
                .texto(texto)
                .dataCriacaoMillis(System.currentTimeMillis())
                .enviadaPorMim(true)
                .visualizada(true)
                .excluida(false)
                .sistema(false)
                .build();
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(
                PlataformaFreela.WORKANA,
                MarketplaceAdapterErrorType.FALHA_ENVIO,
                "Falha ao enviar mensagem Workana",
                e
            );
        }
    }

    private HttpResponse<String> enviar(HttpRequest request) throws Exception {
        return client().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> buscarPaginaMensagens(
        Long conversaId,
        WorkanaConversaContext contexto,
        Map<String, String> cookies
    ) throws Exception {
        HttpResponse<String> response = buscarPaginaMensagens(contexto, cookies, urlsPaginaMensagens(contexto));
        if (response.statusCode() < 400) {
            return response;
        }

        listarConversasInbox();
        WorkanaConversaContext atualizado = conversas.get(conversaId);
        if (atualizado != null && !atualizado.equals(contexto)) {
            response = buscarPaginaMensagens(atualizado, cookies, urlsPaginaMensagens(atualizado));
            if (response.statusCode() < 400) {
                return response;
            }
        }

        if (response == null) {
            throw new MarketplaceAdapterException(
                PlataformaFreela.WORKANA,
                MarketplaceAdapterErrorType.FALHA_REDE,
                "Workana mensagens sem URL valida para conversa " + conversaId
            );
        }
        if (response.statusCode() == 404 || response.statusCode() == 410) {
            throw new MarketplaceAdapterException(
                PlataformaFreela.WORKANA,
                MarketplaceAdapterErrorType.CONVERSA_INACESSIVEL,
                "Workana conversa " + conversaId + " inacessivel ou removida: HTTP " + response.statusCode()
            );
        }
        throw new MarketplaceAdapterException(
            PlataformaFreela.WORKANA,
            MarketplaceAdapterErrorType.FALHA_REDE,
            "Workana mensagens retornou HTTP " + response.statusCode()
        );
    }

    private HttpResponse<String> buscarPaginaMensagens(
        WorkanaConversaContext contexto,
        Map<String, String> cookies,
        Iterable<String> urls
    ) throws Exception {
        HttpResponse<String> ultimaResposta = null;
        for (String url : urls) {
            ultimaResposta = enviar(
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", config.getUserAgent())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                    .header("Cookie", construirCookieHeader(cookies))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build()
            );
            if (ultimaResposta.statusCode() < 400) {
                return ultimaResposta;
            }
        }
        return ultimaResposta;
    }

    private List<String> urlsPaginaMensagens(WorkanaConversaContext contexto) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        adicionarUrl(urls, contexto.inboxUrl());
        adicionarUrl(urls, contexto.postUrl());
        if (contexto.slugProjeto() != null && !contexto.slugProjeto().isBlank()) {
            adicionarUrl(urls, config.getUrlBase() + "/inbox/" + encodePathSegment(contexto.slugProjeto())
                + "/" + encodePathSegment(config.getWorkerSlug()));
            adicionarUrl(urls, config.getUrlBase() + "/messages/index/" + encodePathSegment(contexto.slugProjeto())
                + "/" + encodePathSegment(config.getWorkerSlug()));
        }
        return new ArrayList<>(urls);
    }

    private void adicionarUrl(LinkedHashSet<String> urls, String url) {
        String absoluta = absolutizarUrl(config, url);
        if (!absoluta.isBlank()) {
            urls.add(absoluta);
        }
    }

    private String montarFormPergunta(Map<String, String> campos, String mensagem) {
        return montarFormMensagem(campos, mensagem, true);
    }

    private String montarFormMensagem(Map<String, String> campos, String mensagem, boolean primeiraMensagem) {
        List<String> partes = new ArrayList<>();
        campos.forEach((nome, valor) -> {
            if (!"first_message".equals(nome)
                && !"message[content]".equals(nome)
                && !"jsonAttachments".equals(nome)
                && !"message.attachments".equals(nome)) {
                partes.add(param(nome, valor));
            }
        });
        if (primeiraMensagem) {
            partes.add(param("first_message", "true"));
        }
        partes.add(param("message[content]", mensagem));
        partes.add(param("jsonAttachments", "[]"));
        partes.add(param("message.attachments", ""));
        return String.join("&", partes);
    }

    private JsonNode extrairMessageIndexData(String html) throws Exception {
        Document document = Jsoup.parse(html, config.getUrlBase());
        Element inbox = document.selectFirst("inbox");
        if (inbox == null) {
            throw erroValidacao("Componente inbox Workana nao encontrado");
        }
        String initial = inbox.attr(":initial");
        if (initial == null || initial.isBlank()) {
            throw erroValidacao("Initial do inbox Workana nao encontrado");
        }
        JsonNode root = objectMapper.readTree(Parser.unescapeEntities(initial, false));
        JsonNode messageIndexData = root.path("messageIndexData");
        if (messageIndexData.isMissingNode() || messageIndexData.isNull() || !messageIndexData.isObject()) {
            throw erroValidacao("messageIndexData Workana ausente");
        }
        return messageIndexData;
    }

    private void atualizarContextoDePagina(Long conversaId, JsonNode messageIndexData) {
        WorkanaConversaContext anterior = contexto(conversaId);
        WorkanaConversaContext atualizado = WorkanaInboxMapper.contextoAtualizado(
            config,
            anterior,
            conversaId,
            messageIndexData
        );
        conversas.put(atualizado.conversaId(), atualizado);
    }

    private WorkanaConversaContext contexto(Long conversaId) {
        WorkanaConversaContext contexto = conversas.get(conversaId);
        if (contexto == null) {
            listarConversasInbox();
            contexto = conversas.get(conversaId);
        }
        if (contexto == null) {
            throw new MarketplaceAdapterException(
                PlataformaFreela.WORKANA,
                MarketplaceAdapterErrorType.FALHA_ENVIO,
                "Conversa Workana " + conversaId + " nao carregada; sincronize o inbox antes de listar mensagens"
            );
        }
        return contexto;
    }

    private MarketplaceAdapterException erroValidacao(String mensagem) {
        return new MarketplaceAdapterException(
            PlataformaFreela.WORKANA,
            MarketplaceAdapterErrorType.FALHA_ENVIO,
            mensagem
        );
    }

    void configurarRelogioDiscovery(LongSupplier relogio) {
        discoveryClient.configurarRelogio(relogio);
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
