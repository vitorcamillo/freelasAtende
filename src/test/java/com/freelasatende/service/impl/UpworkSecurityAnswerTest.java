package com.freelasatende.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UpworkSecurityAnswerTest {

    @Test
    void respostaUnicaSemMapeamento() {
        assertEquals("Teta",
            UpworkLoginDriverless.escolherResposta("Teta", "your best friend's nickname"));
    }

    @Test
    void escolhePorTrechoDaPergunta() {
        String cfg = "best friend=Teta;first pet=Rex";
        assertEquals("Teta",
            UpworkLoginDriverless.escolherResposta(cfg, "your best friend's nickname"));
        assertEquals("Rex",
            UpworkLoginDriverless.escolherResposta(cfg, "what was your first pet's name"));
    }

    @Test
    void usaFallbackQuandoNenhumTrechoCasa() {
        String cfg = "best friend=Teta;first pet=Rex";
        // pergunta desconhecida -> primeira resposta como fallback
        assertEquals("Teta",
            UpworkLoginDriverless.escolherResposta(cfg, "your favorite teacher"));
    }

    @Test
    void retornaNullParaConfigVazia() {
        assertNull(UpworkLoginDriverless.escolherResposta("", "qualquer"));
        assertNull(UpworkLoginDriverless.escolherResposta(null, "qualquer"));
    }
}
