package com.freelasatende.scheduler;

import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.service.interfaceServico.AutoAtendimentoService;
import com.freelasatende.service.interfaceServico.TelegramService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PollingSchedulerErrorHandlerTest {

    @Test
    @DisplayName("Falha no Telegram nao propaga alerta de erros consecutivos")
    void falhaTelegramNaoPropagaAlertaErrosConsecutivos() {
        TelegramService telegramService = mock(TelegramService.class);
        PollingSchedulerErrorHandler handler = new PollingSchedulerErrorHandler(
            mock(InteracaoRepository.class),
            mock(AutoAtendimentoService.class),
            telegramService
        );
        doThrow(new RuntimeException("telegram indisponivel"))
            .when(telegramService).enviarNotificacao(anyString());

        assertDoesNotThrow(() -> handler.notificarErrosConsecutivos(3, 3));
    }

    @Test
    @DisplayName("Falha no Telegram nao silencia alerta Codex futuro")
    void falhaTelegramNaoSilenciaAlertaCodexFuturo() {
        TelegramService telegramService = mock(TelegramService.class);
        PollingSchedulerErrorHandler handler = new PollingSchedulerErrorHandler(
            mock(InteracaoRepository.class),
            mock(AutoAtendimentoService.class),
            telegramService
        );
        doThrow(new RuntimeException("telegram indisponivel"))
            .doNothing()
            .when(telegramService).enviarNotificacao(anyString());

        assertDoesNotThrow(() -> handler.notificarProblemaContaCodex(
            new IllegalStateException("CODEX_AUTH_INVALIDA: autenticacao expirada"),
            3600
        ));
        assertDoesNotThrow(() -> handler.notificarProblemaContaCodex(
            new IllegalStateException("CODEX_AUTH_INVALIDA: autenticacao expirada"),
            3600
        ));

        verify(telegramService, times(2)).enviarNotificacao(anyString());
    }
}
