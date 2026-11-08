package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.IaConfig;
import com.freelasatende.service.interfaceServico.IaProvider.IaProviderException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Contrato do cooldown por modelo na cadeia de fallback: depois de 2 falhas
 * retryable consecutivas, o modelo e pulado por 5 minutos (producao:
 * openrouter/free -> 422 repetido e glm-5.3-flash -> 429 faziam TODA
 * avaliacao pagar a latencia das chamadas que ja se sabia falhar). Sucesso
 * limpa o estado; com TODOS os modelos em cooldown, a cadeia e tentada em
 * ordem normal -- nunca deixar de consultar a cadeia por causa do cooldown.
 * Mesmo padrao dos testes de integracao do provider (HttpServer de verdade,
 * sem mock do HttpClient).
 */
class OpenAiCompatibleProviderCooldownTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private IaConfig config(String baseUrl, String model, String fallbackModels) {
        IaConfig config = new IaConfig();
        config.setProvider("openai");
        config.setApiKey("sk-test-key");
        config.setBaseUrl(baseUrl);
        config.setModel(model);
        config.setFallbackModels(fallbackModels);
        config.setTimeout(10);
        return config;
    }

    /** Servidor que responde por modelo: falhaQuebrado define o modelo principal. */
    private HttpServer servidorDeDoisModelos(AtomicInteger chamadasQuebrado,
                                             AtomicInteger chamadasOk,
                                             boolean falhaQuebrado,
                                             String modeloQuebrado,
                                             String modeloOk) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String corpo = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] resposta;
            if (corpo.contains("\"model\":\"" + modeloQuebrado + "\"")) {
                chamadasQuebrado.incrementAndGet();
                if (falhaQuebrado) {
                    resposta = "{\"error\":{\"message\":\"rate limited\"}}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(429, resposta.length);
                } else {
                    resposta = "{\"choices\":[{\"message\":{\"content\":\"RESPOSTA_QUEBRADO\"}}]}"
                        .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, resposta.length);
                }
            } else {
                chamadasOk.incrementAndGet();
                resposta = "{\"choices\":[{\"message\":{\"content\":\"RESPOSTA_OK\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resposta.length);
            }
            exchange.getResponseBody().write(resposta);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    @DisplayName("Duas falhas retryable consecutivas colocam o modelo em cooldown e ele e pulado")
    void devePularModeloAposDuasFalhasConsecutivas() throws Exception {
        AtomicInteger chamadasQuebrado = new AtomicInteger();
        AtomicInteger chamadasOk = new AtomicInteger();
        HttpServer server = servidorDeDoisModelos(chamadasQuebrado, chamadasOk, true,
            "modelo-quebrado", "modelo-ok");
        try {
            OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                config("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "modelo-quebrado", "modelo-ok"),
                objectMapper);

            // Duas chamadas: o modelo quebrado falha (429) e o fallback assume.
            assertEquals("RESPOSTA_OK", provider.enviar("teste", 10));
            assertEquals("RESPOSTA_OK", provider.enviar("teste", 10));
            assertEquals(2, chamadasQuebrado.get());

            // Na terceira chamada o modelo quebrado esta em cooldown: nao e
            // tentado de novo, o fallback responde direto.
            assertEquals("RESPOSTA_OK", provider.enviar("teste", 10));
            assertEquals(2, chamadasQuebrado.get(),
                "modelo com 2 falhas consecutivas deve ser pulado, nao chamado de novo");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Sucesso do modelo limpa contador e cooldown")
    void sucessoDeveLimparEstadoDoModelo() throws Exception {
        AtomicInteger chamadasInstavel = new AtomicInteger();
        AtomicInteger chamadasOk = new AtomicInteger();
        // Instavel: 1a chamada falha, 2a tem sucesso, 3a falha, 4a em diante
        // tem sucesso. Com FALHAS_PARA_COOLDOWN = 2, cada par falha/sucesso
        // so chega a 1 falha consecutiva -- nada de cooldown.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String corpo = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] resposta;
            if (corpo.contains("\"model\":\"modelo-instavel\"")) {
                int chamada = chamadasInstavel.incrementAndGet();
                boolean falha = chamada == 1 || chamada == 3;
                if (falha) {
                    resposta = "{\"error\":{\"message\":\"rate limited\"}}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(429, resposta.length);
                } else {
                    resposta = "{\"choices\":[{\"message\":{\"content\":\"RESPOSTA_INSTAVEL\"}}]}"
                        .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, resposta.length);
                }
            } else {
                chamadasOk.incrementAndGet();
                resposta = "{\"choices\":[{\"message\":{\"content\":\"RESPOSTA_OK\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resposta.length);
            }
            exchange.getResponseBody().write(resposta);
            exchange.close();
        });
        server.start();
        try {
            OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                config("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "modelo-instavel", "modelo-ok"),
                objectMapper);

            // falha (contador=1) -> sucesso (limpa) -> falha (contador=1 de novo).
            // Na cadeia, a falha retryable do instavel cai para o modelo-ok:
            // respostas OK, INSTAVEL, OK, INSTAVEL.
            assertEquals("RESPOSTA_OK", provider.enviar("teste", 10));
            assertEquals("RESPOSTA_INSTAVEL", provider.enviar("teste", 10));
            assertEquals("RESPOSTA_OK", provider.enviar("teste", 10));

            // Se o sucesso NAO limpasse o estado, a 2a falha chegaria ao
            // contador 2 e o modelo estaria em cooldown na 4a chamada
            // (responderia RESPOSTA_OK via fallback).
            assertEquals("RESPOSTA_INSTAVEL", provider.enviar("teste", 10));
            assertEquals(4, chamadasInstavel.get(),
                "sucesso deve limpar o estado; modelo volta a ser tentado sem cooldown");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Com todos os modelos em cooldown, a cadeia e tentada em ordem normal")
    void todosEmCooldownDeveIgnorarCooldownETentarCadeia() throws Exception {
        AtomicInteger chamadasA = new AtomicInteger();
        AtomicInteger chamadasB = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String corpo = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            AtomicInteger contador = corpo.contains("\"model\":\"modelo-a\"") ? chamadasA : chamadasB;
            contador.incrementAndGet();
            byte[] resposta = "{\"error\":{\"message\":\"rate limited\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, resposta.length);
            exchange.getResponseBody().write(resposta);
            exchange.close();
        });
        server.start();
        try {
            OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                config("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "modelo-a", "modelo-b"),
                objectMapper);

            // Duas chamadas com falha total: ambos os modelos atingem o
            // contador de cooldown.
            assertThrows(IaProviderException.class, () -> provider.enviar("teste", 10));
            assertThrows(IaProviderException.class, () -> provider.enviar("teste", 10));
            assertEquals(2, chamadasA.get());
            assertEquals(2, chamadasB.get());

            // Terceira chamada: TODOS em cooldown, cooldown e ignorado e a
            // cadeia e tentada em ordem (a antes de b) em vez de abortar.
            assertThrows(IaProviderException.class, () -> provider.enviar("teste", 10));
            assertEquals(3, chamadasA.get(), "com todos em cooldown, a cadeia deve ser tentada em ordem");
            assertEquals(3, chamadasB.get(), "com todos em cooldown, a cadeia deve ser tentada em ordem");
        } finally {
            server.stop(0);
        }
    }
}
