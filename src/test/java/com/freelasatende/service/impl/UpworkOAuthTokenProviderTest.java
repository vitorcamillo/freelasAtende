package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.UpworkConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixa o fluxo OAuth 2.0 da Upwork, que substitui a raspagem de sessao do
 * navegador recusada com HTTP 401 em 15/09/2026.
 */
class UpworkOAuthTokenProviderTest {

    private UpworkConfig config(String base, String id, String secret, String refresh) {
        UpworkConfig config = new UpworkConfig();
        config.setUrlBase(base);
        config.setOauthClientId(id);
        config.setOauthClientSecret(secret);
        config.setOauthRefreshToken(refresh);
        return config;
    }

    @Test
    @DisplayName("troca refresh token por access token e reusa enquanto valido")
    void renovaEReusa() throws Exception {
        AtomicInteger chamadas = new AtomicInteger();
        AtomicReference<String> auth = new AtomicReference<>("");
        AtomicReference<String> corpo = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/oauth2/token", exchange -> {
            chamadas.incrementAndGet();
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            corpo.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resposta = "{\"access_token\":\"token-oauth\",\"expires_in\":86400}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resposta.length);
            exchange.getResponseBody().write(resposta);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var provider = new UpworkOAuthTokenProvider(
                config(base, "id-1", "segredo-1", "refresh-1"), new ObjectMapper(), HttpClient.newHttpClient());

            assertTrue(provider.configurado());
            assertEquals("token-oauth", provider.token());
            assertEquals("token-oauth", provider.token());
            assertEquals(1, chamadas.get(), "token valido nao pode ser renovado a cada chamada");
            assertTrue(corpo.get().contains("grant_type=refresh_token"));
            assertTrue(corpo.get().contains("refresh_token=refresh-1"));
            // A Upwork exige as credenciais no corpo: so no header Basic ela
            // responde 400 "Missing parameters: client_id" (medido em 15/09).
            assertTrue(corpo.get().contains("client_id=id-1"), "client_id precisa ir no corpo");
            assertTrue(corpo.get().contains("client_secret=segredo-1"), "client_secret precisa ir no corpo");
            String esperado = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("id-1:segredo-1".getBytes(StandardCharsets.UTF_8));
            assertEquals(esperado, auth.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("credencial recusada explica qual variavel conferir")
    void credencialRecusada() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/oauth2/token", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var provider = new UpworkOAuthTokenProvider(
                config(base, "id-1", "segredo-1", "refresh-ruim"), new ObjectMapper(), HttpClient.newHttpClient());

            MarketplaceAdapterException erro = assertThrows(MarketplaceAdapterException.class, provider::token);

            assertTrue(erro.getMessage().contains("HTTP 401"));
            assertTrue(erro.getMessage().contains("UPWORK_OAUTH_REFRESH_TOKEN"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("sem as tres variaveis, OAuth fica desligado e nao interfere")
    void desligadoSemConfiguracao() {
        var semNada = new UpworkOAuthTokenProvider(
            config("http://127.0.0.1:1", "", "", ""), new ObjectMapper(), HttpClient.newHttpClient());
        assertFalse(semNada.configurado());
        assertTrue(semNada.token().isBlank());

        var incompleto = new UpworkOAuthTokenProvider(
            config("http://127.0.0.1:1", "id-1", "segredo-1", ""), new ObjectMapper(), HttpClient.newHttpClient());
        assertFalse(incompleto.configurado(), "configuracao pela metade nao pode valer");
    }
}

