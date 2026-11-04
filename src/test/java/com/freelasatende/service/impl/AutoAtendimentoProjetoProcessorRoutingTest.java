package com.freelasatende.service.impl;

import com.freelasatende.model.dto.AvaliacaoResultado;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.policy.OperationalSafetyPolicy;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.repository.ProjetoRepository;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import com.freelasatende.service.interfaceServico.MarketplaceCapabilities;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoAtendimentoProjetoProcessorRoutingTest {

    @Test
    void plataformaSemPerguntaMasComPropostaVaiDiretoParaCandidaturaPendente() throws Exception {
        MarketplaceAdapter adapter = mock(MarketplaceAdapter.class);
        when(adapter.plataforma()).thenReturn(PlataformaFreela.UPWORK);
        when(adapter.capabilities()).thenReturn(new MarketplaceCapabilities(
            true, true, false, true, false, false, false
        ));
        AvaliadorIaService avaliador = mock(AvaliadorIaService.class);
        when(avaliador.avaliarProjeto(org.mockito.ArgumentMatchers.any())).thenReturn(
            AvaliacaoResultado.builder().capaz(true).build()
        );
        AutoAtendimentoProjetoProcessor.Hooks hooks = mock(AutoAtendimentoProjetoProcessor.Hooks.class);
        Projeto projeto = Projeto.builder().id(PlataformaFreela.UPWORK.idInterno(10L)).idExterno(10L)
            .plataforma(PlataformaFreela.UPWORK).titulo("API Python").foiProcessado(false).build();
        when(hooks.enriquecerProjeto(projeto)).thenReturn(projeto);
        AutoAtendimentoProjetoProcessor processor = processor(adapter, avaliador, hooks);

        processor.processarProjetoInterno(projeto, ModoSistema.REGULAR, false);

        verify(hooks).criarPropostaProjeto(projeto, ModoSistema.REGULAR);
        verify(hooks, never()).criarAbordagemInicialProjeto(projeto, ModoSistema.REGULAR);
    }

    @Test
    void plataformaComPerguntaMantemAbordagemInicial() throws Exception {
        MarketplaceAdapter adapter = mock(MarketplaceAdapter.class);
        when(adapter.plataforma()).thenReturn(PlataformaFreela.WORKANA);
        when(adapter.capabilities()).thenReturn(new MarketplaceCapabilities(
            true, true, true, true, false, false, false
        ));
        AvaliadorIaService avaliador = mock(AvaliadorIaService.class);
        when(avaliador.avaliarProjeto(org.mockito.ArgumentMatchers.any())).thenReturn(
            AvaliacaoResultado.builder().capaz(true).build()
        );
        AutoAtendimentoProjetoProcessor.Hooks hooks = mock(AutoAtendimentoProjetoProcessor.Hooks.class);
        Projeto projeto = Projeto.builder().id(PlataformaFreela.WORKANA.idInterno(20L)).idExterno(20L)
            .plataforma(PlataformaFreela.WORKANA).titulo("Landing page").foiProcessado(false).build();
        when(hooks.enriquecerProjeto(projeto)).thenReturn(projeto);
        AutoAtendimentoProjetoProcessor processor = processor(adapter, avaliador, hooks);

        processor.processarProjetoInterno(projeto, ModoSistema.REGULAR, false);

        verify(hooks).criarAbordagemInicialProjeto(projeto, ModoSistema.REGULAR);
        verify(hooks, never()).criarPropostaProjeto(projeto, ModoSistema.REGULAR);
    }

    private AutoAtendimentoProjetoProcessor processor(
        MarketplaceAdapter adapter,
        AvaliadorIaService avaliador,
        AutoAtendimentoProjetoProcessor.Hooks hooks
    ) {
        return new AutoAtendimentoProjetoProcessor(
            avaliador,
            new MarketplaceAdapterRegistry(List.of(adapter), adapter.plataforma().key()),
            mock(ProjetoRepository.class),
            mock(InteracaoRepository.class),
            mock(AcaoPendenteRepository.class),
            mock(GerenciadorConexoesService.class),
            new OperationalSafetyPolicy(),
            mock(SystemHealthTracker.class),
            hooks,
            false,
            false
        );
    }
}
