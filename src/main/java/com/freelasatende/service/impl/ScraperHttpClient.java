package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.FreelasConfig;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import com.freelasatende.util.FreelasApiUtils;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

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
            ChromeResponse chrome = requestViaChrome(
                "GET",
                url,
                null,
                referer,
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                null
            );
            if (chrome.statusCode() >= 400 || chrome.statusCode() <= 0) {
                throw new IllegalStateException("GET " + URI.create(url).getPath()
                    + " retornou HTTP " + chrome.statusCode());
            }
            return chrome.body();
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
        if (response.statusCode() >= 400) {
            ChromeResponse chrome = requestViaChrome(
                "POST",
                url,
                body,
                referer,
                "text/plain,*/*;q=0.01",
                "application/json"
            );
            return new SimpleStringHttpResponse(chrome.statusCode(), chrome.body(), URI.create(url));
        }
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
        if (response.statusCode() >= 400) {
            ChromeResponse chrome = requestViaChrome(
                "POST",
                url,
                body,
                referer,
                "text/plain,*/*;q=0.01",
                "application/x-www-form-urlencoded; charset=UTF-8"
            );
            return new SimpleStringHttpResponse(chrome.statusCode(), chrome.body(), URI.create(url));
        }
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
        if (response.statusCode() >= 400) {
            ChromeResponse chrome = requestViaChrome(
                "POST",
                url,
                "",
                referer,
                "text/plain,*/*;q=0.01",
                ""
            );
            return new SimpleStringHttpResponse(chrome.statusCode(), chrome.body(), URI.create(url));
        }
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
            ChromeResponse chrome = requestViaChrome(
                "GET",
                url,
                null,
                referer,
                "text/plain,*/*;q=0.01",
                null
            );
            if (chrome.statusCode() >= 400 || chrome.statusCode() <= 0) {
                throw new IllegalStateException("GET " + path + " retornou HTTP " + chrome.statusCode());
            }
            body = chrome.body();
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

    private ChromeResponse requestViaChrome(
        String method,
        String url,
        String body,
        String referer,
        String accept,
        String contentType
    ) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("python3");
        command.add("scripts/freelas_http.py");
        command.add("--method");
        command.add(method);
        command.add("--url");
        command.add(url);
        command.add("--cookie-file");
        command.add(arquivoCookies);
        command.add("--accept");
        command.add(accept != null ? accept : "*/*");
        if (referer != null && !referer.isBlank()) {
            command.add("--referer");
            command.add(referer);
        }
        if (contentType != null && !contentType.isBlank()) {
            command.add("--content-type");
            command.add(contentType);
        }

        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();

        try (OutputStream stdin = process.getOutputStream()) {
            if (body != null && !body.isEmpty()) {
                stdin.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        process.getInputStream().transferTo(stdout);
        boolean finished = process.waitFor(45, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Timeout no cliente Chrome HTTP");
        }

        String output = stdout.toString(StandardCharsets.UTF_8);
        JsonNode root = objectMapper.readTree(output);
        if (root.hasNonNull("error")) {
            throw new IllegalStateException(root.path("error").asText());
        }

        String bodyBase64 = root.path("body_base64").asText("");
        String responseBody = new String(Base64.getDecoder().decode(bodyBase64), StandardCharsets.UTF_8);
        return new ChromeResponse(root.path("status").asInt(), responseBody);
    }

    private record ChromeResponse(int statusCode, String body) {
    }

    private static final class SimpleStringHttpResponse implements HttpResponse<String> {
        private final int statusCode;
        private final String body;
        private final URI uri;

        private SimpleStringHttpResponse(int statusCode, String body, URI uri) {
            this.statusCode = statusCode;
            this.body = body;
            this.uri = uri;
        }

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return uri;
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_2;
        }
    }
}
