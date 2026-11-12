package com.freelasatende.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressao: o Chrome residente do Freelancer.com.br segura memoria do
 * container (~150-200MB) entre os ciclos de polling. Ele deve ser fechado
 * depois de N minutos ocioso — o perfil persistente em disco preserva cookies
 * e cf_clearance na proxima abertura.
 */
class FreelancerBrBrowserOciosidadeTest {

    private static final long MINUTO = 60_000L;

    @Test
    void minutosZeroDesativaOFechamento() {
        assertFalse(FreelancerBrBrowser.deveFecharPorOciosidade(1L, 100L * MINUTO, 0L),
            "Valor 0 desativa: o Chrome continua residente como antes");
    }

    @Test
    void semUsoRegistradoNaoFecha() {
        assertFalse(FreelancerBrBrowser.deveFecharPorOciosidade(0L, 100L * MINUTO, 10L),
            "Sem ultimo uso conhecido nao ha o que fechar");
    }

    @Test
    void noveMinutosENoventaENoveSegundosAindaNaoFecha() {
        long agora = 100L * MINUTO;
        long ultimoUso = agora - (10L * MINUTO - 1_000L);
        assertFalse(FreelancerBrBrowser.deveFecharPorOciosidade(ultimoUso, agora, 10L),
            "Faltando 1s para o limite ainda nao fecha");
    }

    @Test
    void exatamenteDezMinutosFecha() {
        long agora = 100L * MINUTO;
        assertTrue(FreelancerBrBrowser.deveFecharPorOciosidade(agora - 10L * MINUTO, agora, 10L),
            "No limite exato o Chrome ja pode ser fechado");
    }

    @Test
    void minutosNegativosDesativam() {
        assertFalse(FreelancerBrBrowser.deveFecharPorOciosidade(1L, 100L * MINUTO, -5L),
            "Valor negativo tambem desativa o fechamento por ociosidade");
    }
}
