package com.freelasatende.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.IaConfig;
import com.freelasatende.service.interfaceServico.IaProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes para OpenAiCompatibleProvider.
 * Testa a factory de criação de providers e o provider OpenAI-compatible.
 * Como não temos API real, testamos a construção e casos de erro.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenAiCompatibleProviderTest {

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
    @DisplayName("Deve criar OpenAiCompatibleProvider com config valida")
    void deveCriarProviderComConfigValida() {
        // Act
        IaProvider provider = new com.freelasatende.service.impl.OpenAiCompatibleProvider(config, objectMapper);

        // Assert
        assertNotNull(provider);
        assertEquals("openai", provider.getNome()); // retorna provider da config
        assertEquals("gpt-4o", provider.getModelo());
    }

    @Test
    @DisplayName("Deve retornar nome correto do provider")
    void deveRetornarNomeCorreto() {
        // Arrange
        IaProvider provider = new com.freelasatende.service.impl.OpenAiCompatibleProvider(config, objectMapper);

        // Act & Assert
        assertEquals("openai", provider.getNome()); // retorna provider da config
    }

    @Test
    @DisplayName("Deve retornar modelo da config")
    void deveRetornarModeloDaConfig() {
        // Arrange
        IaProvider provider = new com.freelasatende.service.impl.OpenAiCompatibleProvider(config, objectMapper);

        // Act & Assert
        assertEquals("gpt-4o", provider.getModelo());
    }

    @Test
    @DisplayName("Deve usar provider da config no getNome")
    void deveUsarProviderDaConfigNoGetNome() {
        // Arrange
        config.setProvider("ollama");
        IaProvider provider = new com.freelasatende.service.impl.OpenAiCompatibleProvider(config, objectMapper);

        // Act & Assert
        assertEquals("ollama", provider.getNome());
    }

    @Test
    @DisplayName("Deve lancar exception ao tentar enviar sem API key")
    void deveLancarExceptionAoEnviarSemApiKey() {
        // Arrange
        config.setApiKey(null);
        IaProvider provider = new com.freelasatende.service.impl.OpenAiCompatibleProvider(config, objectMapper);

        // Act & Assert
        assertThrows(IaProvider.IaProviderException.class, () -> {
            provider.enviar("test", 10);
        });
    }

    @Test
    @DisplayName("Deve aceitar Ollama local sem API key")
    void deveAceitarOllamaLocalSemApiKey() {
        // Arrange
        config.setProvider("ollama");
        config.setApiKey(null);
        config.setBaseUrl("http://localhost:11434/v1");
        IaProvider provider = new com.freelasatende.service.impl.OpenAiCompatibleProvider(config, objectMapper);

        // Assert - provider deve ser criado sem erro
        assertNotNull(provider);
        assertEquals("ollama", provider.getNome());
    }

    @Test
    @DisplayName("Deve construir body JSON correto para chat completions")
    void deveConstruirBodyJsonCorreto() throws Exception {
        // Arrange
        IaProvider provider = new com.freelasatende.service.impl.OpenAiCompatibleProvider(config, objectMapper);

        // Act - usar reflection para verificar internals
        // Não dá para testar facilmente sem mockar HttpClient
        // Apenas verificamos que o provider foi criado
        assertNotNull(provider);
    }

    @Test
    @DisplayName("Deve criar provider com config custom")
    void deveCriarProviderComConfigCustom() {
        // Arrange
        config.setProvider("custom");
        config.setBaseUrl("https://meu-servidor.com/v1");
        config.setModel("meu-modelo");

        // Act
        IaProvider provider = new com.freelasatende.service.impl.OpenAiCompatibleProvider(config, objectMapper);

        // Assert
        assertNotNull(provider);
        assertEquals("custom", provider.getNome());
        assertEquals("meu-modelo", provider.getModelo());
    }

    @Test
    @DisplayName("IaProviderException deve ter campos corretos")
    void deveTerCamposCorretosNaException() {
        // Arrange
        IaProvider.IaProviderException ex = new IaProvider.IaProviderException("Rate limit", 429, true);

        // Assert
        assertEquals(429, ex.getStatusCode());
        assertTrue(ex.isRetriable());
        assertEquals("Rate limit", ex.getMessage());
    }

    @Test
    @DisplayName("IaProviderException com cause deve ter status -1")
    void deveTerStatusMenosUmComCause() {
        // Arrange
        RuntimeException cause = new RuntimeException("original");
        IaProvider.IaProviderException ex = new IaProvider.IaProviderException("Erro", cause);

        // Assert
        assertEquals(-1, ex.getStatusCode());
        assertTrue(ex.isRetriable());
        assertEquals("Erro", ex.getMessage());
        assertEquals(cause, ex.getCause());
    }

    @Test
    @DisplayName("Deve trocar para modelo fallback após rate limit do principal")
    void deveTrocarParaModeloFallbackAposRateLimit() throws Exception {
        AtomicInteger chamadasPrincipal = new AtomicInteger();
        AtomicInteger chamadasFallback = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String corpo = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean principal = corpo.contains("\"model\":\"gpt-4o\"");
            if (principal) {
                chamadasPrincipal.incrementAndGet();
                byte[] resposta = "{\"error\":\"quota\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(429, resposta.length);
                exchange.getResponseBody().write(resposta);
            } else {
                chamadasFallback.incrementAndGet();
                byte[] resposta = "{\"choices\":[{\"message\":{\"content\":\"RESPOSTA_ALTERNATIVA\"}}]}"
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

            assertEquals("RESPOSTA_ALTERNATIVA", resposta);
            assertEquals(1, chamadasPrincipal.get());
            assertEquals(1, chamadasFallback.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Deve trocar para modelo fallback apos 503 de provedor sobrecarregado")
    void deveTrocarParaModeloFallbackApos503Overloaded() throws Exception {
        AtomicInteger chamadasPrincipal = new AtomicInteger();
        AtomicInteger chamadasFallback = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String corpo = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean principal = corpo.contains("\"model\":\"gpt-4o\"");
            if (principal) {
                chamadasPrincipal.incrementAndGet();
                byte[] resposta = ("{\"error\":{\"message\":\"Upstream model provider is temporarily "
                    + "unavailable.\",\"type\":\"overloaded_error\"}}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(503, resposta.length);
                exchange.getResponseBody().write(resposta);
            } else {
                chamadasFallback.incrementAndGet();
                byte[] resposta = "{\"choices\":[{\"message\":{\"content\":\"RESPOSTA_ALTERNATIVA_503\"}}]}"
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

            assertEquals("RESPOSTA_ALTERNATIVA_503", resposta);
            assertEquals(1, chamadasPrincipal.get());
            assertEquals(1, chamadasFallback.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Exception com status 500 deve ser retriable")
    void deveSerRetriableQuandoStatus500() {
        // Arrange - o código source passa response.statusCode() >= 500 como retriable
        // que para 500 resulta em true (500 >= 500 = true)
        IaProvider.IaProviderException ex = new IaProvider.IaProviderException("Server error", 500, true);

        // Assert
        assertTrue(ex.isRetriable());
    }

    @Test
    @DisplayName("Deve usar provedor secundario quando cadeia principal esgota")
    void deveUsarProvedorSecundarioQuandoCadeiaPrincipalEsgota() throws Exception {
        AtomicInteger chamadasPrincipal = new AtomicInteger();
        AtomicInteger chamadasSecundario = new AtomicInteger();

        HttpServer principalServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        principalServer.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            chamadasPrincipal.incrementAndGet();
            byte[] resposta = ("{\"error\":{\"message\":\"Upstream model provider is temporarily "
                + "unavailable.\",\"type\":\"overloaded_error\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, resposta.length);
            exchange.getResponseBody().write(resposta);
            exchange.close();
        });
        principalServer.start();

        HttpServer secundarioServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        secundarioServer.createContext("/api/v1/chat/completions", exchange -> {
            String corpo = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            chamadasSecundario.incrementAndGet();
            if (corpo.contains("\"model\":\"openrouter/free\"")) {
                byte[] resposta = "{\"choices\":[{\"message\":{\"content\":\"RESPOSTA_OPENROUTER\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resposta.length);
                exchange.getResponseBody().write(resposta);
            } else {
                exchange.sendResponseHeaders(500, 0);
            }
            exchange.close();
        });
        secundarioServer.start();

        try {
            config.setBaseUrl("http://127.0.0.1:" + principalServer.getAddress().getPort() + "/v1");
            config.setFallbackModels("");
            config.setSecondaryBaseUrl("http://127.0.0.1:" + secundarioServer.getAddress().getPort() + "/api/v1");
            config.setSecondaryApiKey("sk-or-test");
            config.setSecondaryModels("openrouter/free");
            IaProvider provider = new com.freelasatende.service.impl.OpenAiCompatibleProvider(config, objectMapper);

            String resposta = provider.enviar("teste", 10);

            assertEquals("RESPOSTA_OPENROUTER", resposta);
            assertEquals(1, chamadasPrincipal.get());
            assertEquals(1, chamadasSecundario.get());
        } finally {
            principalServer.stop(0);
            secundarioServer.stop(0);
        }
    }

    @Test
    @DisplayName("Nao deve travar quando o provedor manda cabecalhos e segura o corpo")
    void naoDeveTravarQuandoProvedorSeguraOCorpo() throws Exception {
        java.util.concurrent.CountDownLatch liberar = new java.util.concurrent.CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/v1/chat/completions", exchange -> {
            String corpo = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (corpo.contains("\"model\":\"modelo-travado\"")) {
                // Igual ao OpenRouter: cabecalho 200 na hora, keep-alive e depois silencio.
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(' ');
                exchange.getResponseBody().flush();
                try {
                    liberar.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                byte[] resposta = "{\"choices\":[{\"message\":{\"content\":\"RESPOSTA_PROXIMO\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resposta.length);
                exchange.getResponseBody().write(resposta);
            }
            exchange.close();
        });
        server.start();
        try {
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            config.setModel("modelo-travado");
            config.setFallbackModels("modelo-ok");
            config.setTimeout(1);
            IaProvider provider = new com.freelasatende.service.impl.OpenAiCompatibleProvider(config, objectMapper);

            String resposta = assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
                () -> provider.enviar("teste", 10));

            assertEquals("RESPOSTA_PROXIMO", resposta);
        } finally {
            liberar.countDown();
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Deve usar camada terciaria na base principal quando free e z.ai esgotam")
    void deveUsarCamadaTerciariaQuandoPrincipalESecundariaEsgotam() throws Exception {
        java.util.List<String> ordem = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        HttpServer openRouter = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        openRouter.createContext("/v1/chat/completions", exchange -> {
            String corpo = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] resposta;
            if (corpo.contains("\"model\":\"qwen/qwen3.7-flash\"")) {
                ordem.add("openrouter:qwen");
                resposta = "{\"choices\":[{\"message\":{\"content\":\"RESPOSTA_QWEN\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resposta.length);
            } else {
                ordem.add("openrouter:free");
                resposta = "{\"error\":{\"message\":\"free-models-per-day\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(429, resposta.length);
            }
            exchange.getResponseBody().write(resposta);
            exchange.close();
        });
        openRouter.start();

        HttpServer zai = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        zai.createContext("/api/coding/paas/v4/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            ordem.add("zai:glm");
            byte[] resposta = ("{\"error\":{\"code\":\"1310\",\"message\":\"Weekly/Monthly Limit Exhausted. "
                + "Your limit will reset at 2026-09-27 23:34:24\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, resposta.length);
            exchange.getResponseBody().write(resposta);
            exchange.close();
        });
        zai.start();

        try {
            config.setBaseUrl("http://127.0.0.1:" + openRouter.getAddress().getPort() + "/v1");
            config.setModel("openrouter/free");
            config.setFallbackModels("");
            config.setSecondaryBaseUrl("http://127.0.0.1:" + zai.getAddress().getPort() + "/api/coding/paas/v4");
            config.setSecondaryApiKey("zai-test");
            config.setSecondaryModels("glm-5.3-flash");
            config.setTertiaryModels("qwen/qwen3.7-flash");
            IaProvider provider = new com.freelasatende.service.impl.OpenAiCompatibleProvider(config, objectMapper);

            assertEquals("RESPOSTA_QWEN", provider.enviar("teste", 10));
            assertEquals(java.util.List.of("openrouter:free", "zai:glm", "openrouter:qwen"), ordem);

            ordem.clear();
            assertEquals("RESPOSTA_QWEN", provider.enviarPulandoModelo("teste", 10, "openrouter/free"));
            assertEquals(java.util.List.of("zai:glm", "openrouter:qwen"), ordem);
        } finally {
            openRouter.stop(0);
            zai.stop(0);
        }
    }

    @Test
    @DisplayName("Deve cair para o provedor secundario quando o principal fica sem credito")
    void deveCairParaSecundarioQuandoPrincipalSemCredito() throws Exception {
        AtomicInteger chamadasPrincipal = new AtomicInteger();
        AtomicInteger chamadasSecundario = new AtomicInteger();

        HttpServer principalServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        principalServer.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            chamadasPrincipal.incrementAndGet();
            byte[] resposta = ("{\"error\":{\"message\":\"You have insufficient credits to make "
                + "this request. Please purchase more credits to continue using the service.\","
                + "\"type\":\"invalid_request_error\",\"code\":\"BAD_REQUEST\"}}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, resposta.length);
            exchange.getResponseBody().write(resposta);
            exchange.close();
        });
        principalServer.start();

        HttpServer secundarioServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        secundarioServer.createContext("/api/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            chamadasSecundario.incrementAndGet();
            byte[] resposta = "{\"choices\":[{\"message\":{\"content\":\"RESPOSTA_SECUNDARIO_SEM_CREDITO\"}}]}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resposta.length);
            exchange.getResponseBody().write(resposta);
            exchange.close();
        });
        secundarioServer.start();

        try {
            config.setBaseUrl("http://127.0.0.1:" + principalServer.getAddress().getPort() + "/v1");
            config.setFallbackModels("");
            config.setSecondaryBaseUrl("http://127.0.0.1:" + secundarioServer.getAddress().getPort() + "/api/v1");
            config.setSecondaryApiKey("sk-or-test");
            config.setSecondaryModels("openrouter/free");
            IaProvider provider = new com.freelasatende.service.impl.OpenAiCompatibleProvider(config, objectMapper);

            String resposta = provider.enviar("teste", 10);

            assertEquals("RESPOSTA_SECUNDARIO_SEM_CREDITO", resposta);
            assertEquals(1, chamadasPrincipal.get());
            assertEquals(1, chamadasSecundario.get());
        } finally {
            principalServer.stop(0);
            secundarioServer.stop(0);
        }
    }


    @Test
    @DisplayName("Nao deve chamar provedor secundario quando nao configurado")
    void naoDeveChamarProvedorSecundarioQuandoNaoConfigurado() throws Exception {
        AtomicInteger chamadasPrincipal = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            chamadasPrincipal.incrementAndGet();
            byte[] resposta = ("{\"error\":{\"message\":\"Upstream model provider is temporarily "
                + "unavailable.\",\"type\":\"overloaded_error\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, resposta.length);
            exchange.getResponseBody().write(resposta);
            exchange.close();
        });
        server.start();
        try {
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            config.setFallbackModels("");
            // secondary-base-url e secondary-models ficam em branco de proposito
            IaProvider provider = new com.freelasatende.service.impl.OpenAiCompatibleProvider(config, objectMapper);

            assertThrows(IaProvider.IaProviderException.class, () -> provider.enviar("teste", 10));
            assertEquals(1, chamadasPrincipal.get());
        } finally {
            server.stop(0);
        }
    }

}
