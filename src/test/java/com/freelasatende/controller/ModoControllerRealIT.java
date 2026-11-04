package com.freelasatende.controller;

import com.freelasatende.model.enums.ModoSistema;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes REAIS para ModoController.
 * Usa MockMvc para testar endpoints HTTP sem mocks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModoControllerRealIT {

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void carregarDotEnv(DynamicPropertyRegistry registry) {
        Map<String, String> env = lerDotEnvSeExistir();
        registry.add("ia.api-key", () -> env.getOrDefault("IA_API_KEY", ""));
        registry.add("telegram.bot-token", () -> env.getOrDefault("TELEGRAM_BOT_TOKEN", ""));
        registry.add("telegram.chat-id", () -> env.getOrDefault("TELEGRAM_CHAT_ID", ""));
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
    @DisplayName("GET /api/modos deve retornar modo atual")
    void deveRetornarModoAtual() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/api/modos"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.modo").exists())
            .andExpect(jsonPath("$.descricao").exists());
    }

    @Test
    @DisplayName("PUT /api/modos?modo=LIVRE deve alterar modo para LIVRE")
    void deveAlterarModoParaLIVRE() throws Exception {
        // Act & Assert
        mockMvc.perform(put("/api/modos")
                .param("modo", "LIVRE"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.modo").value("LIVRE"))
            .andExpect(jsonPath("$.mensagem").exists());
    }

    @Test
    @DisplayName("PUT /api/modos?modo=REGULAR deve alterar modo para REGULAR")
    void deveAlterarModoParaREGULAR() throws Exception {
        // Act & Assert
        mockMvc.perform(put("/api/modos")
                .param("modo", "REGULAR"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.modo").value("REGULAR"));
    }

    @Test
    @DisplayName("PUT /api/modos?modo=ENSINAMENTO deve alterar modo para ENSINAMENTO")
    void deveAlterarModoParaENSINAMENTO() throws Exception {
        // Act & Assert
        mockMvc.perform(put("/api/modos")
                .param("modo", "ENSINAMENTO"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.modo").value("ENSINAMENTO"));
    }

    @Test
    @DisplayName("GET /api/modos deve incluir descricao correta para cada modo")
    void deveIncluirDescricaoCorretaParaCadaModo() throws Exception {
        // Act & Assert - LIVRE
        mockMvc.perform(put("/api/modos").param("modo", "LIVRE"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/modos"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.descricao").value("Liberado: pode enviar abordagem inicial curta quando escrita real estiver ligada"));

        // Act & Assert - REGULAR
        mockMvc.perform(put("/api/modos").param("modo", "REGULAR"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/modos"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.descricao").value("Opera com revisao quando ha duvidas e pode enviar abordagem inicial curta"));

        // Act & Assert - ENSINAMENTO
        mockMvc.perform(put("/api/modos").param("modo", "ENSINAMENTO"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/modos"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.descricao").value("Ensino: pode enviar abordagem inicial curta; outras decisoes seguem em revisao"));
    }
}
