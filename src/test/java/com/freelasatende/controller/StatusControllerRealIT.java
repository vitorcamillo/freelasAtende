package com.freelasatende.controller;

import com.freelasatende.model.entity.Configuracao;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.repository.ConfiguracaoRepository;
import com.freelasatende.service.interfaceServico.ScraperService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes REAIS para StatusController.
 * Usa MockMvc para testar endpoints HTTP sem mocks nos serviços.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatusControllerRealIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConfiguracaoRepository configuracaoRepository;

    @DynamicPropertySource
    static void carregarDotEnv(DynamicPropertyRegistry registry) {
        Map<String, String> env = lerDotEnvSeExistir();
        registry.add("ia.api-key", () -> env.getOrDefault("IA_API_KEY", ""));
        registry.add("telegram.bot-token", () -> env.getOrDefault("TELEGRAM_BOT_TOKEN", ""));
        registry.add("telegram.chat-id", () -> env.getOrDefault("TELEGRAM_CHAT_ID", ""));
        registry.add("auth.cookies-arquivo", () -> "target/test-cookies-status.json");
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

    @BeforeEach
    @Transactional
    void setup() {
        configuracaoRepository.deleteAll();
        Configuracao config = Configuracao.builder()
            .modoSistema(ModoSistema.REGULAR)
            .pollingIntervaloSegundos(30)
            .ativo(true)
            .limiteConexoesDiario(200)
            .conexoesUsadasHoje(0)
            .dataUltimoReset(LocalDateTime.now())
            .build();
        configuracaoRepository.save(config);
    }

    @AfterEach
    @Transactional
    void limparDados() {
        configuracaoRepository.deleteAll();
    }

    @Test
    @DisplayName("GET /api/status deve retornar status do sistema")
    void deveRetornarStatusDoSistema() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/api/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sistema").value("FreelasAtende"))
            .andExpect(jsonPath("$.conexoes").exists())
            .andExpect(jsonPath("$.javaVersion").exists());
    }

    @Test
    @DisplayName("GET /api/conexoes deve retornar status das conexoes")
    void deveRetornarStatusConexoes() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/api/conexoes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").exists())
            .andExpect(jsonPath("$.temDisponiveis").exists());
    }

    @Test
    @DisplayName("GET /api/debug/telegram deve retornar info do bot")
    void deveRetornarInfoTelegram() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/api/debug/telegram"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.botToken").exists())
            .andExpect(jsonPath("$.botTokenConfigurado").exists())
            .andExpect(jsonPath("$.chatIdConfigurado").exists())
            .andExpect(jsonPath("$.chatId").value(matchesPattern("NULL|SET \\(\\d+ chars\\)")));
    }

    @Test
    @DisplayName("GET /api/auth/session deve verificar sessao")
    void deveVerificarSessao() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/api/auth/session"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessaoValida").exists());
    }

    @Test
    @DisplayName("POST /api/auth/cookies deve aceitar JSON de cookies")
    @Transactional
    void deveAceitarCookiesJson() throws Exception {
        // Arrange
        String cookiesJson = "{\"JSESSIONID\":\"test123\",\"sgcn\":\"abc\"}";

        // Act & Assert
        mockMvc.perform(post("/api/auth/cookies")
                .contentType("application/json")
                .content(cookiesJson))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mensagem").value("Cookies atualizados"))
            .andExpect(jsonPath("$.sessaoValida").exists());
    }
}
