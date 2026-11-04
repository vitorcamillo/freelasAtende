package com.freelasatende.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.IaConfig;
import com.freelasatende.config.IaProviderFactory;
import com.freelasatende.service.interfaceServico.IaProvider;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes REAIS para IaProviderFactory.
 * Testa que a factory cria o provider correto baseado na configuração.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IaProviderFactoryRealIT {

    @Autowired
    private IaProviderFactory iaProviderFactory;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void carregarDotEnv(DynamicPropertyRegistry registry) {
        Map<String, String> env = lerDotEnvSeExistir();
        registry.add("ia.api-key", () -> env.getOrDefault("IA_API_KEY", ""));
        registry.add("ia.base-url", () -> env.getOrDefault("IA_BASE_URL", "https://api.openai.com/v1"));
        registry.add("ia.model", () -> env.getOrDefault("IA_MODEL", "gpt-4o-mini"));
        registry.add("ia.timeout", () -> env.getOrDefault("IA_TIMEOUT", "30"));
    }

    private static Map<String, String> lerDotEnvSeExistir() {
        Map<String, String> map = new java.util.HashMap<>();
        java.io.File file = new java.io.File(".env");
        if (!file.exists() || !file.isFile()) {
            return map;
        }
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int idx = line.indexOf('=');
                if (idx <= 0) continue;
                String key = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();
                value = value.replaceAll("^\"|\"$", "").replaceAll("^\'|'$", "");
                map.put(key, value);
            }
        } catch (Exception e) {
            // ignore
        }
        return map;
    }

    @Test
    @DisplayName("Factory deve criar provider nao-nulo")
    void deveCriarProviderNaoNulo() {
        // Act
        IaProvider provider = iaProviderFactory.iaProvider();

        // Assert
        assertNotNull(provider);
        assertNotNull(provider.getNome());
        assertNotNull(provider.getModelo());
    }

    @Test
    @DisplayName("Factory deve criar OpenAiCompatibleProvider para provider openai case insensitive")
    void deveCriarOpenAiCompatibleProviderCaseInsensitive() {
        // Arrange - criar factory diretamente com config
        IaConfig config = new IaConfig();
        config.setProvider("OPENAI");
        config.setModel("gpt-4o-mini");
        config.setBaseUrl("https://api.openai.com/v1");
        config.setApiKey("test-key");

        // Act
        IaProviderFactory factory = new IaProviderFactory(config, objectMapper);
        IaProvider provider = factory.iaProvider();

        // Assert
        assertNotNull(provider);
        assertEquals("openai", provider.getNome());
    }

    @Test
    @DisplayName("Factory deve criar OpenAiCompatibleProvider para openai")
    void deveCriarOpenAiCompatibleProviderParaOpenai() {
        // Arrange
        IaConfig config = new IaConfig();
        config.setProvider("openai");
        config.setModel("gpt-4o");
        config.setBaseUrl("https://api.openai.com/v1");
        config.setApiKey("sk-test");

        // Act
        IaProviderFactory factory = new IaProviderFactory(config, objectMapper);
        IaProvider provider = factory.iaProvider();

        // Assert
        assertNotNull(provider);
        assertEquals("openai", provider.getNome());
    }

    @Test
    @DisplayName("Factory deve criar OpenAiCompatibleProvider para ollama")
    void deveCriarOpenAiCompatibleProviderParaOllama() {
        // Arrange
        IaConfig config = new IaConfig();
        config.setProvider("ollama");
        config.setModel("llama3");
        config.setBaseUrl("http://localhost:11434/v1");
        config.setApiKey(null);

        // Act
        IaProviderFactory factory = new IaProviderFactory(config, objectMapper);
        IaProvider provider = factory.iaProvider();

        // Assert
        assertNotNull(provider);
        assertEquals("ollama", provider.getNome());
    }

    @Test
    @DisplayName("Factory deve criar OpenAiCompatibleProvider para lmstudio")
    void deveCriarOpenAiCompatibleProviderParaLmstudio() {
        // Arrange
        IaConfig config = new IaConfig();
        config.setProvider("lmstudio");
        config.setModel("local-model");
        config.setBaseUrl("http://localhost:1234/v1");

        // Act
        IaProviderFactory factory = new IaProviderFactory(config, objectMapper);
        IaProvider provider = factory.iaProvider();

        // Assert
        assertNotNull(provider);
        assertEquals("lmstudio", provider.getNome());
    }

    @Test
    @DisplayName("Factory deve usar OpenAI-compatible como default para provider desconhecido")
    void deveUsarOpenAiCompatibleComoDefaultParaProviderDesconhecido() {
        // Arrange
        IaConfig config = new IaConfig();
        config.setProvider("desconhecido");
        config.setModel("any-model");
        config.setBaseUrl("http://localhost:8080/v1");
        config.setApiKey(null);

        // Act
        IaProviderFactory factory = new IaProviderFactory(config, objectMapper);
        IaProvider provider = factory.iaProvider();

        // Assert
        assertNotNull(provider);
        assertEquals("desconhecido", provider.getNome());
    }

    @Test
    @DisplayName("Factory deve usar openai como default quando provider e null")
    void deveUsarOpenaiComoDefaultQuandoProviderNull() {
        // Arrange
        IaConfig config = new IaConfig();
        config.setProvider(null);
        config.setModel("gpt-4o-mini");
        config.setBaseUrl("https://api.openai.com/v1");
        config.setApiKey("test");

        // Act
        IaProviderFactory factory = new IaProviderFactory(config, objectMapper);
        IaProvider provider = factory.iaProvider();

        // Assert
        assertNotNull(provider);
        assertEquals("openai", provider.getNome());
    }

    @Test
    @DisplayName("Factory deve usar openai como default quando provider e vazio")
    void deveUsarOpenaiComoDefaultQuandoProviderVazio() {
        // Arrange
        IaConfig config = new IaConfig();
        config.setProvider("");
        config.setModel("gpt-4o-mini");
        config.setBaseUrl("https://api.openai.com/v1");
        config.setApiKey("test");

        // Act
        IaProviderFactory factory = new IaProviderFactory(config, objectMapper);
        IaProvider provider = factory.iaProvider();

        // Assert
        assertNotNull(provider);
        assertEquals("openai", provider.getNome());
    }
}
