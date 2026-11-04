package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.WorkanaConfig;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static com.freelasatende.service.impl.WorkanaClientSupport.construirCookieHeader;
import static com.freelasatende.service.impl.WorkanaClientSupport.encodePathSegment;

/** Consulta isolada da permissão usada pelo botão "Faça uma pergunta" da Workana. */
@Slf4j
final class WorkanaPermissionClient {

    private WorkanaPermissionClient() {
    }

    static WorkanaPermissaoContato consultar(
        HttpClient httpClient,
        WorkanaConfig config,
        ObjectMapper objectMapper,
        String slug,
        Map<String, String> cookies
    ) {
        String url = config.getUrlBase() + "/workers/permissions/make_bid_action?projectSlug="
            + encodePathSegment(slug);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", config.getUserAgent())
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", config.getUrlBase() + "/job/" + encodePathSegment(slug))
                .header("Cookie", construirCookieHeader(cookies))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return WorkanaPermissaoContato.analisar(response.statusCode(), response.body(), objectMapper);
        } catch (Exception e) {
            log.debug("Falha ao consultar permissao de contato Workana para {}: {}", slug, e.getMessage());
            return WorkanaPermissaoContato.permitida();
        }
    }
}
