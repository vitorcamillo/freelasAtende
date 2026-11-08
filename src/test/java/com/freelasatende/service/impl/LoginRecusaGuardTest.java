package com.freelasatende.service.impl;

import com.freelasatende.service.interfaceServico.TelegramService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Cobre o guarda de recusa do login 99Freelas: deteccao de mensagem de senha invalida,
 * bloqueio por impressao digital, notificacao unica e throttle do login assistido.
 */
class LoginRecusaGuardTest {

    private static final long MINUTO = 60_000L;

    @Test
    void mensagemIndicaSenhaInvalidaReconheceVariacoes() {
        assertTrue(LoginRecusaGuard.mensagemIndicaSenhaInvalida("A senha informada é inválida."));
        assertTrue(LoginRecusaGuard.mensagemIndicaSenhaInvalida("Senha incorreta"));
        assertFalse(LoginRecusaGuard.mensagemIndicaSenhaInvalida(null));
        assertFalse(LoginRecusaGuard.mensagemIndicaSenhaInvalida(""));
        assertFalse(LoginRecusaGuard.mensagemIndicaSenhaInvalida("Captcha inválido"));
        assertFalse(LoginRecusaGuard.mensagemIndicaSenhaInvalida("Muitas tentativas"));
    }

    @Test
    void registrarFalhaBloqueaSomenteCredenciaisRejeitadas() {
        LoginRecusaGuard guard = new LoginRecusaGuard();

        guard.registrarFalha("a@b.com", "senhaErrada", "A senha informada é inválida.", null);

        assertTrue(guard.bloqueada("a@b.com", "senhaErrada"));
        assertFalse(guard.bloqueada("a@b.com", "senhaNova"));
    }

    @Test
    void registrarFalhaDuasVezesNotificaSomenteUmaVez() {
        LoginRecusaGuard guard = new LoginRecusaGuard();
        TelegramService telegram = mock(TelegramService.class);

        guard.registrarFalha("a@b.com", "senhaErrada", "A senha informada é inválida.", telegram);
        guard.registrarFalha("a@b.com", "senhaErrada", "A senha informada é inválida.", telegram);

        verify(telegram, times(1)).enviarNotificacao(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void registrarFalhaComMensagemNaoRelacionadaNaoBloqueiaNemNotifica() {
        LoginRecusaGuard guard = new LoginRecusaGuard();
        TelegramService telegram = mock(TelegramService.class);

        guard.registrarFalha("a@b.com", "senhaErrada", "Muitas tentativas. Aguarde.", telegram);

        assertFalse(guard.bloqueada("a@b.com", "senhaErrada"));
        verify(telegram, never()).enviarNotificacao(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void limparRemoveOBloqueio() {
        LoginRecusaGuard guard = new LoginRecusaGuard();
        guard.registrarFalha("a@b.com", "senhaErrada", "Senha incorreta", null);
        assertTrue(guard.bloqueada("a@b.com", "senhaErrada"));

        guard.limpar();

        assertFalse(guard.bloqueada("a@b.com", "senhaErrada"));
    }

    @Test
    void notificacaoLoginAssistidoRespeitaThrottleDeSessentaMinutos() {
        LoginRecusaGuard guard = new LoginRecusaGuard();
        long agora = 1_000_000L;

        assertTrue(guard.deveNotificarLoginAssistido(agora));
        assertFalse(guard.deveNotificarLoginAssistido(agora + 10 * MINUTO));
        assertTrue(guard.deveNotificarLoginAssistido(agora + 61 * MINUTO));
    }
}
