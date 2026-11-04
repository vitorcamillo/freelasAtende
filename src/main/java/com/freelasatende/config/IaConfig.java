package com.freelasatende.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configurações genéricas de IA.
 * Suporta qualquer provider compatível com OpenAI Chat Completions API.
 *
 * Propriedades:
 *   ia.provider    = openai | ollama | lmstudio | custom | openrouter | codex
 *   ia.api-key     = chave da API
 *   ia.base-url    = URL base (ex: https://api.openai.com/v1)
 *   ia.model       = modelo (ex: gpt-4o, llama3)
 *   ia.max-retries = tentativas em caso de falha (padrão: 3)
 *   ia.timeout     = timeout em segundos (padrão: 60)
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ia")
public class IaConfig {

    private String provider = "openai";
    private String apiKey;
    private String baseUrl = "https://api.openai.com/v1";
    private String model = "openrouter/free";
    private int maxRetries = 3;
    private int timeout = 60;
    private long unavailableCooldownSegundos = 3600;
    private boolean fallbackWhenMissingKey = true;
    private String fallbackModels;
    private String codexAccountHomes;

    /**
     * Segunda camada de fallback, com provedor/URL/chave proprios. Usada
     * quando a cadeia principal (mesma base-url/api-key) se esgota inteira
     * (todos os modelos de ia.model + ia.fallback-models falharam com erro
     * retriable). Hoje serve para o OpenRouter, mas o nome e generico para
     * caber qualquer segundo provedor OpenAI-compatible no futuro.
     */
    private String secondaryBaseUrl;
    private String secondaryApiKey;
    private String secondaryModels;

    /**
     * Terceira camada: modelos tentados depois da secundaria, usando de novo a
     * base-url/api-key principais. Serve para por um modelo pago barato do
     * OpenRouter depois da z.ai, sem precisar de uma terceira chave.
     */
    private String tertiaryModels;
}
