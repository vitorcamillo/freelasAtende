package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class GmailOtpReader {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile HttpClient httpClient;
    private volatile boolean tentouInicializar;
    private volatile String accessTokenCache;
    private volatile long accessTokenExpira;

    private static final Pattern CODIGO = Pattern.compile("(?<!\\d)(\\d{4,8})(?!\\d)");

    public boolean disponivel() { return obterToken() != null; }

    public String buscarCodigo(String remetenteQuery, int maxIdadeMinutos) {
        String token = obterToken();
        if (token == null) return null;
        try {
            String query = URLEncoder.encode(remetenteQuery + " newer_than:1d", StandardCharsets.UTF_8);
            JsonNode root = getJson("https://gmail.googleapis.com/gmail/v1/users/me/threads?q=" + query + "&maxResults=5", token);
            JsonNode threads = root.path("threads");
            if (!threads.isArray() || threads.isEmpty()) return null;
            for (JsonNode resumo : threads) {
                String tid = resumo.path("id").asText("");
                if (tid.isBlank()) continue;
                JsonNode thread = getJson("https://gmail.googleapis.com/gmail/v1/users/me/threads/" + tid + "?format=metadata", token);
                JsonNode msgs = thread.path("messages");
                for (int i = msgs.size() - 1; i >= 0; i--) {
                    JsonNode msg = msgs.get(i);
                    String codigo = extrairCodigoDeTextos(cabecalho(msg, "Subject"), msg.path("snippet").asText(""));
                    if (codigo != null) { log.info("[GmailOTP] Codigo para query '{}'.", remetenteQuery); return codigo; }
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("[GmailOTP] Falha ({}): {}", remetenteQuery, e.getMessage());
            return null;
        }
    }

    static String extrairCodigoDeTextos(String... fontes) {
        for (String fonte : fontes) {
            if (fonte == null || fonte.isBlank()) continue;
            Matcher m = CODIGO.matcher(fonte);
            while (m.find()) { String c = m.group(1); if (c.length() == 6) return c; }
            Matcher m2 = CODIGO.matcher(fonte);
            while (m2.find()) { String c = m2.group(1); if (!pareceAno(c)) return c; }
        }
        return null;
    }

    private static boolean pareceAno(String s) {
        if (s.length() != 4) return false;
        try { int v = Integer.parseInt(s); return v >= 1990 && v <= 2100; } catch (Exception e) { return false; }
    }

    private String cabecalho(JsonNode msg, String nome) {
        JsonNode headers = msg.path("payload").path("headers");
        if (headers.isArray()) {
            for (JsonNode h : headers)
                if (h.path("name").asText("").equalsIgnoreCase(nome))
                    return h.path("value").asText("");
        }
        return "";
    }

    private String obterToken() {
        long agora = System.currentTimeMillis();
        if (accessTokenCache != null && agora < accessTokenExpira) return accessTokenCache;
        if (tentouInicializar && accessTokenCache == null) return null;
        synchronized (this) {
            if (accessTokenCache != null && agora < accessTokenExpira) return accessTokenCache;
            tentouInicializar = true;
            try {
                String at = valor("GMAIL_ACCESS_TOKEN");
                if (at != null && !at.isBlank()) {
                    accessTokenCache = at; accessTokenExpira = agora + 3500_000L;
                    log.info("[GmailOTP] Inicializado (read-only)."); return at;
                }
                String rt = valor("GMAIL_REFRESH_TOKEN"), ci = valor("GMAIL_OAUTH_CLIENT_ID"), cs = valor("GMAIL_OAUTH_CLIENT_SECRET");
                if (rt != null && !rt.isBlank() && ci != null && !ci.isBlank() && cs != null && !cs.isBlank()) {
                    String b = "client_id="+urlEncode(ci)+"&client_secret="+urlEncode(cs)+"&refresh_token="+urlEncode(rt)+"&grant_type=refresh_token";
                    HttpRequest req = HttpRequest.newBuilder().uri(URI.create("https://oauth2.googleapis.com/token")).header("Content-Type", "application/x-www-form-urlencoded").timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.ofString(b)).build();
                    JsonNode root = objectMapper.readTree(client().send(req, HttpResponse.BodyHandlers.ofString()).body());
                    String novo = root.path("access_token").asText("");
                    int exp = root.path("expires_in").asInt(3600);
                    if (novo.isBlank()) throw new RuntimeException("Sem token");
                    accessTokenCache = novo; accessTokenExpira = agora + (exp - 60) * 1000L;
                    log.info("[GmailOTP] Inicializado (read-only)."); return novo;
                }
                log.info("[GmailOTP] Gmail nao configurado; OTP inativo.");
                return null;
            } catch (Exception e) {
                log.info("[GmailOTP] Gmail nao configurado ({}).", e.getMessage());
                return null;
            }
        }
    }

    private JsonNode getJson(String url, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(15)).GET().build();
        return objectMapper.readTree(client().send(req, HttpResponse.BodyHandlers.ofString()).body());
    }

    private String urlEncode(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private String valor(String ch) { String v = System.getenv(ch); return (v == null || v.isBlank()) ? System.getProperty(ch) : v; }
    private synchronized HttpClient client() { if (httpClient == null) httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(); return httpClient; }
}
