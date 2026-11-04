package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.WorkanaConfig;
import com.freelasatende.model.enums.PlataformaFreela;
import org.jsoup.Jsoup;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class WorkanaClientSupport {

    private WorkanaClientSupport() {
    }

    static Map<String, String> carregarCookies(WorkanaConfig config, ObjectMapper objectMapper) {
        String arquivo = config.getCookiesArquivo();
        if (arquivo.isBlank() || !Files.exists(Path.of(arquivo))) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(Path.of(arquivo).toFile());
            Map<String, String> cookies = new HashMap<>();
            if (root.isObject()) {
                root.fields().forEachRemaining(entry -> cookies.put(entry.getKey(), entry.getValue().asText("")));
            } else if (root.isArray()) {
                for (JsonNode cookie : root) {
                    String name = cookie.path("name").asText("");
                    String value = cookie.path("value").asText("");
                    if (!name.isBlank() && !value.isBlank()) {
                        cookies.put(name, value);
                    }
                }
            }
            cookies.entrySet().removeIf(entry -> entry.getKey().isBlank() || entry.getValue().isBlank());
            return cookies;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(
                PlataformaFreela.WORKANA,
                MarketplaceAdapterErrorType.FALHA_PARSE,
                "Falha ao carregar cookies Workana",
                e
            );
        }
    }

    static String construirCookieHeader(Map<String, String> cookies) {
        List<String> partes = new ArrayList<>();
        cookies.forEach((nome, valor) -> partes.add(nome + "=" + valor));
        return String.join("; ", partes);
    }

    static String param(String nome, String valor) {
        return URLEncoder.encode(nome, StandardCharsets.UTF_8)
            + "="
            + URLEncoder.encode(valor == null ? "" : valor, StandardCharsets.UTF_8);
    }

    static String encodePathSegment(String valor) {
        return URLEncoder.encode(valor, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String resumirResposta(String body) {
        if (body == null || body.isBlank()) {
            return "corpo vazio";
        }
        String texto = Jsoup.parse(body).text().replaceAll("\\s+", " ").trim();
        if (texto.length() <= 300) {
            return texto;
        }
        return texto.substring(0, 300) + "...";
    }

    static Long longOuNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        String texto = node.asText("");
        if (texto.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(texto);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String absolutizarUrl(WorkanaConfig config, String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        if (url.startsWith("/")) {
            return config.getUrlBase() + url;
        }
        return config.getUrlBase() + "/" + url;
    }
}
