package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.FreelancerBrConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prova de ponta a ponta contra o Freelancer.com.br REAL.
 *
 * <p>Desligado por padrao (exige sessao valida e rede). Habilite com
 * {@code FREELANCERBR_PROVA_REAL=1} para verificar, sem sair do codigo de
 * producao, que a sessao esta viva e o CSRF renova — os dois pre-requisitos do
 * envio de interesse.</p>
 */
@EnabledIfEnvironmentVariable(named = "FREELANCERBR_PROVA_REAL", matches = "1")
class FreelancerBrProvaRealTest {

    @Test
    void sessaoViveECsrfRenovaPeloTransporteDeProducao() {
        FreelancerBrConfig config = new FreelancerBrConfig();
        config.setCookiesArquivo("data/freelancerbr-cookies.json");
        ObjectMapper mapper = new ObjectMapper();
        FreelancerBrClient client = new FreelancerBrClient(
            config, mapper, new FreelancerBrHttp(config, mapper));

        String inbox = client.baixarInboxHtml();

        assertTrue(inbox.toLowerCase().contains("/account/logout"),
            "A inbox precisa voltar autenticada pelo transporte de producao");
        assertFalse(client.avaliarDesafio(200, inbox), "A inbox nao pode cair no desafio do Cloudflare");
        assertTrue(client.temSessaoValida(), "temSessaoValida deve refletir a sessao real");
    }
}
