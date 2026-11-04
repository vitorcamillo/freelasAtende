package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.policy.OperationalSafetyPolicy;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import com.freelasatende.service.interfaceServico.MarketplaceCapabilities;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoAtendimentoActionExecutorWorkanaTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void perguntaWorkanaUsaCoreUnicoSemGastarConexao() throws Exception {
        MarketplaceAdapter adapter = mock(MarketplaceAdapter.class);
        when(adapter.plataforma()).thenReturn(PlataformaFreela.WORKANA);
        when(adapter.capabilities()).thenReturn(new MarketplaceCapabilities(true, false, true, false, false, false, false));
        when(adapter.enviarPergunta(any(Projeto.class), eq("Ja foi atendido?"))).thenReturn(true);

        GerenciadorConexoesService gerenciadorConexoes = mock(GerenciadorConexoesService.class);
        AutoAtendimentoActionExecutor.Hooks hooks = mock(AutoAtendimentoActionExecutor.Hooks.class);
        when(hooks.deveSimularEnvioReal()).thenReturn(false);

        AutoAtendimentoActionExecutor executor = new AutoAtendimentoActionExecutor(
            objectMapper,
            new OperationalSafetyPolicy(),
            new MarketplaceAdapterRegistry(List.of(adapter), "WORKANA"),
            gerenciadorConexoes,
            hooks,
            true,
            false
        );

        PerguntaPayload payload = PerguntaPayload.builder()
            .plataforma(PlataformaFreela.WORKANA)
            .projetoId(1001L)
            .projetoIdExterno(2002L)
            .tituloProjeto("Projeto Workana")
            .urlProjeto("https://www.workana.com/job/projeto-workana")
            .mensagem("Ja foi atendido?")
            .build();
        AcaoPendente acao = AcaoPendente.builder()
            .plataforma(PlataformaFreela.WORKANA)
            .projetoId(1001L)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.APROVADA)
            .modoOrigem(ModoSistema.LIVRE)
            .payloadJson(objectMapper.writeValueAsString(payload))
            .build();

        executor.executar(acao);

        verify(adapter).enviarPergunta(any(Projeto.class), eq("Ja foi atendido?"));
        verify(gerenciadorConexoes, never()).registrarUsoConexao();
    }
}
