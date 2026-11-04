package com.freelasatende.scheduler;

import com.freelasatende.model.dto.AcaoControleDTO;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.service.interfaceServico.ControleOperacionalService;
import com.freelasatende.service.interfaceServico.TelegramService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PollingSchedulerOperatorNotifierTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Falha no Telegram nao derruba diario nem marca enviado")
    void falhaTelegramNaoDerrubaDiarioNemMarcaEnviado() {
        TelegramService telegramService = mock(TelegramService.class);
        Path diario = tempDir.resolve("diario.txt");
        PollingSchedulerOperatorNotifier notifier = new PollingSchedulerOperatorNotifier(
            telegramService,
            mock(ControleOperacionalService.class),
            true,
            diario.toString(),
            false,
            60,
            tempDir.resolve("checkin.txt").toString(),
            false,
            false
        );
        doThrow(new RuntimeException("telegram indisponivel"))
            .doNothing()
            .when(telegramService).enviarNotificacao(anyString());

        assertDoesNotThrow(() -> notifier.notificarDiarioOperacional(2, 1, 2, ModoSistema.ENSINAMENTO));
        assertFalse(Files.exists(diario));
        assertDoesNotThrow(() -> notifier.notificarDiarioOperacional(2, 1, 2, ModoSistema.ENSINAMENTO));

        verify(telegramService, times(2)).enviarNotificacao(anyString());
    }

    @Test
    @DisplayName("Falha no Telegram nao arma cooldown falso no check-in")
    void falhaTelegramNaoArmaCooldownFalsoNoCheckin() {
        TelegramService telegramService = mock(TelegramService.class);
        ControleOperacionalService controle = mock(ControleOperacionalService.class);
        AcaoControleDTO acao = AcaoControleDTO.builder()
            .id(17L)
            .tituloProjeto("Projeto")
            .perguntaParaUsuario("Qual detalhe falta?")
            .focoEnsinoAtual(true)
            .build();
        when(controle.listarAcoesPendentes()).thenReturn(List.of(acao));
        PollingSchedulerOperatorNotifier notifier = new PollingSchedulerOperatorNotifier(
            telegramService,
            controle,
            false,
            tempDir.resolve("diario.txt").toString(),
            true,
            60,
            tempDir.resolve("checkin.txt").toString(),
            false,
            false
        );
        when(telegramService.enviarAcaoPendenteComBotoes(anyString(), eq(17L)))
            .thenThrow(new RuntimeException("telegram indisponivel"))
            .thenReturn(123L);

        assertDoesNotThrow(() -> notifier.notificarCheckinEnsino(0, 1, 1, ModoSistema.ENSINAMENTO));
        assertDoesNotThrow(() -> notifier.notificarCheckinEnsino(0, 1, 1, ModoSistema.ENSINAMENTO));

        verify(telegramService, times(2)).enviarAcaoPendenteComBotoes(anyString(), eq(17L));
    }

    @Test
    @DisplayName("Check-in de ensino nao repete a mesma acao apos envio com sucesso")
    void checkinEnsinoNaoRepeteMesmaAcao() {
        TelegramService telegramService = mock(TelegramService.class);
        ControleOperacionalService controle = mock(ControleOperacionalService.class);
        AcaoControleDTO acao = AcaoControleDTO.builder()
            .id(7932L)
            .tituloProjeto("Desenvolvimento do Portal ICAR Conectada")
            .perguntaParaUsuario("O que ainda preciso saber?")
            .focoEnsinoAtual(true)
            .build();
        when(controle.listarAcoesPendentes()).thenReturn(List.of(acao));
        PollingSchedulerOperatorNotifier notifier = new PollingSchedulerOperatorNotifier(
            telegramService,
            controle,
            false,
            tempDir.resolve("diario.txt").toString(),
            true,
            1,
            tempDir.resolve("checkin.txt").toString(),
            false,
            false
        );
        when(telegramService.enviarAcaoPendenteComBotoes(anyString(), eq(7932L))).thenReturn(123L);

        notifier.notificarCheckinEnsino(50, 85, 85, ModoSistema.ENSINAMENTO);
        notifier.notificarCheckinEnsino(50, 85, 85, ModoSistema.ENSINAMENTO);

        ArgumentCaptor<String> mensagem = ArgumentCaptor.forClass(String.class);
        verify(telegramService, times(1)).enviarAcaoPendenteComBotoes(mensagem.capture(), eq(7932L));
        assertTrue(mensagem.getValue().contains("Pergunta do junior"));
        assertTrue(mensagem.getValue().contains("```\nDesenvolvimento do Portal ICAR Conectada\n```"));
        assertTrue(mensagem.getValue().contains("O que ainda preciso saber:\n```"));
    }
}
