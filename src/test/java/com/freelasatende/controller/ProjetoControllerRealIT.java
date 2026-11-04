package com.freelasatende.controller;

import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.StatusProjeto;
import com.freelasatende.repository.ProjetoRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes REAIS para ProjetoController.
 * Usa MockMvc para testar endpoints HTTP sem mocks nos serviços.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjetoControllerRealIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjetoRepository projetoRepository;

    @DynamicPropertySource
    static void carregarDotEnv(DynamicPropertyRegistry registry) {
        Map<String, String> env = lerDotEnvSeExistir();
        registry.add("ia.api-key", () -> env.getOrDefault("IA_API_KEY", ""));
        registry.add("ia.base-url", () -> env.getOrDefault("IA_BASE_URL", "https://api.openai.com/v1"));
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

    @BeforeEach
    @Transactional
    void setup() {
        projetoRepository.deleteAll();
    }

    @AfterEach
    @Transactional
    void limparDados() {
        projetoRepository.deleteAll();
    }

    @Test
    @DisplayName("GET /api/projetos deve listar todos os projetos")
    void deveListarTodosProjetos() throws Exception {
        // Arrange
        Projeto projeto = criarProjetoTeste(1L, "Projeto Teste 1", StatusProjeto.NOVO);
        projetoRepository.save(projeto);

        // Act & Assert
        mockMvc.perform(get("/api/projetos"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$[0].id").value(1L))
            .andExpect(jsonPath("$[0].titulo").value("Projeto Teste 1"));
    }

    @Test
    @DisplayName("GET /api/projetos?status=NOVO deve filtrar por status")
    void deveFiltrarPorStatus() throws Exception {
        // Arrange
        Projeto projeto1 = criarProjetoTeste(1L, "Projeto Novo", StatusProjeto.NOVO);
        Projeto projeto2 = criarProjetoTeste(2L, "Projeto Ignorado", StatusProjeto.IGNORADO);
        projetoRepository.save(projeto1);
        projetoRepository.save(projeto2);

        // Act & Assert
        mockMvc.perform(get("/api/projetos?status=NOVO"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$[0].status").value("NOVO"));
    }

    @Test
    @DisplayName("GET /api/projetos/{id} deve retornar projeto existente")
    void deveRetornarProjetoPorId() throws Exception {
        // Arrange
        Projeto projeto = criarProjetoTeste(100L, "Projeto Busca", StatusProjeto.NOVO);
        projetoRepository.save(projeto);

        // Act & Assert
        mockMvc.perform(get("/api/projetos/100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(100L))
            .andExpect(jsonPath("$.titulo").value("Projeto Busca"));
    }

    @Test
    @DisplayName("GET /api/projetos/{id} deve retornar 404 para projeto inexistente")
    void deveRetornar404ParaProjetoInexistente() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/api/projetos/999999"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/projetos/nao-processados deve listar projetos não processados")
    void deveListarProjetosNaoProcessados() throws Exception {
        // Arrange
        Projeto projeto1 = criarProjetoTeste(1L, "Pendente", StatusProjeto.NOVO);
        projeto1.setFoiProcessado(false);
        Projeto projeto2 = criarProjetoTeste(2L, "Processado", StatusProjeto.NOVO);
        projeto2.setFoiProcessado(true);
        projetoRepository.save(projeto1);
        projetoRepository.save(projeto2);

        // Act & Assert
        mockMvc.perform(get("/api/projetos/nao-processados"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("POST /api/projetos/{id}/ignorar deve marcar projeto como ignorado")
    @Transactional
    void deveIgnorarProjeto() throws Exception {
        // Arrange
        Projeto projeto = criarProjetoTeste(200L, "Projeto Ignorar", StatusProjeto.NOVO);
        projeto.setFoiProcessado(false);
        projetoRepository.save(projeto);

        // Act & Assert
        mockMvc.perform(post("/api/projetos/200/ignorar"))
            .andExpect(status().isOk());

        // Verificar que foi atualizado no banco
        Projeto ignorado = projetoRepository.findById(200L).orElseThrow();
        assertEquals(StatusProjeto.IGNORADO, ignorado.getStatus());
        assertTrue(ignorado.getFoiProcessado());
    }

    @Test
    @DisplayName("POST /api/projetos/{id}/ignorar deve retornar 404 para projeto inexistente")
    void deveRetornar404AoIgnorarProjetoInexistente() throws Exception {
        // Act & Assert
        mockMvc.perform(post("/api/projetos/888888/ignorar"))
            .andExpect(status().isNotFound());
    }

    private Projeto criarProjetoTeste(Long id, String titulo, StatusProjeto status) {
        return Projeto.builder()
            .id(id)
            .titulo(titulo)
            .descricao("Descricao teste")
            .categoria("TI")
            .orcamento(BigDecimal.valueOf(1000))
            .status(status)
            .foiProcessado(false)
            .dataCriacao(LocalDateTime.now())
            .build();
    }
}
