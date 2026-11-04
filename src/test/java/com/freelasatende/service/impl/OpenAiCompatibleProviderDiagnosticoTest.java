package com.freelasatende.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrato de diagnostico do transporte de IA: apos o incidente de 15/09/2026
 * (card com "Erro ao comunicar com openai: null" por DNS instavel no container),
 * a mensagem de falha precisa sempre identificar a classe do erro e a causa
 * raiz, e falha de DNS precisa ser reconhecida para retry.
 */
class OpenAiCompatibleProviderDiagnosticoTest {

    @Test
    @DisplayName("ehFalhaDeDns deve detectar UnresolvedAddressException em qualquer nivel da causa")
    void deveDetectarFalhaDeDnsNaCausa() {
        IOException transporte = new IOException();
        transporte.initCause(new java.nio.channels.UnresolvedAddressException());

        assertAll(
            () -> assertTrue(OpenAiCompatibleProvider.ehFalhaDeDns(transporte)),
            () -> assertTrue(OpenAiCompatibleProvider.ehFalhaDeDns(new java.net.UnknownHostException("api.exemplo"))),
            () -> assertFalse(OpenAiCompatibleProvider.ehFalhaDeDns(new IOException("conexao recusada"))),
            () -> assertFalse(OpenAiCompatibleProvider.ehFalhaDeDns(null))
        );
    }

    @Test
    @DisplayName("descreverCausa deve produzir mensagem utilizavel quando getMessage() e nulo")
    void deveDescreverCausaComMensagemNula() {
        IOException transporte = new IOException();
        transporte.initCause(new java.nio.channels.UnresolvedAddressException());

        String descricao = OpenAiCompatibleProvider.descreverCausa(transporte);

        assertAll(
            () -> assertTrue(descricao.contains("IOException"), "deve conter a classe do erro: " + descricao),
            () -> assertTrue(descricao.contains("UnresolvedAddressException"), "deve conter a causa raiz: " + descricao)
        );
    }

    @Test
    @DisplayName("ehRespostaInutilizavel deve rejeitar saida de classificador de seguranca e vazia")
    void deveRejeitarSaidaDeClassificador() {
        assertAll(
            () -> assertTrue(OpenAiCompatibleProvider.ehRespostaInutilizavel("User Safety: safe")),
            () -> assertTrue(OpenAiCompatibleProvider.ehRespostaInutilizavel("User Safety: safe\nResponse Safety: safe")),
            () -> assertTrue(OpenAiCompatibleProvider.ehRespostaInutilizavel("  user safety: UNSAFE\nSafety Categories: Violence")),
            () -> assertTrue(OpenAiCompatibleProvider.ehRespostaInutilizavel("safe")),
            () -> assertTrue(OpenAiCompatibleProvider.ehRespostaInutilizavel("unsafe\nS1,S10")),
            () -> assertTrue(OpenAiCompatibleProvider.ehRespostaInutilizavel("")),
            () -> assertTrue(OpenAiCompatibleProvider.ehRespostaInutilizavel(null))
        );
    }

    @Test
    @DisplayName("ehRespostaInutilizavel deve aceitar texto gerado normal, inclusive com a palavra safe")
    void deveAceitarTextoNormal() {
        assertAll(
            () -> assertFalse(OpenAiCompatibleProvider.ehRespostaInutilizavel("SIM")),
            () -> assertFalse(OpenAiCompatibleProvider.ehRespostaInutilizavel("NAO")),
            () -> assertFalse(OpenAiCompatibleProvider.ehRespostaInutilizavel(
                "Bom dia! Consigo automatizar o Instagram e o WhatsApp de forma safe e integrada.")),
            () -> assertFalse(OpenAiCompatibleProvider.ehRespostaInutilizavel("Safe deploy feito. User Safety: safe"))
        );
    }
}
