package com.freelasatende.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixa que saldo esgotado no provedor principal libera a cadeia de fallback.
 *
 * <p>Caso real de producao (14/09): o provedor respondeu HTTP 400 com
 * "You have insufficient credits to make this request". Como 400 era fatal, a
 * cadeia abortava no primeiro modelo e o provedor secundario nunca era
 * tentado -- 1.775 avaliacoes falharam em 21h e nenhuma proposta saiu.</p>
 */
class IaSaldoEsgotadoTest {

    private static final String CORPO_PRODUCAO =
        "{\"error\":{\"message\":\"You have insufficient credits to make this request. "
        + "Please purchase more credits to continue using the service.\","
        + "\"type\":\"invalid_request_error\",\"code\":\"BAD_REQUEST\"}}";

    @Test
    @DisplayName("400 de credito esgotado e reconhecido como saldo esgotado")
    void creditoEsgotadoEhReconhecido() {
        assertTrue(OpenAiCompatibleProvider.ehSaldoEsgotado(CORPO_PRODUCAO));
    }

    @Test
    @DisplayName("saldo esgotado nao e confundido com credencial invalida")
    void saldoEsgotadoNaoEhFalhaDeCredencial() {
        assertFalse(OpenAiCompatibleProvider.ehFalhaDeCredencial(CORPO_PRODUCAO));
    }

    @Test
    @DisplayName("outras variacoes de cota esgotada tambem contam")
    void variacoesDeCota() {
        assertTrue(OpenAiCompatibleProvider.ehSaldoEsgotado(
            "{\"error\":{\"code\":\"insufficient_quota\"}}"));
        assertTrue(OpenAiCompatibleProvider.ehSaldoEsgotado(
            "You exceeded your current quota, please check your plan and billing details."));
        assertTrue(OpenAiCompatibleProvider.ehSaldoEsgotado("Insufficient Balance"));
    }

    @Test
    @DisplayName("erro comum de requisicao continua fatal")
    void erroComumNaoEhSaldoEsgotado() {
        assertFalse(OpenAiCompatibleProvider.ehSaldoEsgotado(null));
        assertFalse(OpenAiCompatibleProvider.ehSaldoEsgotado(""));
        assertFalse(OpenAiCompatibleProvider.ehSaldoEsgotado(
            "{\"error\":{\"message\":\"max_tokens is too large\"}}"));
    }
}
