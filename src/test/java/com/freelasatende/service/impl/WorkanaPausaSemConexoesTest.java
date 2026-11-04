package com.freelasatende.service.impl;

import com.freelasatende.model.dto.AvaliacaoResultado;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.policy.OperationalSafetyPolicy;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.repository.ProjetoRepository;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import com.freelasatende.service.interfaceServico.MarketplaceCapabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O limite de conexoes da Workana e semanal. Antes, qualquer negacao usava o mesmo cooldown
 * de 6h, entao o sistema voltava a tentar cinco vezes por dia sabendo que ia falhar.
 */
class WorkanaPausaSemConexoesTest {

    private static final String RESUMO = "Abordagem inicial automatica | projeto";
    private static final String OBS_SEM_CONEXOES =
        "Envio automatico nao executado por limite semanal de conexoes do Workana. "
            + "Nao retentar automaticamente este projeto para nao gastar conexoes quando o limite renovar.";
    private static final String OBS_SESSAO =
        "Envio automatico nao executado por falha de sessao/autorizacao do marketplace. "
            + "Nao criar pendencia de aprovacao para abordagem inicial automatica.";

    @Test
    @DisplayName("Sem conexoes: 24h apos a falha ainda esta pausado (cooldown semanal)")
    void semConexoesSeguePausadoDepoisDeUmDia() throws Exception {
        AutoAtendimentoProjetoProcessor.Hooks hooks =
            processarComUltimaFalha(OBS_SEM_CONEXOES, LocalDateTime.now().minusHours(24));

        verify(hooks, never()).criarAbordagemInicialProjeto(any(), any());
    }

    @Test
    @DisplayName("Sem conexoes: passada a semana, volta a tentar")
    void semConexoesVoltaAtentarDepoisDaSemana() throws Exception {
        AutoAtendimentoProjetoProcessor.Hooks hooks =
            processarComUltimaFalha(OBS_SEM_CONEXOES, LocalDateTime.now().minusDays(8));

        verify(hooks).criarAbordagemInicialProjeto(any(), any());
    }

    @Test
    @DisplayName("Falha de sessao mantem o cooldown curto de 6h")
    void falhaDeSessaoMantemCooldownCurto() throws Exception {
        AutoAtendimentoProjetoProcessor.Hooks pausado =
            processarComUltimaFalha(OBS_SESSAO, LocalDateTime.now().minusHours(2));
        verify(pausado, never()).criarAbordagemInicialProjeto(any(), any());

        AutoAtendimentoProjetoProcessor.Hooks liberado =
            processarComUltimaFalha(OBS_SESSAO, LocalDateTime.now().minusHours(7));
        verify(liberado).criarAbordagemInicialProjeto(any(), any());
    }

    private AutoAtendimentoProjetoProcessor.Hooks processarComUltimaFalha(
        String observacao,
        LocalDateTime quando
    ) throws Exception {
        MarketplaceAdapter adapter = mock(MarketplaceAdapter.class);
        when(adapter.plataforma()).thenReturn(PlataformaFreela.WORKANA);
        when(adapter.capabilities()).thenReturn(new MarketplaceCapabilities(
            true, true, true, true, false, false, false
        ));

        AvaliadorIaService avaliador = mock(AvaliadorIaService.class);
        when(avaliador.avaliarProjeto(any())).thenReturn(AvaliacaoResultado.builder().capaz(true).build());

        AcaoPendente falha = new AcaoPendente();
        falha.setId(1L);
        falha.setResumo(RESUMO);
        falha.setObservacaoUsuario(observacao);
        falha.setDataAtualizacao(quando);

        AcaoPendenteRepository acoes = mock(AcaoPendenteRepository.class);
        when(acoes.findFirstByPlataformaAndTipoAndStatusOrderByDataAtualizacaoDesc(
            PlataformaFreela.WORKANA, TipoAcaoPendente.ENVIAR_PERGUNTA, StatusAcaoPendente.ERRO
        )).thenReturn(Optional.of(falha));

        AutoAtendimentoProjetoProcessor.Hooks hooks = mock(AutoAtendimentoProjetoProcessor.Hooks.class);
        Projeto projeto = Projeto.builder()
            .id(PlataformaFreela.WORKANA.idInterno(42L))
            .idExterno(42L)
            .plataforma(PlataformaFreela.WORKANA)
            .titulo("Automacao")
            .foiProcessado(false)
            .build();
        when(hooks.enriquecerProjeto(projeto)).thenReturn(projeto);

        GerenciadorConexoesService conexoes = mock(GerenciadorConexoesService.class);
        when(conexoes.temConexoesDisponiveis()).thenReturn(true);

        AutoAtendimentoProjetoProcessor processor = new AutoAtendimentoProjetoProcessor(
            avaliador,
            new MarketplaceAdapterRegistry(List.of(adapter), adapter.plataforma().key()),
            mock(ProjetoRepository.class),
            mock(InteracaoRepository.class),
            acoes,
            conexoes,
            new OperationalSafetyPolicy(),
            mock(SystemHealthTracker.class),
            hooks,
            true,
            true
        );

        processor.processarProjetoInterno(projeto, ModoSistema.REGULAR, false);
        return hooks;
    }
}
