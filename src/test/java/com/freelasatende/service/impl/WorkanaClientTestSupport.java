package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.WorkanaConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

final class WorkanaClientTestSupport {

    private WorkanaClientTestSupport() {
    }

    static WorkanaConfig configFor(HttpServer server, Path cookies) {
        WorkanaConfig config = new WorkanaConfig();
        config.setUrlBase("http://127.0.0.1:" + server.getAddress().getPort());
        config.setCookiesArquivo(cookies.toString());
        return config;
    }

    static WorkanaClient client(WorkanaConfig config) {
        return new WorkanaClient(config, new ObjectMapper());
    }

    static void responder(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    static void responderJson(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        responder(exchange, status, body);
    }

    static Map<String, String> parseForm(String body) {
        return Arrays.stream(body.split("&"))
            .map(item -> item.split("=", 2))
            .collect(Collectors.toMap(
                item -> decode(item[0]),
                item -> item.length > 1 ? decode(item[1]) : ""
            ));
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
