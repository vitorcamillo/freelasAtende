package com.freelasatende.controller;

import com.freelasatende.model.entity.Aprendizado;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.repository.AprendizadoRepository;
import com.freelasatende.service.interfaceServico.AprendizadoService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testes REAIS para AprendizadoController.
 * Usa MockMvc para testar endpoints HTTP sem mocks nos serviços.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AprendizadoControllerRealIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AprendizadoRepository aprendizadoRepository;

    @Autowired
    private AprendizadoService aprendizadoService;

    @DynamicPropertySource
    static void propriedadesSemCredenciaisExternas(DynamicPropertyRegistry registry) {
        registry.add("ia.api-key", () -> "");
        registry.add("telegram.bot-token", () -> "");
        registry.add("telegram.chat-id", () -> "");
    }

    @BeforeEach
    @Transactional
    void setup() {
        aprendizadoRepository.deleteAll();
    }

    @AfterEach
    @Transactional
    void limparDados() {
        aprendizadoRepository.deleteAll();
    }

    @Test
    @DisplayName("GET /api/aprendizados deve listar aprendizados")
    void deveListarAprendizados() throws Exception {
        // Arrange
        criarAprendizadoTeste(1L, 100L, "Projeto A", true);

        // Act & Assert
        mockMvc.perform(get("/api/aprendizados"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /api/aprendizados/regras deve listar regras aprendidas")
    void deveListarRegras() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/api/aprendizados/regras"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("POST /api/aprendizados deve criar aprendizado")
    @Transactional
    void deveCriarAprendizado() throws Exception {
        // Arrange
        String requestBody = """
            {
                "projetoId": 500,
                "tituloProjeto": "Projeto Nuevo",
                "foiAprovado": true,
                "motivo": "Boa descrição",
                "modoUsado": "REGULAR",
                "feedback": "Cliente responsivo"
            }
            """;

        // Act & Assert
        mockMvc.perform(post("/api/aprendizados")
                .contentType("application/json")
                .content(requestBody))
            .andExpect(status().isOk());

        // Verificar que foi criado no banco
        List<Aprendizado> aprendizados = aprendizadoRepository.findByProjetoIdOrderByDataDesc(500L);
        assertFalse(aprendizados.isEmpty());
        assertEquals("Projeto Nuevo", aprendizados.get(0).getTituloProjeto());
    }

    private Aprendizado criarAprendizadoTeste(Long id, Long projetoId, String titulo, boolean aprovado) {
        Aprendizado aprendizado = Aprendizado.builder()
            .id(id)
            .projetoId(projetoId)
            .tituloProjeto(titulo)
            .foiAprovado(aprovado)
            .motivo("Motivo teste")
            .modoUsado(ModoSistema.REGULAR)
            .data(LocalDateTime.now())
            .build();
        return aprendizadoRepository.save(aprendizado);
    }
}
