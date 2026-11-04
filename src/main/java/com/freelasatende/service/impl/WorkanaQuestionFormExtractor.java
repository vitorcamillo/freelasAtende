package com.freelasatende.service.impl;

import com.freelasatende.config.WorkanaConfig;
import com.freelasatende.model.enums.PlataformaFreela;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.freelasatende.service.impl.WorkanaClientSupport.absolutizarUrl;
import static com.freelasatende.service.impl.WorkanaErrorClassifier.indicaAcessoNegado;
import static com.freelasatende.service.impl.WorkanaErrorClassifier.tipoAcessoNegado;
import static com.freelasatende.service.impl.WorkanaClientSupport.encodePathSegment;

final class WorkanaQuestionFormExtractor {

    private final WorkanaConfig config;

    WorkanaQuestionFormExtractor(WorkanaConfig config) {
        this.config = config;
    }

    WorkanaFormPergunta extrair(String html, String slug, Map<String, String> cookies) {
        Document document = Jsoup.parse(html, config.getUrlBase());
        Element form = document.selectFirst("form[action*=/messages/index/]");
        if (form == null) {
            form = document.selectFirst("form:has(textarea[name=message\\[content\\]]), form:has(input[name=message\\[content\\]])");
        }
        if (form == null) {
            form = document.selectFirst("form[action*=/messages/bid/]");
        }
        if (form == null) {
            String postUrl = extrairPostUrlVue(html);
            if (!postUrl.isBlank()) {
                WorkanaCsrfInfo csrf = extrairCsrf(document, html);
                Map<String, String> campos = new HashMap<>(csrf.campos());
                incluirDcstInput(campos, cookies);
                return new WorkanaFormPergunta(absolutizarUrl(config, postUrl), campos, csrf.headerName(), csrf.headerValue());
            }
            // Sem formulario e sem postUrl a pagina nao tem caminho de envio real: o
            // fallback de URL montada so produz "HTTP 400: corpo vazio" no POST.
            if (indicaAcessoNegado(html)) {
                throw tipoAcessoNegado(html).equals(MarketplaceAdapterErrorType.LIMITE_CONEXOES)
                    ? erroValidacao("Workana negou contato inicial: limite de conexoes da conta")
                    : erroValidacao("Workana negou acesso a pagina de contato inicial");
            }
            String workerSlug = config.getWorkerSlug();
            if (!workerSlug.isBlank()) {
                WorkanaCsrfInfo csrf = extrairCsrf(document, html);
                Map<String, String> campos = new HashMap<>(csrf.campos());
                incluirDcstInput(campos, cookies);
                return new WorkanaFormPergunta(
                    config.getUrlBase() + "/messages/index/" + encodePathSegment(slug) + "/" + encodePathSegment(workerSlug),
                    campos,
                    csrf.headerName(),
                    csrf.headerValue()
                );
            }
            throw erroValidacao("Form/postUrl de pergunta Workana nao encontrado; configure WORKANA_WORKER_SLUG se necessario");
        }

        String action = form.absUrl("action");
        if (action == null || action.isBlank()) {
            if (config.getWorkerSlug().isBlank()) {
                throw erroValidacao("Action do form Workana ausente; configure WORKANA_WORKER_SLUG");
            }
            action = config.getUrlBase() + "/messages/index/" + encodePathSegment(slug) + "/"
                + encodePathSegment(config.getWorkerSlug());
        }
        Map<String, String> campos = new HashMap<>();
        for (Element input : form.select("input[name]")) {
            String name = input.attr("name");
            String value = input.attr("value");
            if (!name.isBlank() && !"message[content]".equals(name)) {
                campos.put(name, value == null ? "" : value);
            }
        }
        WorkanaCsrfInfo csrf = extrairCsrf(document, html);
        campos.putAll(csrf.campos());
        incluirDcstInput(campos, cookies);
        return new WorkanaFormPergunta(action, campos, csrf.headerName(), csrf.headerValue());
    }

    private void incluirDcstInput(Map<String, String> campos, Map<String, String> cookies) {
        String dcst = cookies.getOrDefault("dcstcookieii", "");
        if (!dcst.isBlank()) {
            campos.put("dcst-input", dcst);
        }
    }

    private WorkanaCsrfInfo extrairCsrf(Document document, String html) {
        Map<String, String> campos = new HashMap<>();
        Element meta = document.selectFirst("meta[name=csrf-token]");
        String token = meta == null ? "" : meta.attr("content");
        int separator = token.indexOf('/');
        if (separator > 0 && separator < token.length() - 1) {
            campos.put("csrf_name", token.substring(0, separator));
            campos.put("csrf_value", token.substring(separator + 1));
        }

        String normalizado = Parser.unescapeEntities(html, false).replace("\\/", "/");
        String headerName = extrairStringJson(normalizado, "ajaxCSRFHeaderKey");
        String headerValue = extrairStringJson(normalizado, "ajaxCSRFToken");
        return new WorkanaCsrfInfo(campos, headerName, headerValue);
    }

    private String extrairPostUrlVue(String html) {
        String normalizado = Parser.unescapeEntities(html, false).replace("\\/", "/");
        Matcher matcher = Pattern.compile("\"postUrl\"\\s*:\\s*\"([^\"]+)\"").matcher(normalizado);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String extrairStringJson(String texto, String chave) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(chave) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(texto);
        return matcher.find() ? matcher.group(1) : "";
    }

    private MarketplaceAdapterException erroValidacao(String mensagem) {
        return new MarketplaceAdapterException(
            PlataformaFreela.WORKANA,
            MarketplaceAdapterErrorType.FALHA_ENVIO,
            mensagem
        );
    }

    record WorkanaFormPergunta(
        String actionUrl,
        Map<String, String> campos,
        String csrfHeaderName,
        String csrfHeaderValue
    ) {
    }

    private record WorkanaCsrfInfo(Map<String, String> campos, String headerName, String headerValue) {
    }
}
