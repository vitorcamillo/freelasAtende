package com.freelasatende.service.impl;

import io.github.selenium.javaDriverless.JavaDriverless;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cobre a extracao da mensagem de erro visivel na pagina de login 99Freelas.
 * O driver e mockado como os demais testes do pacote (classe concreta via Mockito).
 */
class LoginDriverlessErroLoginTest {

    @Test
    void driverNuloRetornaStringVazia() {
        assertEquals("", LoginDriverlessDomHelper.extrairMensagemErroLogin(null));
    }

    @Test
    void scriptSemResultadoRetornaStringVazia() throws Exception {
        JavaDriverless driver = mock(JavaDriverless.class);
        when(driver.executeScript(anyString())).thenReturn(null);

        assertEquals("", LoginDriverlessDomHelper.extrairMensagemErroLogin(driver));
    }

    @Test
    void mensagemDeAlertaVisivelEhRetornada() throws Exception {
        JavaDriverless driver = mock(JavaDriverless.class);
        when(driver.executeScript(anyString())).thenReturn("E-mail ou senha incorretos");

        assertEquals("E-mail ou senha incorretos", LoginDriverlessDomHelper.extrairMensagemErroLogin(driver));
    }

    @Test
    void emailDaPaginaEhMascaradoAntesDoLog() throws Exception {
        JavaDriverless driver = mock(JavaDriverless.class);
        // Fallback varre linhas do body: pode carregar texto com dado pessoal da pagina.
        when(driver.executeScript(anyString()))
            .thenReturn("erro ao entrar com fulano.silva@empresa.com.br | tente novamente");

        assertEquals("erro ao entrar com ***@*** | tente novamente",
            LoginDriverlessDomHelper.extrairMensagemErroLogin(driver));
    }

    @Test
    void scriptQueLancaExcecaoRetornaStringVazia() throws Exception {
        JavaDriverless driver = mock(JavaDriverless.class);
        when(driver.executeScript(anyString())).thenThrow(new RuntimeException("chrome caiu"));

        assertEquals("", LoginDriverlessDomHelper.extrairMensagemErroLogin(driver));
    }
}
