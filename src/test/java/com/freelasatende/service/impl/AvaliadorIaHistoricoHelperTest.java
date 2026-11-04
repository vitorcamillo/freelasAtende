package com.freelasatende.service.impl;

import com.freelasatende.model.dto.AvaliacaoResultado;
import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.service.interfaceServico.HistoricoService;
import com.freelasatende.service.interfaceServico.IaProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AvaliadorIaHistoricoHelperTest {

    @Test
    @DisplayName("IA indisponivel registra erro tecnico sem concluir projeto")
    void iaIndisponivelRegistraErroTecnicoNaoConclusivo() {
        HistoricoService historicoService = mock(HistoricoService.class);
        IaProvider iaProvider = mock(IaProvider.class);
        when(iaProvider.getNome()).thenReturn("codex");
        when(iaProvider.getModelo()).thenReturn("gpt-5.4-mini");

        Projeto projeto = Projeto.builder()
            .id(758100L)
            .titulo("Projeto para reprocessar")
            .build();
        IaProvider.IaProviderException erro = new IaProvider.IaProviderException(
            "CODEX_CONTA_LIMITADA: limite ou creditos da conta Codex esgotados",
            429,
            false
        );

        AvaliacaoResultado resultado = AvaliadorIaHistoricoHelper.iaIndisponivel(
            historicoService,
            iaProvider,
            projeto,
            erro
        );

        ArgumentCaptor<Interacao> captor = ArgumentCaptor.forClass(Interacao.class);
        verify(historicoService).registrarInteracao(captor.capture());

        assertFalse(resultado.isCapaz());
        assertTrue(resultado.getMotivoRecusa().contains("IA temporariamente indisponivel"));
        assertEquals(TipoInteracao.ERRO_SISTEMA, captor.getValue().getTipo());
        assertTrue(captor.getValue().getConteudo().contains("HTTP 429"));
    }

    @Test
    @DisplayName("IA limitada persiste motivo curto sem resposta bruta do provedor")
    void iaLimitadaPersisteMotivoCurto() {
        HistoricoService historicoService = mock(HistoricoService.class);
        IaProvider iaProvider = mock(IaProvider.class);
        when(iaProvider.getNome()).thenReturn("openrouter");
        when(iaProvider.getModelo()).thenReturn("modelo-principal");
        Projeto projeto = Projeto.builder().id(758101L).titulo("Projeto limitado").build();
        IaProvider.IaProviderException erro = new IaProvider.IaProviderException("x".repeat(5_000), 429, true);

        AvaliacaoResultado resultado = AvaliadorIaHistoricoHelper.iaIndisponivel(
            historicoService, iaProvider, projeto, erro
        );

        assertTrue(resultado.getMotivoRecusa().contains("HTTP 429"));
        assertTrue(resultado.getMotivoRecusa().length() < 1_000);
        assertFalse(resultado.getMotivoRecusa().contains("xxxxx"));
    }
}
