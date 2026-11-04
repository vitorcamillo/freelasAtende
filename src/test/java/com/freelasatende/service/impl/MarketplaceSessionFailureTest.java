package com.freelasatende.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketplaceSessionFailureTest {

    @Test
    void classificaHttp5xxComoFalhaTemporariaDeRede() {
        assertTrue(MarketplaceSessionFailure.isFalhaTemporariaRede(
            new IllegalStateException("GET /services/user/carregarConversas retornou HTTP 521")
        ));
    }

    @Test
    void naoClassifica403ComoFalhaTemporariaDeRede() {
        assertFalse(MarketplaceSessionFailure.isFalhaTemporariaRede(
            new IllegalStateException("GET /dashboard retornou HTTP 403")
        ));
    }

    @Test
    void classifica403ComoSessao99FreelasInvalida() {
        assertTrue(MarketplaceSessionFailure.isSessao99FreelasInvalida(
            new IllegalStateException("GET /dashboard retornou HTTP 403")
        ));
    }

    @Test
    void classifica403ComoSessaoWorkanaInvalida() {
        MarketplaceAdapterException exception = new MarketplaceAdapterException(
            com.freelasatende.model.enums.PlataformaFreela.WORKANA,
            MarketplaceAdapterErrorType.FALHA_AUTENTICACAO,
            "Workana form de pergunta retornou HTTP 403"
        );

        assertTrue(MarketplaceSessionFailure.isSessaoWorkanaInvalida(exception));
        assertTrue(MarketplaceSessionFailure.isSessaoMarketplaceInvalida(exception));
    }

    @Test
    void naoClassificaAcessoNegadoDoFormWorkanaComoSessaoInvalida() {
        MarketplaceAdapterException exception = new MarketplaceAdapterException(
            com.freelasatende.model.enums.PlataformaFreela.WORKANA,
            MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL,
            "Workana form de pergunta retornou HTTP 403: Acesso Negado Voce nao possui autorizacao"
        );

        assertFalse(MarketplaceSessionFailure.isSessaoWorkanaInvalida(exception));
        assertFalse(MarketplaceSessionFailure.isSessaoMarketplaceInvalida(exception));
    }

    @Test
    void naoClassificaAcessoNegadoDoFormWorkanaEmbrulhadoComoSessaoInvalida() {
        MarketplaceAdapterException causa = new MarketplaceAdapterException(
            com.freelasatende.model.enums.PlataformaFreela.WORKANA,
            MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL,
            "Workana form de pergunta retornou HTTP 403: Acesso Negado Voce nao possui autorizacao"
        );
        IllegalStateException exception = new IllegalStateException("Falha ao executar acao pendente", causa);

        assertFalse(MarketplaceSessionFailure.isSessaoWorkanaInvalida(exception));
        assertFalse(MarketplaceSessionFailure.isSessaoMarketplaceInvalida(exception));
    }
}
