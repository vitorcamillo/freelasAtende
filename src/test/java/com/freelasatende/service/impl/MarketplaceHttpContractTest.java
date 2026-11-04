package com.freelasatende.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketplaceHttpContractTest {

    @Test
    void expandeSomenteAbordagemInicialWorkana() {
        String expandida = MarketplaceHttpContract.mensagemInicialWorkana("Ja foi atendido?");
        String textoNormal = "Mensagem especifica para o cliente";

        assertTrue(expandida.length() <= 100);
        assertTrue(expandida.contains("ainda esta em aberto"));
        assertTrue(expandida.contains("Se não foi atendindo posso lhe ajudar"));
        assertSame(textoNormal, MarketplaceHttpContract.mensagemInicialWorkana(textoNormal));
    }

    @Test
    void montaContratosOficiaisFreelancer() {
        String base = "https://www.freelancer.com";

        assertEquals(base + "/api/messages/0.1/threads?limit=50"
            + "&last_message=true&unread_count=true&user_details=true&context_details=true",
            MarketplaceHttpContract.freelancerThreadsUrl(base));
        assertEquals(base + "/api/messages/0.1/messages?threads%5B%5D=81&user_details=true&limit=100",
            MarketplaceHttpContract.freelancerMessagesUrl(base, 81L));
        assertEquals(base + "/api/messages/0.1/threads/81/messages/",
            MarketplaceHttpContract.freelancerThreadMessageUrl(base, 81L));
        assertEquals(100, MarketplaceHttpContract.freelancerMilestonePercentage());
    }

    @Test
    void resumeRespostaSemOmitirErroCurtoELimitaErroLongo() {
        assertEquals("corpo vazio", MarketplaceHttpContract.resumirResposta(null));
        assertEquals("corpo vazio", MarketplaceHttpContract.resumirResposta("  "));
        assertEquals("campo amount invalido", MarketplaceHttpContract.resumirResposta("campo  amount\n invalido"));
        assertEquals(303, MarketplaceHttpContract.resumirResposta("x".repeat(400)).length());
        assertTrue(MarketplaceHttpContract.resumirResposta("x".repeat(400)).endsWith("..."));
    }
}
