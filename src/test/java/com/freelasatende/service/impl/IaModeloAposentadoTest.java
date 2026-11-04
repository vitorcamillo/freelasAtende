package com.freelasatende.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixa a distincao entre os dois tipos de HTTP 403 devolvidos por provedores
 * compativeis com a API da OpenAI.
 *
 * <p>Caso real de producao: o provedor passou a responder 403 com "The free
 * MiniMax M3 and M2.7 models have been retired" para um modelo da cadeia de
 * fallback. Como todo 403 era tratado como nao-retriavel, a cadeia inteira
 * abortava e o provedor secundario nunca era tentado -- 225 erros em 4h e
 * projetos passando sem avaliacao. Modelo fora do ar deve pular para o proximo;
 * credencial invalida continua fatal.</p>
 */
class IaModeloAposentadoTest {

    @Test
    @DisplayName("403 de modelo aposentado nao e falha de credencial")
    void modeloAposentadoNaoEhFalhaDeCredencial() {
        String corpo = "{\"error\":{\"message\":\"The free MiniMax M3 and M2.7 models have been "
            + "retired. Run /model and pick MiniMax M3 or MiniMax M2.7 to keep going.\","
            + "\"type\":\"permission_error\",\"code\":\"FORBIDDEN\"}}";
        assertFalse(OpenAiCompatibleProvider.ehFalhaDeCredencial(corpo));
    }

    @Test
    @DisplayName("403 de chave invalida e falha de credencial")
    void chaveInvalidaEhFalhaDeCredencial() {
        assertTrue(OpenAiCompatibleProvider.ehFalhaDeCredencial(
            "{\"error\":{\"message\":\"Invalid API key provided\"}}"));
        assertTrue(OpenAiCompatibleProvider.ehFalhaDeCredencial(
            "{\"error\":{\"message\":\"No auth credentials found\"}}"));
        assertTrue(OpenAiCompatibleProvider.ehFalhaDeCredencial(
            "{\"error\":{\"message\":\"Unauthorized\"}}"));
    }

    @Test
    @DisplayName("corpo vazio ou nulo nao e falha de credencial")
    void corpoVazioNaoEhFalhaDeCredencial() {
        assertFalse(OpenAiCompatibleProvider.ehFalhaDeCredencial(null));
        assertFalse(OpenAiCompatibleProvider.ehFalhaDeCredencial(""));
        assertFalse(OpenAiCompatibleProvider.ehFalhaDeCredencial("   "));
    }
}
