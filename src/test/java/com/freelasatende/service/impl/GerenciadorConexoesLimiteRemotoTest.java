package com.freelasatende.service.impl;

import com.freelasatende.model.entity.Configuracao;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.repository.ConfiguracaoRepository;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Circuito de limite remoto do marketplace POR PLATAFORMA: quando o Workana
 * recusa o envio por falta de conexoes (limite semanal do lado remoto), o
 * gerenciador local bloqueia novas tentativas apenas nele ate o reset diario
 * — cada novo projeto Workana nao repete o ciclo de pre-check + POST + WARN e
 * as demais plataformas seguem livres.
 */
class GerenciadorConexoesLimiteRemotoTest {

    private final ConfiguracaoRepository configuracaoRepository = mock(ConfiguracaoRepository.class);
    private Configuracao config;

    @BeforeEach
    void prepararCenario() {
        config = Configuracao.builder()
            .modoSistema(ModoSistema.REGULAR)
            .pollingIntervaloSegundos(30)
            .ativo(true)
            .limiteConexoesDiario(5)
            .conexoesUsadasHoje(0)
            .dataUltimoReset(LocalDateTime.now())
            .build();
        when(configuracaoRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(config));
        when(configuracaoRepository.conexoesUsadasHojeAtual(config.getId())).thenReturn(Optional.of(0));
    }

    @Test
    void marcarLimiteRemotoBloqueiaSoAPlataformaRecusada() {
        GerenciadorConexoesService service = service();
        assertTrue(service.temConexoesDisponiveis());

        service.marcarLimiteRemotoExterno(PlataformaFreela.WORKANA);

        assertTrue(service.limiteRemotoAtivo(PlataformaFreela.WORKANA),
            "Recusa remota do Workana deve abrir o circuito do Workana");
        assertFalse(service.limiteRemotoAtivo(PlataformaFreela.FREELAS_99),
            "Limite do Workana nao pode bloquear outra plataforma");
        assertTrue(service.temConexoesDisponiveis(),
            "Gate local de conexoes segue livre para as demais plataformas");
    }

    @Test
    void segundaMarcacaoMantemEstadoBloqueadoSemEfeitoAdicional() {
        GerenciadorConexoesService service = service();

        service.marcarLimiteRemotoExterno(PlataformaFreela.WORKANA);
        service.marcarLimiteRemotoExterno(PlataformaFreela.WORKANA);

        assertTrue(service.limiteRemotoAtivo(PlataformaFreela.WORKANA));
        // Deduplicacao do warn (log so na transicao false->true) nao e verificada aqui:
        // o pacote de testes nao tem precedente de captura de log; a segunda marcacao
        // e retorno imediato (early return) na implementacao.
    }

    @Test
    void resetDiarioLimpaLimiteRemotoEReabreCircuito() {
        GerenciadorConexoesService service = service();
        service.marcarLimiteRemotoExterno(PlataformaFreela.WORKANA);
        assertTrue(service.limiteRemotoAtivo(PlataformaFreela.WORKANA));

        // Dia virou: a data e LocalDate.now() dentro da implementacao, mas o reset
        // e disparado pelo dataUltimoReset da configuracao, que o teste controla.
        config.setDataUltimoReset(LocalDateTime.now().minusDays(1));

        service.verificarResetDiario();

        assertFalse(service.limiteRemotoAtivo(PlataformaFreela.WORKANA),
            "Reset diario local deve reabrir o circuito do limite remoto");
    }

    @Test
    void statusConexoesIndicaLimiteRemotoAtivo() {
        GerenciadorConexoesService service = service();
        String sessaoRenovavelAntes = statusCampo(service, "Sessao renovavel");
        assertFalse(service.getStatusConexoes().contains("limite remoto do marketplace ativo"));

        service.marcarLimiteRemotoExterno(PlataformaFreela.WORKANA);

        assertTrue(service.getStatusConexoes().contains("limite remoto do marketplace ativo"));
        assertEquals(sessaoRenovavelAntes, statusCampo(service, "Sessao renovavel"),
            "Flag remota nao deve alterar os campos existentes do status");
    }

    private GerenciadorConexoesService service() {
        return new GerenciadorConexoesServiceImpl(configuracaoRepository);
    }

    private String statusCampo(GerenciadorConexoesService service, String campo) {
        String status = service.getStatusConexoes();
        for (String parte : status.split("\\|")) {
            String item = parte.trim();
            if (item.startsWith(campo + ":")) {
                return item.substring((campo + ":").length()).trim();
            }
        }
        return null;
    }
}
