package com.freelasatende.config;

import com.freelasatende.service.impl.CodexCliProvider;
import com.freelasatende.service.impl.OpenAiCompatibleProvider;
import com.freelasatende.service.interfaceServico.IaProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Factory que cria o IaProvider correto baseado na configuração.
 *
 * Providers suportados:
 *   - openai   → OpenAiCompatibleProvider
 *   - ollama   → OpenAiCompatibleProvider (mesmo formato)
 *   - custom   → OpenAiCompatibleProvider (qualquer API OpenAI-compatible)
 *   - codex    → CodexCliProvider (CLI oficial em modo nao interativo)
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class IaProviderFactory {

    private final IaConfig iaConfig;
    private final ObjectMapper objectMapper;

    @Bean
    public IaProvider iaProvider() {
        String provider = normalizarProvider();
        String apiKeyStatus = identificarApiKey(iaConfig.getApiKey());

        log.info("Criando IA Provider: {} (modelo: {}, url: {}, apiKey: {})",
            provider, iaConfig.getModel(), iaConfig.getBaseUrl(),
            apiKeyStatus);

        return switch (provider) {
            case "codex" -> new CodexCliProvider(iaConfig);
            case "openai", "ollama", "lmstudio", "custom", "openrouter" ->
                new OpenAiCompatibleProvider(iaConfig, objectMapper);
            default -> {
                log.warn("Provider '{}' desconhecido, usando OpenAI-compatible", provider);
                yield new OpenAiCompatibleProvider(iaConfig, objectMapper);
            }
        };
    }

    private String normalizarProvider() {
        String providerConfigurado = iaConfig.getProvider();
        String provider = providerConfigurado != null && !providerConfigurado.isBlank()
            ? providerConfigurado.trim().toLowerCase()
            : "openai";
        return provider;
    }

    private String identificarApiKey(String apiKey) {
        if (apiKey == null) {
            return "null";
        }
        String key = apiKey.trim();
        if (key.isEmpty()) {
            return "[empty]";
        }
        return "[set]";
    }
}
