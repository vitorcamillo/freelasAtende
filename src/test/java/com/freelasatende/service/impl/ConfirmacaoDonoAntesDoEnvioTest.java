package com.freelasatende.service.impl;

import com.freelasatende.policy.OperationalSafetyPolicy;
import com.freelasatende.repository.ProjetoRepository;
import com.freelasatende.service.interfaceServico.HistoricoService;
import com.freelasatende.service.interfaceServico.MensagemClienteGuardService;
import com.freelasatende.service.interfaceServico.QualidadeRascunhoService;
import com.freelasatende.service.interfaceServico.TelegramService;
import com.freelasatende.util.IaSafetyPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfirmacaoDonoAntesDoEnvioTest {

    private final ProjetoRepository projetoRepository = mock(ProjetoRepository.class);
    private final AutoAtendimentoActionHooks hooks = new AutoAtendimentoActionHooks(
        new MarketplaceAdapterRegistry(List.of(), "FREELAS_99"),
        mock(TelegramService.class),
        mock(HistoricoService.class),
        projetoRepository,
        mock(IaSafetyPolicy.class),
        new OperationalSafetyPolicy(),
        mock(MensagemClienteGuardService.class),
        mock(QualidadeRascunhoService.class),
        mock(TelegramMessageFormatter.class),
        true,
        false
    );

    @AfterEach
    void fechar() {
        ReservaDeProjeto.fechar();
    }

    @Test
    void zeroLinhasNaRenovacaoEPerdaDePosseMesmoComProjetoRemovido() {
        ReservaDeProjeto.abrir(10L, LocalDateTime.now());
        when(projetoRepository.renovarReivindicacao(eq(10L), any(), any())).thenReturn(0);
        when(projetoRepository.existsById(10L)).thenReturn(false);

        assertThrows(ReivindicacaoPerdidaException.class, () -> hooks.confirmarDonoAntesDoEnvio(10L));
    }

    @Test
    void semReservaNaThreadNaoConsultaOBanco() {
        assertDoesNotThrow(() -> hooks.confirmarDonoAntesDoEnvio(11L));
        verify(projetoRepository, never()).renovarReivindicacao(anyLong(), any(), any());
    }
}
