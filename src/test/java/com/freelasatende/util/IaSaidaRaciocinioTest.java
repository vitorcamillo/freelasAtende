package com.freelasatende.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes unitarios para IaSaidaRaciocinio, sem dependencias externas.
 */
class IaSaidaRaciocinioTest {

    @Test
    @DisplayName("Deve remover bloco think fechado e manter a mensagem final")
    void deveRemoverBlocoThinkFechado() {
        assertEquals("Oi, tudo bem?", IaSaidaRaciocinio.removerBlocos("<think>analise</think>\nOi, tudo bem?"));
    }

    @Test
    @DisplayName("Deve remover bloco thinking fechado sem diferenciar maiusculas")
    void deveRemoverBlocoThinkingMaiusculo() {
        assertEquals("Resposta", IaSaidaRaciocinio.removerBlocos("<THINKING>x\ny</THINKING> Resposta"));
    }

    @Test
    @DisplayName("Deve descartar tudo ate o ultimo fechamento orfao")
    void deveDescartarAteFechamentoOrfao() {
        assertEquals("Mensagem final", IaSaidaRaciocinio.removerBlocos("raciocinio solto</think>Mensagem final"));
    }

    @Test
    @DisplayName("Deve descartar abertura sem fechamento (texto cortado)")
    void deveDescartarAberturaSemFechamento() {
        assertEquals("", IaSaidaRaciocinio.removerBlocos("<think>cortado no meio sem fechar"));
    }

    @Test
    @DisplayName("Deve preservar texto sem tags (apenas trim)")
    void devePreservarTextoSemTags() {
        assertEquals("Texto normal", IaSaidaRaciocinio.removerBlocos("  Texto normal  "));
    }

    @Test
    @DisplayName("Deve retornar null para entrada null")
    void deveRetornarNullParaEntradaNull() {
        assertNull(IaSaidaRaciocinio.removerBlocos(null));
    }

    @Test
    @DisplayName("Deve detectar prosa de raciocinio em ingles e portugues")
    void deveDetectarProsaDeRaciocinio() {
        assertTrue(IaSaidaRaciocinio.pareceRaciocinio("Okay, the client is asking about price."));
        assertTrue(IaSaidaRaciocinio.pareceRaciocinio("We need to reply in Portuguese."));
        assertTrue(IaSaidaRaciocinio.pareceRaciocinio("Let me think about this."));
        assertTrue(IaSaidaRaciocinio.pareceRaciocinio("O cliente esta perguntando o prazo, entao devo..."));
        assertTrue(IaSaidaRaciocinio.pareceRaciocinio("Here is a draft. The user wants a short reply."));
    }

    @Test
    @DisplayName("Nao deve marcar mensagem normal ao cliente como raciocinio")
    void naoDeveMarcarMensagemNormalComoRaciocinio() {
        assertFalse(IaSaidaRaciocinio.pareceRaciocinio("Ok, entendi o que voce precisa. Pode me mandar o layout?"));
        assertFalse(IaSaidaRaciocinio.pareceRaciocinio("Oi! Vi seu projeto de site e tenho uma duvida sobre o login."));
        assertFalse(IaSaidaRaciocinio.pareceRaciocinio("Hello! Thanks for the details."));
        assertFalse(IaSaidaRaciocinio.pareceRaciocinio("Vou analisar o layout e te retorno ainda hoje."));
        assertFalse(IaSaidaRaciocinio.pareceRaciocinio("Analisando seu projeto, vi que o login usa Firebase."));
        assertFalse(IaSaidaRaciocinio.pareceRaciocinio("Pensando no seu caso, da para comecar pelo app."));
        assertFalse(IaSaidaRaciocinio.pareceRaciocinio("Let's schedule a call to align the details."));
        assertFalse(IaSaidaRaciocinio.pareceRaciocinio("The user is redirected to login after the session expires."));
        assertFalse(IaSaidaRaciocinio.pareceRaciocinio("When the token expires, the user is redirected to the login page."));
        assertFalse(IaSaidaRaciocinio.pareceRaciocinio("Quando o cliente esta no checkout, exibimos o resumo do pedido."));
        assertFalse(IaSaidaRaciocinio.pareceRaciocinio("A resposta deve vir em JSON com os campos do pedido."));
        assertFalse(IaSaidaRaciocinio.pareceRaciocinio("Quando o cliente esta no checkout, a resposta deve vir em JSON."));
        assertFalse(IaSaidaRaciocinio.pareceRaciocinio("Okay, posso fazer sim"));
        assertFalse(IaSaidaRaciocinio.pareceRaciocinio(null));
        assertFalse(IaSaidaRaciocinio.pareceRaciocinio(""));
    }
}
