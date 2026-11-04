package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.WorkanaConfig;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.model.enums.PlataformaFreela;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.freelasatende.model.enums.StatusProjeto;
import java.time.LocalDateTime;

import static com.freelasatende.service.impl.WorkanaClientSupport.*;

@Component
@RequiredArgsConstructor
class WorkanaProposalClient {

    private final WorkanaConfig config;
    private final ObjectMapper objectMapper;
    private HttpClient httpClient;

    void enviar(Projeto projeto, Proposta proposta) {
        if (projeto == null || proposta == null || proposta.getValorProposto() == null
            || proposta.getMensagem() == null || proposta.getMensagem().isBlank()) throw erro("Proposta Workana incompleta");
        String slug = slug(projeto.getUrlProjeto());
        Map<String, String> cookies = carregarCookies(config, objectMapper);
        if (cookies.isEmpty()) throw erro("Cookies Workana ausentes");
        String formUrl = config.getUrlBase() + "/messages/bid/" + encodePathSegment(slug) + "/?tab=bid";
        try {
            HttpResponse<String> pagina = client().send(HttpRequest.newBuilder().uri(URI.create(formUrl))
                .header("User-Agent", config.getUserAgent()).header("Accept", "text/html,application/xhtml+xml")
                .header("Cookie", construirCookieHeader(cookies)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (pagina.statusCode() >= 400) throw erro("Formulario Workana retornou HTTP " + pagina.statusCode());
            Formulario form = extrairFormulario(pagina.body(), cookies);
            Map<String, String> campos = new LinkedHashMap<>(form.campos());
            campos.put(form.campoValor(), proposta.getValorProposto().toPlainString());
            campos.put(form.campoPrazo(), campos.getOrDefault(form.campoPrazo(), "7").isBlank() ? "7" :
                campos.get(form.campoPrazo()));
            campos.put("message[content]", proposta.getMensagem());
            campos.put("first_message", "true");
            campos.putIfAbsent("jsonAttachments", "[]");
            String body = campos.entrySet().stream().map(e -> param(e.getKey(), e.getValue()))
                .reduce((a, b) -> a + "&" + b).orElse("");
            HttpRequest.Builder post = HttpRequest.newBuilder().uri(URI.create(form.action()))
                .header("User-Agent", config.getUserAgent()).header("Accept", "text/html,application/json")
                .header("Content-Type", "application/x-www-form-urlencoded").header("Origin", config.getUrlBase())
                .header("Referer", formUrl).header("Cookie", construirCookieHeader(cookies))
                .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body));
            if (!form.csrfHeader().isBlank() && !form.csrfToken().isBlank()) post.header(form.csrfHeader(), form.csrfToken());
            HttpResponse<String> response = client().send(post.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) throw erro("Proposta Workana retornou HTTP " + response.statusCode()
                + ": " + resumirResposta(response.body()));
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(PlataformaFreela.WORKANA,
                MarketplaceAdapterErrorType.FALHA_REDE, "Falha ao enviar proposta Workana", e);
        }
    }

    Projeto buscarDetalhes(Long idExterno, String slug) {
        if (slug == null || slug.isBlank()) {
            throw unsupported("Slug Workana ausente para buscar detalhes");
        }
        Map<String, String> cookies = carregarCookies(config, objectMapper);
        if (cookies.isEmpty()) {
            throw unsupported("Cookies Workana ausentes para buscar detalhes");
        }
        String url = config.getUrlBase() + "/job/" + encodePathSegment(slug);
        try {
            HttpResponse<String> response = client().send(HttpRequest.newBuilder().uri(URI.create(url))
                .header("User-Agent", config.getUserAgent()).header("Accept", "text/html")
                .header("Cookie", construirCookieHeader(cookies)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                MarketplaceAdapterErrorType tipo = response.statusCode() == 404 || response.statusCode() == 410
                    ? MarketplaceAdapterErrorType.CONVERSA_INACESSIVEL
                    : (response.statusCode() >= 500
                        ? MarketplaceAdapterErrorType.FALHA_REDE
                        : MarketplaceAdapterErrorType.FALHA_ENVIO);
                throw new MarketplaceAdapterException(
                    PlataformaFreela.WORKANA,
                    tipo,
                    "Detalhes Workana retornaram HTTP " + response.statusCode()
                );
            }
            Document doc = Jsoup.parse(response.body(), config.getUrlBase());
            Element titulo = doc.selectFirst("h1");
            Element descricao = doc.selectFirst(".project-details, .job-description, [itemprop=description]");
            if (titulo == null) throw erro("Titulo do projeto Workana nao encontrado");
            return Projeto.builder().id(PlataformaFreela.WORKANA.idInterno(idExterno)).idExterno(idExterno)
                .plataforma(PlataformaFreela.WORKANA).titulo(titulo.text())
                .descricao(descricao == null ? "" : descricao.text()).urlProjeto(url)
                .status(StatusProjeto.NOVO).dataCriacao(LocalDateTime.now()).foiProcessado(false).build();
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(PlataformaFreela.WORKANA,
                MarketplaceAdapterErrorType.FALHA_REDE, "Falha ao buscar detalhes Workana", e);
        }
    }

    Formulario extrairFormulario(String html, Map<String, String> cookies) {
        Document doc = Jsoup.parse(html, config.getUrlBase());
        Element escolhido = null;
        String campoValor = "";
        String campoPrazo = "";
        for (Element form : doc.select("form")) {
            String valor = nomeCampo(form, "amount", "price", "budget", "valor");
            String prazo = nomeCampo(form, "delivery", "period", "duration", "prazo");
            if (!valor.isBlank() && !prazo.isBlank()) {
                escolhido = form;
                campoValor = valor;
                campoPrazo = prazo;
                break;
            }
        }
        if (escolhido == null) throw erro("Campos atuais de valor/prazo da proposta Workana nao encontrados");
        Map<String, String> campos = new LinkedHashMap<>();
        for (Element input : escolhido.select("input[name], select[name], textarea[name]")) {
            if (!input.attr("name").isBlank()) campos.put(input.attr("name"), input.val());
        }
        String dcst = cookies.getOrDefault("dcstcookieii", "");
        if (!dcst.isBlank()) campos.put("dcst-input", dcst);
        String csrf = doc.select("meta[name=csrf-token]").attr("content");
        int barra = csrf.indexOf('/');
        if (barra > 0) {
            campos.put("csrf_name", csrf.substring(0, barra));
            campos.put("csrf_value", csrf.substring(barra + 1));
        }
        String normal = html.replace("\\/", "/");
        return new Formulario(escolhido.absUrl("action"), campos, campoValor, campoPrazo,
            jsonString(normal, "ajaxCSRFHeaderKey"), jsonString(normal, "ajaxCSRFToken"));
    }

    private String nomeCampo(Element form, String... partes) {
        for (Element field : form.select("input[name], select[name]")) {
            String nome = field.attr("name").toLowerCase();
            for (String parte : partes) if (nome.contains(parte)) return field.attr("name");
        }
        return "";
    }

    private String jsonString(String texto, String chave) {
        var matcher = java.util.regex.Pattern.compile("\\\"" + chave + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(texto);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String slug(String url) {
        if (url == null || url.isBlank()) throw erro("URL Workana ausente");
        String path = URI.create(url).getPath();
        String slug = path.substring(path.lastIndexOf('/') + 1);
        if (slug.isBlank()) throw erro("Slug Workana ausente");
        return slug;
    }

    private MarketplaceAdapterException erro(String mensagem) {
        return new MarketplaceAdapterException(PlataformaFreela.WORKANA, MarketplaceAdapterErrorType.FALHA_ENVIO, mensagem);
    }

    private MarketplaceAdapterException unsupported(String mensagem) {
        return new MarketplaceAdapterException(
            PlataformaFreela.WORKANA,
            MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL,
            mensagem
        );
    }

    private synchronized HttpClient client() {
        if (httpClient == null) httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
        return httpClient;
    }

    record Formulario(String action, Map<String, String> campos, String campoValor, String campoPrazo,
                      String csrfHeader, String csrfToken) { }
}
