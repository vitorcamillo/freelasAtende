package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.FreelasConfig;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import com.freelasatende.util.FreelasApiUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

class ScraperHttpClient {

    private final AutenticacaoService autenticacaoService;
    private final FreelasConfig freelasConfig;
    private final ObjectMapper objectMapper;
    private final String arquivoCookies;
    private HttpClient httpClient;

    ScraperHttpClient(
        AutenticacaoService autenticacaoService,
        FreelasConfig freelasConfig,
        ObjectMapper objectMapper,
        String arquivoCookies
    ) {
        this.autenticacaoService = autenticacaoService;
        this.freelasConfig = freelasConfig;
        this.objectMapper = objectMapper;
        this.arquivoCookies = arquivoCookies;
    }

    String get(String url, String referer) throws Exception {
        Map<String, String> cookies = autenticacaoService.obterCookies();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", freelasConfig.getUserAgent())
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            .header("Referer", referer)
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-User", "?1")
            .header("sec-ch-ua", "\"Microsoft Edge\";v=\"147\", \"Not.A/Brand\";v=\"8\", \"Chromium\";v=\"147\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"Linux\"")
            .header("Cookie", construirCookieHeader(cookies))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();

        HttpResponse<String> response = getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            // Sem fallback que imite navegador: HTTP >= 400 (inclusive desafio do Cloudflare) vira erro e a operação para.
            throw new IllegalStateException("GET " + URI.create(url).getPath()
                + " retornou HTTP " + response.statusCode());
        }

        return response.body();
    }

    HttpResponse<String> postJson(String url, String body, String referer) throws Exception {
        Map<String, String> cookies = autenticacaoService.obterCookies();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", freelasConfig.getUserAgent())
            .header("Accept", "text/plain,*/*;q=0.01")
            .header("Accept-Language", "pt-BR")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Content-Type", "application/json")
            .header("Referer", referer)
            .header("Cookie", construirCookieHeader(cookies))
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return response;
    }

    HttpResponse<String> postForm(String url, String body, String referer) throws Exception {
        Map<String, String> cookies = autenticacaoService.obterCookies();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", freelasConfig.getUserAgent())
            .header("Accept", "text/plain,*/*;q=0.01")
            .header("Accept-Language", "pt-BR")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("Referer", referer)
            .header("Cookie", construirCookieHeader(cookies))
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return response;
    }

    HttpResponse<String> postSemBody(String url, String referer) throws Exception {
        Map<String, String> cookies = autenticacaoService.obterCookies();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", freelasConfig.getUserAgent())
            .header("Accept", "text/plain,*/*;q=0.01")
            .header("Accept-Language", "pt-BR")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", referer)
            .header("Cookie", construirCookieHeader(cookies))
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        HttpResponse<String> response = getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return response;
    }

    JsonNode getServico(String url, String path, String referer) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", freelasConfig.getUserAgent())
            .header("Accept", "text/plain,*/*;q=0.01")
            .header("Accept-Language", "pt-BR")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", referer)
            .header("Cookie", construirCookieHeader(autenticacaoService.obterCookies()))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();

        HttpResponse<String> response = getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body();
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("GET " + path + " retornou HTTP " + response.statusCode());
        }
        return FreelasApiUtils.decodeUrlEncodedJson(objectMapper, body);
    }

    private synchronized HttpClient getHttpClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        }
        return httpClient;
    }

    private String construirCookieHeader(Map<String, String> cookies) {
        StringBuilder sb = new StringBuilder();
        if (cookies != null) {
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(entry.getKey()).append("=").append(entry.getValue());
            }
        }
        return sb.toString();
    }

}
