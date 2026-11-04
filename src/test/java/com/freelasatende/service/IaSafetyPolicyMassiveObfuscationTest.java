package com.freelasatende.service;

import com.freelasatende.util.IaSafetyPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "scheduling.enabled=false")
@ActiveProfiles("test")
@DisplayName("IaSafetyPolicy: cobertura massiva de obfuscação")
class IaSafetyPolicyMassiveObfuscationTest {

    @Autowired
    private IaSafetyPolicy iaSafetyPolicy;

    @DynamicPropertySource
    static void carregarDotEnv(DynamicPropertyRegistry registry) {
        Map<String, String> env = lerDotEnvSeExistir(new File(".env"));
        registry.add("ia.api-key", () -> env.getOrDefault("IA_API_KEY", ""));
        registry.add("ia.base-url", () -> env.getOrDefault("IA_BASE_URL", "https://api.openai.com/v1"));
        registry.add("ia.model", () -> env.getOrDefault("IA_MODEL", "gpt-4o-mini"));
        registry.add("freelas.email", () -> env.getOrDefault("FREELAS_EMAIL", ""));
        registry.add("freelas.senha", () -> env.getOrDefault("FREELAS_PASSWORD", ""));
        registry.add("telegram.bot-token", () -> env.getOrDefault("TELEGRAM_BOT_TOKEN", ""));
        registry.add("telegram.chat-id", () -> env.getOrDefault("TELEGRAM_CHAT_ID", ""));
    }

    private static Map<String, String> lerDotEnvSeExistir(File file) {
        Map<String, String> map = new HashMap<>();
        if (!file.exists() || !file.isFile()) {
            return map;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int idx = line.indexOf('=');
                if (idx <= 0) {
                    continue;
                }
                String key = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();
                value = value.replaceAll("^\"|\"$", "").replaceAll("^\'|'$", "");
                map.put(key, value);
            }
        } catch (Exception ignore) {}
        return map;
    }

    @Test
    @DisplayName("Deve bloquear variacoes obfuscadas de termos proibidos")
    void deveBloquearVariacoesObfuscadas() {
        List<String> tokens = List.of(
            "whatsapp", "whats", "zap", "wpp", "pix", "gmail", "discord", "telegram"
        );
        // apenas tokens longos e inequivocos bloqueiam embutidos em outras palavras
        List<String> tokensEmbutidos = List.of("whatsapp", "telegram", "discord", "gmail");

        List<String> separadores = List.of(" ", ".", "-", "_", "/", "|", "   ", " . ");

        for (String token : tokens) {
            assertTrue(iaSafetyPolicy.containsUnsafeContent(token), "Token simples nao bloqueado: " + token);
            assertTrue(iaSafetyPolicy.containsUnsafeContent(token.toUpperCase()), "Token uppercase nao bloqueado: " + token);

            if (tokensEmbutidos.contains(token)) {
                assertTrue(iaSafetyPolicy.containsUnsafeContent("xx" + token + "yy"),
                    "Token embutido nao bloqueado: " + token);
            }

            for (String separador : separadores) {
                String obfuscado = intercalar(token, separador);
                assertTrue(iaSafetyPolicy.containsUnsafeContent(obfuscado),
                    "Token obfuscado nao bloqueado: " + obfuscado);
            }
        }
    }

    @Test
    @DisplayName("Nao deve bloquear mensagens seguras comuns")
    void naoDeveBloquearMensagensSeguras() {
        List<String> mensagensSeguras = List.of(
            "Posso detalhar o escopo aqui na plataforma.",
            "Consigo entregar em 7 dias uteis, com documentacao.",
            "Se fizer sentido, posso comecar pela etapa de descoberta.",
            "Vou compartilhar um plano tecnico e milestones claros.",
            "Podemos alinhar requisitos funcionais e nao funcionais."
        );

        for (String mensagem : mensagensSeguras) {
            assertFalse(iaSafetyPolicy.containsUnsafeContent(mensagem),
                "Mensagem segura foi bloqueada indevidamente: " + mensagem);
        }
    }

    private String intercalar(String token, String separador) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            builder.append(token.charAt(i));
            if (i < token.length() - 1) {
                builder.append(separador);
            }
        }
        return builder.toString();
    }
}
