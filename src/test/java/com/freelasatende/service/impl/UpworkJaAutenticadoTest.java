package com.freelasatende.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixa a distincao entre as telas de autenticacao do Upwork e as areas ja
 * logadas. O caso de producao que motivou o teste:
 * {@code /ab/account-security/password-and-security} e area logada, mas era
 * classificada como tela de login por conter "account-security", fazendo um
 * login bem-sucedido ser reportado como "nao concluiu automaticamente".
 */
class UpworkJaAutenticadoTest {

    @Test
    void telaDeLoginNaoEstaAutenticada() {
        assertFalse(UpworkLoginDriverless.jaAutenticado("https://www.upwork.com/ab/account-security/login"));
    }

    @Test
    void areaLogadaDeSegurancaEstaAutenticada() {
        assertTrue(UpworkLoginDriverless.jaAutenticado(
            "https://www.upwork.com/ab/account-security/password-and-security"));
    }

    @Test
    void feedDeVagasEstaAutenticado() {
        assertTrue(UpworkLoginDriverless.jaAutenticado("https://www.upwork.com/nx/find-work/"));
    }

    @Test
    void telasDeVerificacaoNaoEstaoAutenticadas() {
        assertFalse(UpworkLoginDriverless.jaAutenticado("https://www.upwork.com/ab/account-security/otp"));
        assertFalse(UpworkLoginDriverless.jaAutenticado("https://www.upwork.com/ab/verify"));
        assertFalse(UpworkLoginDriverless.jaAutenticado("https://www.upwork.com/challenge"));
    }

    @Test
    void urlNulaOuForaDoDominioNaoEstaAutenticada() {
        assertFalse(UpworkLoginDriverless.jaAutenticado(null));
        assertFalse(UpworkLoginDriverless.jaAutenticado("https://exemplo.com/nx/find-work/"));
    }
}
