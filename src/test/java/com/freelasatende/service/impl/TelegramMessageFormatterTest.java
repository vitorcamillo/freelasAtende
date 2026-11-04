package com.freelasatende.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramMessageFormatterTest {

    private final TelegramMessageFormatter formatter = new TelegramMessageFormatter();

    @Test
    void textRemoveCaracteresQueQuebramMarkdown() {
        assertEquals("A B (ok) / caminho", formatter.text("A_B [ok] \\ caminho"));
    }

    @Test
    void textDecodificaEntidadesHtmlDaPlataforma() {
        assertEquals("prospecção ativa", formatter.text("prospec&ccedil;&atilde;o ativa"));
    }

    @Test
    void codeBlockEscapaCrasesEAplicaLimiteInterno() {
        String bloco = formatter.codeBlock("antes ```codigo``` depois");

        assertTrue(bloco.startsWith("```\n"));
        assertTrue(bloco.endsWith("\n```"));
        assertTrue(bloco.contains("'''codigo'''"));
    }

    @Test
    void codeBlockDecodificaEntidadesHtmlDaPlataforma() {
        assertTrue(formatter.codeBlock("Integra&ccedil;&atilde;o").contains("Integração"));
    }

    @Test
    void limitNotificationCortaMensagemLonga() {
        String texto = "x".repeat(5000);

        String limitado = formatter.limitNotification(texto);

        assertTrue(limitado.length() < texto.length());
        assertTrue(limitado.contains("texto muito longo"));
    }

    @Test
    void limitNotificationFechaBlocoCodigoQuandoCortaDentroDoMarkdown() {
        String texto = "inicio\n```\n" + "x".repeat(5000);

        String limitado = formatter.limitNotification(texto);

        assertTrue(limitado.contains("\n```"));
        assertEquals(0, contar(limitado, "```") % 2);
    }

    private int contar(String texto, String trecho) {
        int total = 0;
        int index = 0;
        while ((index = texto.indexOf(trecho, index)) >= 0) {
            total++;
            index += trecho.length();
        }
        return total;
    }
}
