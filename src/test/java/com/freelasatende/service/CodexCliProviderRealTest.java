package com.freelasatende.service;

import com.freelasatende.config.IaConfig;
import com.freelasatende.service.impl.CodexCliProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodexCliProviderRealTest {

    @Test
    @EnabledIfSystemProperty(named = "real.codex.enabled", matches = "true")
    void deveEnviarPromptNormalizadoParaCodexReal() throws Exception {
        String accountHome = System.getProperty("real.codex.accountHome");
        if (accountHome == null || accountHome.isBlank()) {
            throw new IllegalStateException("Informe -Dreal.codex.accountHome=/caminho/da/conta");
        }

        IaConfig config = new IaConfig();
        config.setProvider("codex");
        config.setModel(System.getProperty("real.codex.model", "gpt-5.4-mini"));
        config.setTimeout(120);
        config.setMaxRetries(1);
        config.setCodexAccountHomes(accountHome);

        CodexCliProvider provider = new CodexCliProvider(config);

        String resposta = provider.enviar("Responda somente OK.\u0000\uD800", 10);

        assertEquals("OK", resposta.trim());
    }
}
