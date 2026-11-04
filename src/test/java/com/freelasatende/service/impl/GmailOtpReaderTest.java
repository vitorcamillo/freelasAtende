package com.freelasatende.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GmailOtpReaderTest {

    @Test
    void extraiCodigoDeSeisDigitosDoCorpo() {
        String corpo = "Your Upwork verification code is 483920. It expires in 10 minutes.";
        assertEquals("483920", GmailOtpReader.extrairCodigoDeTextos(null, null, corpo));
    }

    @Test
    void priorizaSeisDigitosSobreOutrosNumeros() {
        String corpo = "Ref 12 - use code 771204 to sign in. Do not share.";
        assertEquals("771204", GmailOtpReader.extrairCodigoDeTextos(corpo));
    }

    @Test
    void ignoraAnoQuandoNaoHaSeisDigitos() {
        // 2026 é ano; o codigo real tem 4 digitos diferentes.
        String corpo = "Freelancer.com 2026 - your login code: 8391";
        assertEquals("8391", GmailOtpReader.extrairCodigoDeTextos(corpo));
    }

    @Test
    void usaAssuntoQuandoCorpoNaoTemCodigo() {
        String assunto = "Codigo 550132";
        String corpo = "Acesse sua conta com seguranca.";
        assertEquals("550132", GmailOtpReader.extrairCodigoDeTextos(assunto, null, corpo));
    }

    @Test
    void retornaNullQuandoNaoHaCodigo() {
        assertNull(GmailOtpReader.extrairCodigoDeTextos("Bem-vindo!", "Sem numeros aqui", null));
    }

    @Test
    void toleraTextosNulosOuVazios() {
        assertNull(GmailOtpReader.extrairCodigoDeTextos(null, "", "   "));
    }
}
