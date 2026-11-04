package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.UpworkConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixa o fallback de token do mensageiro do Upwork.
 *
 * <p>Medido em producao em 15/09/2026: o endpoint de token subordinado passou a
 * responder HTTP 401 mesmo apos login bem-sucedido com 35 cookies salvos. Como a
 * verificacao de sessao depende da inbox, a plataforma inteira ficou marcada como
 * "nao autenticada" e nenhuma conversa era lida.</p>
 */
class UpworkTokenFallbackTest {

    @TempDir
    Path pasta;

    private UpworkConfig config(String base, Path cookies) {
        UpworkConfig config = new UpworkConfig();
        config.setUrlBase(base);
        config.setAuthUrlBase(base);
        config.setCookiesArquivo(cookies.toString());
        config.setMessagesClientId("cliente-teste");
        return config;
    }

    private Path cookies(String json) throws Exception {
        Path arquivo = pasta.resolve("upwork-cookies.json");
        Files.writeString(arquivo, json);
        return arquivo;
    }

    @Test
    @DisplayName("token do cookie assume quando o endpoint responde 401")
    void usaTokenDoCookieQuandoEndpointRecusa() throws Exception {
        AtomicReference<String> authRecebido = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/oauth2/token/subordinate/v3/cliente-teste", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.createContext("/api/v3/rooms/rooms", exchange -> {
            authRecebido.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] corpo = "{\"rooms\":[{\"roomId\":\"sala-1\",\"topic\":\"Projeto X\"}]}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, corpo.length);
            exchange.getResponseBody().write(corpo);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Path arquivo = cookies("{\"current_organization_uid\":\"org-1\","
                + "\"oauth2_global_js_token\":\"token-do-cookie\"}");
            UpworkClient client = new UpworkClient(config(base, arquivo), new ObjectMapper());

            List<?> conversas = client.listarConversasInbox();

            assertEquals(1, conversas.size());
            assertEquals("bearer token-do-cookie", authRecebido.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("sem token no cookie, a falha diz quais cookies existem")
    void semTokenNoCookieFalhaExplicita() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/oauth2/token/subordinate/v3/cliente-teste", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Path arquivo = cookies("{\"current_organization_uid\":\"org-1\",\"visitor_id\":\"v-1\"}");
            UpworkClient client = new UpworkClient(config(base, arquivo), new ObjectMapper());

            MarketplaceAdapterException erro = org.junit.jupiter.api.Assertions.assertThrows(
                MarketplaceAdapterException.class, client::listarConversasInbox);

            assertTrue(erro.getMessage().contains("oauth2_global_js_token"));
            assertTrue(erro.getMessage().contains("visitor_id"), "a falha precisa listar os cookies presentes");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("token do cookie so vale quando existe de verdade")
    void tokenDeCookie() {
        assertEquals("abc", UpworkClient.tokenDeCookie(Map.of("oauth2_global_js_token", "abc")));
        assertTrue(UpworkClient.tokenDeCookie(Map.of("outro", "x")).isBlank());
        assertTrue(UpworkClient.tokenDeCookie(null).isBlank());
        assertFalse(UpworkClient.tokenDeCookie(Map.of("oauth2_global_js_token", " abc ")).contains(" "));
    }
}

