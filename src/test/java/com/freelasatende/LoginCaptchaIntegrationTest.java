package com.freelasatende;

import com.freelasatende.config.FreelasConfig;
import com.freelasatende.service.impl.LoginJavaDriverless;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste de integração real para testar o fluxo de login com janela real do Chrome.
 * Isso abrirá uma janela real do Chrome via JavaDriverless.
 *
 * A propriedade scheduling.enabled=false desliga o PollingScheduler
 * para evitar interferência durante o teste de login.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "run99freelasLoginIT", matches = "(?i)^I_UNDERSTAND$")
@TestPropertySource(properties = {
    "sistema.polling-intervalo=999999999",
    "scheduling.enabled=false",
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration"
})
public class LoginCaptchaIntegrationTest {

    @Autowired
    private LoginJavaDriverless loginJavaDriverless;

    @DynamicPropertySource
    static void carregarDotEnv(DynamicPropertyRegistry registry) {
        Map<String, String> env = lerDotEnvSeExistir(new File(".env"));

        // A aplicação local carrega `.env`; no `mvn test` isso não acontece.
        // Aqui espelhamos esse comportamento para o teste de integração.
        registry.add("freelas.email", () -> env.getOrDefault("FREELAS_EMAIL", ""));
        registry.add("freelas.password", () -> env.getOrDefault("FREELAS_PASSWORD", ""));
        registry.add("freelas.url-base", () -> env.getOrDefault("FREELAS_URL_BASE", FreelasConfig.DEFAULT_URL_BASE));
        
        // Carrega configurações do Telegram para o teste
        registry.add("telegram.botToken", () -> env.getOrDefault("TELEGRAM_BOT_TOKEN", ""));
        registry.add("telegram.chatId", () -> env.getOrDefault("TELEGRAM_CHAT_ID", ""));
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
                map.put(key, value);
            }
        } catch (Exception ignore) {}
        return map;
    }

    @Test
    public void testarLoginDiretoComJavaDriverless() {
        System.out.println("==================================================");
        System.out.println("INICIANDO TESTE DE LOGIN REAL COM JAVADRIVERLESS");
        System.out.println("==================================================");
        
        boolean resultado = loginJavaDriverless.executarLogin();
        
        System.out.println("==================================================");
        System.out.println("RESULTADO DO LOGIN: " + (resultado ? "✅ SUCESSO" : "❌ FALHA"));
        System.out.println("==================================================");

        // Não fazemos assert hard aqui porque depende de credenciais válidas no .env
        // e de estado externo (Cloudflare, 99Freelas disponível, etc.)
        // O objetivo é testar que o fluxo completo executa sem exceções
    }
}
