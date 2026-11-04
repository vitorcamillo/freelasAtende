package com.freelasatende.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerguntaClienteFormatterTest {

    @Test
    @DisplayName("Pergunta inicial qualifica escopo sem fechar proposta cedo")
    void perguntaInicialQualificaEscopoSemFecharPropostaCedo() {
        String texto = PerguntaClienteFormatter.montarPerguntaParaCliente(List.of(
            "O que existe hoje no sistema legado?",
            "Qual parte precisa entrar na primeira entrega?",
            "Precisa integrar com Supabase Storage?"
        ));

        assertTrue(texto.contains("O que existe hoje no sistema legado?"));
        assertTrue(texto.contains("qual parte precisa entrar na primeira entrega?"));
        assertTrue(texto.contains("precisa integrar com Supabase Storage?"));
        assertFalse(texto.toLowerCase().contains("monto a proposta"));
        assertFalse(texto.toLowerCase().contains("passar uma proposta"));
        assertFalse(texto.toLowerCase().contains("prazo e valor"));
        assertFalse(texto.toLowerCase().contains("próxima etapa"));
    }

    @Test
    @DisplayName("Duvida de valor vira pergunta de escopo da primeira entrega")
    void duvidaDeValorViraPerguntaDeEscopoDaPrimeiraEntrega() {
        String texto = PerguntaClienteFormatter.montarPerguntaParaCliente(List.of(
            "Qual faixa de valor você tem disponível?"
        ));

        assertTrue(texto.contains("escopo principal"));
        assertTrue(texto.contains("primeira entrega"));
        assertFalse(texto.toLowerCase().contains("valor disponível"));
        assertFalse(texto.toLowerCase().contains("faixa de valor"));
    }
}
