package com.freelasatende.service;

import com.freelasatende.model.dto.AvaliacaoResultado;
import com.freelasatende.model.entity.Cliente;
import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.service.impl.MarketplaceAdapterErrorType;
import com.freelasatende.service.impl.MarketplaceAdapterException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoAtendimentoFencingEDevolucaoTest extends AutoAtendimentoServiceImplTestSupport {

    @Test
    @DisplayName("Reserva expirada e tomada durante a avaliacao: a gravacao conclusiva da IA e descartada")
    void reservaTomadaDuranteAvaliacaoDescartaGravacao() {
        habilitarEscritaRealMockada();
        when(gerenciadorConexoesService.temConexoesDisponiveis()).thenReturn(true);
        Long projetoId = sequencia.incrementAndGet();
        when(avaliadorIaService.avaliarProjeto(any(Projeto.class))).thenAnswer(chamada -> {
            // Outra instancia toma a reivindicacao no meio da avaliacao (prazo vencido).
            projetoRepository.reivindicarProcessamento(projetoId, LocalDateTime.now().plusMinutes(1),
                LocalDateTime.now().plusSeconds(1));
            // Gravacao conclusiva feita pela avaliacao real: precisa ser recusada pelo fencing.
            historicoService.registrarInteracao(Interacao.builder().plataforma(PlataformaFreela.padrao())
                .projetoId(projetoId).projetoTitulo("Site").tipo(TipoInteracao.PROJETO_APROVADO)
                .conteudo("IA avaliou: SIM").build());
            return AvaliacaoResultado.builder().capaz(true).build();
        });

        autoAtendimentoService.processarProjeto(projetoBase(projetoId), ModoSistema.REGULAR);

        assertEquals(0, interacaoRepository.findByProjetoIdOrderByDataDesc(projetoId).size(),
            "Nada da execucao que perdeu a reserva pode ficar no historico");
        assertEquals(0, acaoPendenteRepository.count());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
    }

    @Test
    @DisplayName("Reserva tomada depois da confirmacao (durante a consulta do cliente): nada e enviado")
    void reservaTomadaAntesDoEnvioNaoEnvia() {
        habilitarEscritaRealMockada();
        when(gerenciadorConexoesService.temConexoesDisponiveis()).thenReturn(true);
        Long projetoId = sequencia.incrementAndGet();
        when(scraperService.buscarCliente(anyLong())).thenAnswer(chamada -> {
            // Outra instancia assume o projeto (prazo vencido) depois da confirmacao.
            projetoRepository.reivindicarProcessamento(projetoId, LocalDateTime.now().plusMinutes(1),
                LocalDateTime.now().plusSeconds(1));
            return Cliente.builder().id(7L).mediaNota(4.7).build();
        });

        autoAtendimentoService.processarProjeto(projetoBase(projetoId), ModoSistema.REGULAR);

        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        verify(gerenciadorConexoesService, never()).reservarConexao();
        assertEquals(0, acaoPendenteRepository.countByStatus(com.freelasatende.model.enums.StatusAcaoPendente.ERRO),
            "A acao da execucao que perdeu nao pode ficar como erro (bloquearia retentativas)");
    }

    @Test
    @DisplayName("Retomada concorrente depois da confirmacao: a execucao antiga nao cria pendencia nem notifica")
    void retomadaDepoisDaConfirmacaoAntigaNaoGravaPendencia() {
        // Modo com aprovacao humana: sem envio direto, a abordagem vira pendencia notificada.
        Long projetoId = sequencia.incrementAndGet();
        when(scraperService.buscarCliente(anyLong())).thenAnswer(chamada -> {
            // Esta execucao pausou depois de confirmar a posse; a reserva venceu e outra assumiu.
            projetoRepository.reivindicarProcessamento(projetoId, LocalDateTime.now().plusMinutes(1),
                LocalDateTime.now().plusSeconds(1));
            return Cliente.builder().id(7L).mediaNota(4.7).build();
        });

        autoAtendimentoService.processarProjeto(projetoBase(projetoId), ModoSistema.ENSINAMENTO);

        assertEquals(0, acaoPendenteRepository.count(), "A execucao antiga nao pode gravar pendencia (nem notificar)");
        assertEquals(0, interacaoRepository.findByProjetoIdOrderByDataDesc(projetoId).size());
        assertNotEquals(Boolean.TRUE, projetoRepository.findById(projetoId).orElseThrow().getFoiProcessado());
    }

    @Test
    @DisplayName("Envio com resultado incerto (adaptador devolve false) mantem a conexao reservada")
    void envioIncertoMantemConexaoReservada() {
        habilitarEscritaRealMockada();
        when(gerenciadorConexoesService.temConexoesDisponiveis()).thenReturn(true);
        Long projetoId = sequencia.incrementAndGet();
        when(scraperService.enviarPergunta(eq(projetoId), anyString())).thenReturn(false);

        autoAtendimentoService.processarProjeto(projetoBase(projetoId), ModoSistema.REGULAR);

        verify(gerenciadorConexoesService, never()).devolverConexao(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("Recusa de autenticacao antes do envio devolve a conexao reservada")
    void recusaAntesDoEnvioDevolveConexao() {
        habilitarEscritaRealMockada();
        when(gerenciadorConexoesService.temConexoesDisponiveis()).thenReturn(true);
        Long projetoId = sequencia.incrementAndGet();
        when(scraperService.enviarPergunta(eq(projetoId), anyString())).thenThrow(new MarketplaceAdapterException(
            PlataformaFreela.FREELAS_99, MarketplaceAdapterErrorType.FALHA_AUTENTICACAO, "HTTP 401"));

        autoAtendimentoService.processarProjeto(projetoBase(projetoId), ModoSistema.REGULAR);

        verify(gerenciadorConexoesService).devolverConexao(any(LocalDateTime.class));
    }

    private Projeto projetoBase(Long id) {
        Projeto projeto = projetoBase();
        projeto.setId(id);
        return projeto;
    }
}
