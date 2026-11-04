package com.freelasatende.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.IaConfig;
import com.freelasatende.service.interfaceServico.IaProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste isolado de OpenAiCompatibleProvider: content null do modelo de
 * raciocinio nao pode virar a string "null" no cliente e deve passar para o
 * proximo modelo da cadeia de fallback.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenAiCompatibleProviderContentNullTest {

    private IaConfig config;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        config = new IaConfig();
        config.setProvider("openai");
        config.setModel("gpt-4o");
        config.setBaseUrl("https://api.openai.com/v1");
        config.setApiKey("sk-test-key-123");
        config.setTimeout(10);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Deve ignorar content null e passar para o proximo modelo da cadeia")
    void deveIgnorarContentNullEPassarParaProximoModelo() throws Exception {
        AtomicInteger chamadasPrincipal = new AtomicInteger();
        AtomicInteger chamadasFallback = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String corpo = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean principal = corpo.contains("\"model\":\"gpt-4o\"");
            if (principal) {
                chamadasPrincipal.incrementAndGet();
                // Modelo de raciocinio gastou os tokens no reasoning e devolve
                // content null; nao pode virar a string "null" no cliente.
                byte[] resposta = "{\"choices\":[{\"message\":{\"content\":null,\"reasoning\":\"pensando...\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resposta.length);
                exchange.getResponseBody().write(resposta);
            } else {
                chamadasFallback.incrementAndGet();
                byte[] resposta = "{\"choices\":[{\"message\":{\"content\":\"RESPOSTA_ALTERNATIVA_NULL\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resposta.length);
                exchange.getResponseBody().write(resposta);
            }
            exchange.close();
        });
        server.start();
        try {
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            config.setFallbackModels("modelo-alternativo");
            IaProvider provider = new com.freelasatende.service.impl.OpenAiCompatibleProvider(config, objectMapper);

            String resposta = provider.enviar("teste", 10);

            assertEquals("RESPOSTA_ALTERNATIVA_NULL", resposta);
            assertTrue(chamadasPrincipal.get() >= 1);
            assertEquals(1, chamadasFallback.get());
            assertNotEquals("null", resposta);
        } finally {
            server.stop(0);
        }
    }
}
