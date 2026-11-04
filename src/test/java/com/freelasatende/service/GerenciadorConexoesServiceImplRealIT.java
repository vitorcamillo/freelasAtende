package com.freelasatende.service;

import com.freelasatende.config.OperacaoDefaults;
import com.freelasatende.model.entity.Configuracao;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.repository.ConfiguracaoRepository;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes REAIS (SEM mocks) para GerenciadorConexoesServiceImpl.
 * Usa H2 em memória e testa a lógica real do serviço.
 */
@SpringBootTest(properties = {
    "scheduling.enabled=false",
    "ia.api-key=",
    "ia.base-url=https://api.local.invalid",
    "ia.model=test-local",
    "telegram.bot-token=",
    "telegram.chat-id="
})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GerenciadorConexoesServiceImplRealIT {

    @Autowired
    private ConfiguracaoRepository configuracaoRepository;

    @Autowired
    private GerenciadorConexoesService gerenciadorConexoesService;

    private static final int LIMITE_CONEXOES_DIARIO = OperacaoDefaults.LIMITE_CONEXOES_DIARIO;

    @BeforeEach
    void setup() {
        configuracaoRepository.deleteAll();
        Configuracao config = Configuracao.builder()
            .modoSistema(ModoSistema.REGULAR)
            .pollingIntervaloSegundos(30)
            .ativo(true)
            .limiteConexoesDiario(LIMITE_CONEXOES_DIARIO)
            .conexoesUsadasHoje(0)
            .dataUltimoReset(LocalDateTime.now())
            .build();
        configuracaoRepository.save(config);
    }

    @AfterEach
    void limparDados() {
        configuracaoRepository.deleteAll();
    }

    @Test
    @DisplayName("Deve indicar que tem conexoes disponiveis quando contador esta em zero")
    void deveIndicarConexoesDisponiveis() {
        assertTrue(gerenciadorConexoesService.temConexoesDisponiveis());
        assertEquals(0, configuracaoAtual().getConexoesUsadasHoje());
    }

    @Test
    @DisplayName("Deve incrementar contador ao registrar uso")
    void deveIncrementarContadorAoRegistrarUso() {
        gerenciadorConexoesService.registrarUsoConexao();

        Configuracao atualizada = configuracaoAtual();
        assertEquals(1, atualizada.getConexoesUsadasHoje());
    }

    @Test
    @DisplayName("Deve bloquear quando atinge limite diario")
    void deveBloquearQuandoAtingirLimite() {
        Configuracao config = configuracaoAtual();
        config.setConexoesUsadasHoje(LIMITE_CONEXOES_DIARIO);
        configuracaoRepository.save(config);

        assertFalse(gerenciadorConexoesService.temConexoesDisponiveis());
    }

    @Test
    @DisplayName("Deve criar configuracao default se nao existir")
    void deveCriarConfiguracaoDefaultSeNaoExistir() {
        configuracaoRepository.deleteAll();

        assertTrue(gerenciadorConexoesService.temConexoesDisponiveis());

        Configuracao config = configuracaoRepository.findFirstByOrderByIdAsc().orElseThrow();
        assertNotNull(config);
        assertNotNull(config.getId());
        assertEquals(ModoSistema.REGULAR, config.getModoSistema());
        assertEquals(LIMITE_CONEXOES_DIARIO, config.getLimiteConexoesDiario());
    }

    @Test
    @DisplayName("Deve resetar contador quando muda o dia")
    void deveResetarContadorQuandoMudaDia() {
        Configuracao config = configuracaoAtual();
        config.setDataUltimoReset(LocalDateTime.now().minusDays(1));
        config.setConexoesUsadasHoje(150);
        configuracaoRepository.save(config);

        gerenciadorConexoesService.verificarResetDiario();

        Configuracao atualizada = configuracaoAtual();
        assertEquals(0, atualizada.getConexoesUsadasHoje());
        assertEquals(LocalDate.now(), atualizada.getDataUltimoReset().toLocalDate());
    }

    @Test
    @DisplayName("Deve resetar antes de registrar uso quando muda o dia")
    void deveResetarAntesDeRegistrarUsoQuandoMudaDia() {
        Configuracao config = configuracaoAtual();
        config.setDataUltimoReset(LocalDateTime.now().minusDays(1));
        config.setConexoesUsadasHoje(150);
        configuracaoRepository.save(config);

        gerenciadorConexoesService.registrarUsoConexao();

        Configuracao atualizada = configuracaoAtual();
        assertEquals(1, atualizada.getConexoesUsadasHoje());
        assertEquals(LocalDate.now(), atualizada.getDataUltimoReset().toLocalDate());
    }

    @Test
    @DisplayName("Deve formatar status corretamente")
    void deveFormatarStatusCorretamente() {
        String status = gerenciadorConexoesService.getStatusConexoes();

        assertTrue(status.contains("200"));
        assertTrue(status.contains("Conexoes abertas:"));
    }

    private Configuracao configuracaoAtual() {
        return configuracaoRepository.findFirstByOrderByIdAsc().orElseThrow();
    }
}
