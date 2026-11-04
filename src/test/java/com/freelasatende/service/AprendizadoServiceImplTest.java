package com.freelasatende.service;

import com.freelasatende.model.entity.Aprendizado;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.MotivoAprendizado;
import com.freelasatende.model.enums.StatusRegraAprendida;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.repository.AprendizadoRepository;
import com.freelasatende.service.impl.AprendizadoServiceImpl;
import com.freelasatende.service.interfaceServico.AprendizadoService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes de integração para AprendizadoService.
 * Usa SpringBootTest com serviços reais.
 */
@SpringBootTest(properties = "scheduling.enabled=false")
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AprendizadoServiceImplTest {

    private static final Path MEMORIA_TESTE = Path.of("target/test-memoria-aprendizado.json");

    @Autowired
    private AprendizadoService aprendizadoService;

    @Autowired
    private AprendizadoRepository aprendizadoRepository;

    @DynamicPropertySource
    static void propriedadesSemCredenciaisExternas(DynamicPropertyRegistry registry) {
        registry.add("ia.api-key", () -> "");
        registry.add("ia.base-url", () -> "https://api.local.invalid");
        registry.add("ia.model", () -> "test-local");
        registry.add("ia.memoria-arquivo", () -> MEMORIA_TESTE.toString());
    }

    @AfterEach
    @Transactional
    void limparDados() {
        aprendizadoRepository.deleteAll();
        try {
            Files.deleteIfExists(MEMORIA_TESTE);
        } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("Deve salvar aprendizado")
    void deveSalvarAprendizado() {
        // Arrange
        Aprendizado aprendizado = criarAprendizado();

        // Act
        Aprendizado resultado = aprendizadoService.salvar(aprendizado);

        // Assert
        assertNotNull(resultado);
        assertNotNull(resultado.getId());
    }

    @Test
    @DisplayName("Deve registrar decisão")
    void deveRegistrarDecisao() {
        // Act
        aprendizadoService.registrarDecisao(
            1L, "Site institucional", true,
            "Projeto adequado", ModoSistema.REGULAR, "Feedback positivo"
        );

        // Assert
        List<Aprendizado> historico = aprendizadoService.buscarHistorico();
        assertFalse(historico.isEmpty());
    }

    @Test
    @DisplayName("Feedback humano vira regra aprendida reutilizavel")
    void feedbackHumanoViraRegraAprendidaReutilizavel() {
        aprendizadoService.registrarDecisao(
            2L,
            "Landing page",
            false,
            "RESPONDER_CONVERSA",
            ModoSistema.REGULAR,
            "Ficou robotico e com cara de sistema"
        );

        List<String> regras = aprendizadoService.buscarRegrasAprendidas();
        List<Aprendizado> historico = aprendizadoService.buscarHistorico();

        assertFalse(historico.isEmpty());
        assertNotNull(historico.get(0).getRegrasAprendidas());
        assertTrue(historico.get(0).getRegrasAprendidas().contains("robotico"));
        assertTrue(regras.isEmpty(), "Regras de busca publica continuam apenas para aprendizados aprovados");
    }

    @Test
    @DisplayName("Feedback estruturado vira regra auditável e sincroniza memória")
    void feedbackEstruturadoViraRegraAuditavelESincronizaMemoria() throws Exception {
        aprendizadoService.registrarDecisaoDetalhada(
            77L,
            "Landing page",
            true,
            TipoAcaoPendente.ENVIAR_PERGUNTA,
            ModoSistema.ENSINAMENTO,
            "BOM_TOM",
            MotivoAprendizado.BOM_TOM,
            "Oi, consegue confirmar o prazo?",
            null,
            "BOTAO_TELEGRAM"
        );

        List<Aprendizado> historico = aprendizadoService.buscarHistorico();
        String memoria = Files.readString(MEMORIA_TESTE);

        assertEquals(1, historico.size());
        assertEquals(MotivoAprendizado.BOM_TOM, historico.get(0).getMotivoAprendizado());
        assertEquals(StatusRegraAprendida.CONFIRMADA, historico.get(0).getStatusRegra());
        assertTrue(memoria.contains("regras_ensinamento"));
        assertTrue(memoria.contains("BOM_TOM"));
    }

    @Test
    @DisplayName("Memória corrompida gera backup antes de reescrever")
    void memoriaCorrompidaGeraBackup() throws Exception {
        Files.createDirectories(MEMORIA_TESTE.getParent());
        Files.writeString(MEMORIA_TESTE, "{json quebrado");

        aprendizadoService.registrarDecisaoDetalhada(
            78L,
            "Sistema web",
            false,
            TipoAcaoPendente.RESPONDER_CONVERSA,
            ModoSistema.ENSINAMENTO,
            "ROBOTICO",
            MotivoAprendizado.ROBOTICO,
            "Teste tecnico automatizado",
            null,
            "BOTAO_TELEGRAM"
        );

        assertTrue(Files.exists(MEMORIA_TESTE));
        assertTrue(Files.list(MEMORIA_TESTE.getParent())
            .anyMatch(path -> path.getFileName().toString().startsWith("test-memoria-aprendizado.json.corrompido-")));
    }

    @Test
    @DisplayName("Deve buscar histórico")
    void deveBuscarHistorico() {
        // Arrange
        aprendizadoRepository.save(criarAprendizado());
        aprendizadoRepository.save(criarAprendizado());

        // Act
        List<Aprendizado> resultado = aprendizadoService.buscarHistorico();

        // Assert
        assertEquals(2, resultado.size());
    }

    @Test
    @DisplayName("Deve buscar regras aprendidas")
    void deveBuscarRegrasAprendidas() {
        // Arrange
        Aprendizado aprendizado = criarAprendizado();
        aprendizado.setFoiAprovado(true);
        aprendizadoRepository.save(aprendizado);

        // Act
        List<String> regras = aprendizadoService.buscarRegrasAprendidas();

        // Assert
        assertNotNull(regras);
    }

    @Test
    @DisplayName("Não deve atualizar regras com feedback vazio")
    void naoDeveAtualizarRegrasComFeedbackVazio() {
        // Act - não deve lançar exceção
        assertDoesNotThrow(() -> {
            aprendizadoService.atualizarRegras(null);
            aprendizadoService.atualizarRegras("");
        });

        // Assert: verifica que não salvou nada
        List<Aprendizado> historico = aprendizadoService.buscarHistorico();
        assertTrue(historico.isEmpty() || historico.stream().allMatch(a -> a.getMotivo() == null || !a.getMotivo().contains("atualizado")));
    }

    private Aprendizado criarAprendizado() {
        return Aprendizado.builder()
            .data(java.time.LocalDateTime.now())
            .projetoId(1L)
            .tituloProjeto("Teste")
            .foiAprovado(true)
            .motivo("Teste OK")
            .modoUsado(ModoSistema.REGULAR)
            .build();
    }
}
