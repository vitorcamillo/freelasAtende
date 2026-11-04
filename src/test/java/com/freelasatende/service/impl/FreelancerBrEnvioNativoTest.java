package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.FreelancerBrConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrato de envio do Freelancer.com.br, medido na plataforma real.
 *
 * <p>O .com.br e um site Laravel PROPRIO, nao uma traducao do freelancer.com:
 * os projetos daqui nao existem na API global. O primeiro contato e o
 * "Estou interessado" nativo, que exige o id COMPOSTO
 * ({@code <data-id>-<data-partition>}); com o id simples o backend responde
 * HTTP 500.</p>
 */
class FreelancerBrEnvioNativoTest {

    private final FreelancerBrConfig config = new FreelancerBrConfig();
    private final FreelancerBrClient client =
        new FreelancerBrClient(config, new ObjectMapper(), null);

    @Test
    void envioDeInteresseUsaHostCanonicoSemWww() {
        AtomicReference<String> destino = new AtomicReference<>();
        FreelancerBrClient clientComTransporte = new FreelancerBrClient(
            config, new ObjectMapper(), new FreelancerBrHttp(null, null) {
                @Override
                public Resposta postForm(String url, String corpo, String referer) {
                    destino.set(url);
                    return new Resposta(302, url, "");
                }

                @Override
                public Resposta getJson(String url, String referer) {
                    return new Resposta(200, url, "{\"token\":\"csrf\"}");
                }
            });

        boolean enviado = clientComTransporte.enviarInteresseNativo(
            "01a01b98998fb026255bc46b36a63f1d-eyJjYXQiOls5LDE5Nl19", "Ola, vi seu projeto");

        assertTrue(enviado);
        assertEquals("https://freelancer.com.br/project/"
                + "01a01b98998fb026255bc46b36a63f1d-eyJjYXQiOls5LDE5Nl19/interest",
            destino.get());
    }

    @Test
    void listagemProduzIdCompostoUsadoNoEnvio() {
        String html = """
            <div class="assignments-card" data-id="01a01b98998fb026255bc46b36a63f1d"
                 data-partition="eyJjYXQiOls5LDE5Nl19">
              <div class="section-project">
                <a href="https://www.freelancer.com.br/projetos/marketing/digitador/prencher-formulario-9GTFS196"
                   class="link">prencher formulario</a>
              </div>
              <div class="section-category"><span class="main-text bold">Marketing</span></div>
              <div class="section-budget"><span class="bold">R$  100 - R$  500</span></div>
            </div>
            """;

        List<FreelancerBrClient.ProjetoBr> projetos = client.extrairProjetosDaListagem(html);

        assertEquals(1, projetos.size());
        FreelancerBrClient.ProjetoBr projeto = projetos.get(0);
        assertEquals("01a01b98998fb026255bc46b36a63f1d-eyJjYXQiOls5LDE5Nl19", projeto.idComposto(),
            "O envio exige id + partition; so o id devolve HTTP 500 no backend");
        assertEquals("prencher formulario", projeto.titulo());
        assertTrue(projeto.url().endsWith("prencher-formulario-9GTFS196"));
    }

    @Test
    void envioSemIdCompostoFalhaAntesDeChamarARede() {
        MarketplaceAdapterException erro = org.junit.jupiter.api.Assertions.assertThrows(
            MarketplaceAdapterException.class,
            () -> client.enviarInteresseNativo(null, "Ola, vi seu projeto"));

        assertEquals(MarketplaceAdapterErrorType.FALHA_ENVIO, erro.getType());
    }

    @Test
    void orcamentoEmRealVemDoMaiorValorDaFaixa() {
        FreelancerBrProjetoParser parser = new FreelancerBrProjetoParser(config);

        assertEquals(0, parser.extrairOrcamentoTexto("R$  500 - R$  2.000")
            .compareTo(new java.math.BigDecimal("2000")));
        assertNotNull(parser.extrairOrcamentoTexto("R$  30 - R$  50"));
        org.junit.jupiter.api.Assertions.assertNull(parser.extrairOrcamentoTexto(""));
    }
}
