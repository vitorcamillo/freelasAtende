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

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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

    @Test
    void limiteRemotoDeConexoesMarcaCircuitoComCapabilityRealDoWorkana() throws Exception {
        // Capability REAL do adapter Workana (conexoes=false): o envio nao passa pelo
        // gate local de reserva — a marcacao do circuito nao pode depender dele.
        MarketplaceCapabilities capabilitiesWorkana = new WorkanaMarketplaceAdapter(
            mock(WorkanaClient.class), mock(WorkanaProjectParser.class), mock(WorkanaProposalClient.class)
        ).capabilities();
        org.junit.jupiter.api.Assertions.assertFalse(capabilitiesWorkana.conexoes(),
            "Premissa do teste: o Workana real nao consome conexao local");

        MarketplaceAdapter adapter = mock(MarketplaceAdapter.class);
        when(adapter.plataforma()).thenReturn(PlataformaFreela.WORKANA);
        when(adapter.capabilities()).thenReturn(capabilitiesWorkana);
        doThrow(new MarketplaceAdapterException(
            PlataformaFreela.WORKANA,
            MarketplaceAdapterErrorType.LIMITE_CONEXOES,
            "Poxa, voce nao tem mais conexoes disponiveis"
        )).when(adapter).enviarPergunta(any(Projeto.class), eq("Ja foi atendido?"));

        GerenciadorConexoesService gerenciadorConexoes = mock(GerenciadorConexoesService.class);
        AutoAtendimentoActionExecutor executor = executor(adapter, gerenciadorConexoes);
        AcaoPendente acao = acaoPerguntaWorkana();

        org.junit.jupiter.api.Assertions.assertThrows(MarketplaceAdapterException.class,
            () -> executor.executar(acao));

        // Nada foi enviado e o marketplace recusou por limite: o circuito remoto do
        // Workana abre ate o reset diario, sem reserva local para devolver.
        verify(gerenciadorConexoes).marcarLimiteRemotoExterno(PlataformaFreela.WORKANA);
        verify(gerenciadorConexoes, never()).reservarConexao();
        verify(gerenciadorConexoes, never()).devolverConexao(any(LocalDateTime.class));
    }

    @Test
    void falhaDeRedeNaoMarcaLimiteRemoto() throws Exception {
        MarketplaceAdapter adapter = mock(MarketplaceAdapter.class);
        when(adapter.plataforma()).thenReturn(PlataformaFreela.WORKANA);
        when(adapter.capabilities()).thenReturn(new MarketplaceCapabilities(true, false, true, false, false, false, false));
        doThrow(new MarketplaceAdapterException(
            PlataformaFreela.WORKANA,
            MarketplaceAdapterErrorType.FALHA_REDE,
            "timeout no POST do form de pergunta"
        )).when(adapter).enviarPergunta(any(Projeto.class), eq("Ja foi atendido?"));

        GerenciadorConexoesService gerenciadorConexoes = mock(GerenciadorConexoesService.class);
        AutoAtendimentoActionExecutor executor = executor(adapter, gerenciadorConexoes);
        AcaoPendente acao = acaoPerguntaWorkana();

        org.junit.jupiter.api.Assertions.assertThrows(MarketplaceAdapterException.class,
            () -> executor.executar(acao));

        verify(gerenciadorConexoes, never()).marcarLimiteRemotoExterno(any(PlataformaFreela.class));
    }

    private AutoAtendimentoActionExecutor executor(MarketplaceAdapter adapter, GerenciadorConexoesService gerenciadorConexoes) {
        AutoAtendimentoActionExecutor.Hooks hooks = mock(AutoAtendimentoActionExecutor.Hooks.class);
        when(hooks.deveSimularEnvioReal()).thenReturn(false);
        return new AutoAtendimentoActionExecutor(
            objectMapper,
            new OperationalSafetyPolicy(),
            new MarketplaceAdapterRegistry(List.of(adapter), "WORKANA"),
            gerenciadorConexoes,
            hooks,
            true,
            false
        );
    }

    private AcaoPendente acaoPerguntaWorkana() throws Exception {
        PerguntaPayload payload = PerguntaPayload.builder()
            .plataforma(PlataformaFreela.WORKANA)
            .projetoId(1001L)
            .projetoIdExterno(2002L)
            .tituloProjeto("Projeto Workana")
            .urlProjeto("https://www.workana.com/job/projeto-workana")
            .mensagem("Ja foi atendido?")
            .build();
        return AcaoPendente.builder()
            .plataforma(PlataformaFreela.WORKANA)
            .projetoId(1001L)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.APROVADA)
            .modoOrigem(ModoSistema.LIVRE)
            .payloadJson(objectMapper.writeValueAsString(payload))
            .build();
    }
}
