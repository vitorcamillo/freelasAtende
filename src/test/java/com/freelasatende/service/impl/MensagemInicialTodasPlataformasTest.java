package com.freelasatende.service.impl;

import com.freelasatende.config.FreelancerConfig;
import com.freelasatende.config.UpworkConfig;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.util.AtendimentoTextos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Garante que a abordagem inicial existe em todas as plataformas, cada uma com o
 * texto adaptado ao proprio limite, sem vazar o texto-base cru onde ele nao cabe.
 */
class MensagemInicialTodasPlataformasTest {

    @Test
    @DisplayName("Workana e Freelancer, com contrato proprio, adaptam o texto-base da abordagem inicial")
    void contratoAdaptaTextoBasePorPlataforma() {
        String base = AtendimentoTextos.abordagemInicial();

        String workana = MarketplaceHttpContract.mensagemInicialWorkana(base);
        String freelancer = MarketplaceHttpContract.mensagemInicialFreelancer(base);

        assertNotEquals(base, workana);
        assertNotEquals(base, freelancer);
        assertFalse(workana.isBlank());
        assertFalse(freelancer.isBlank());
    }

    @Test
    @DisplayName("Contrato preserva mensagem personalizada que nao e a abordagem inicial")
    void contratoPreservaMensagemPersonalizada() {
        String personalizada = "Posso entregar a API em 5 dias com testes automatizados.";

        assertEquals(personalizada, MarketplaceHttpContract.mensagemInicialFreelancer(personalizada));
        assertEquals(personalizada, MarketplaceHttpContract.mensagemInicialWorkana(personalizada));
    }

    @Test
    @DisplayName("Freelancer expoe a capacidade de abordagem inicial; Upwork nao (nao ha endpoint da plataforma)")
    void freelancerSuportaPerguntasUpworkNao() {
        UpworkMarketplaceAdapter upwork = new UpworkMarketplaceAdapter(new UpworkConfig(), null, null);
        FreelancerMarketplaceAdapter freelancer =
            new FreelancerMarketplaceAdapter(new FreelancerConfig(), null);

        assertFalse(upwork.capabilities().perguntas());
        assertTrue(upwork.capabilities().propostas());
        assertTrue(freelancer.capabilities().perguntas());
    }

    @Test
    @DisplayName("Upwork recusa pergunta pre-candidatura: a plataforma nao suporta abrir conversa antes de aplicar")
    void upworkRecusaPerguntaPreCandidatura() {
        UpworkClient client = mock(UpworkClient.class);
        UpworkMarketplaceAdapter adapter =
            new UpworkMarketplaceAdapter(new UpworkConfig(), client, null);
        Projeto projeto = Projeto.builder()
            .id(PlataformaFreela.UPWORK.idInterno(10L))
            .idExterno(10L)
            .plataforma(PlataformaFreela.UPWORK)
            .urlProjeto("https://www.upwork.com/jobs/~021234567890abcdef?ref=search")
            .build();

        assertThrows(MarketplaceAdapterException.class,
            () -> adapter.enviarPergunta(projeto, AtendimentoTextos.abordagemInicial()));
        verify(client, never()).enviarMensagemConversa(ArgumentMatchers.anyLong(),
            ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Freelancer envia o texto adaptado usando o idExterno do projeto")
    void freelancerEnviaTextoAdaptado() {
        FreelancerClient client = mock(FreelancerClient.class);
        FreelancerMarketplaceAdapter adapter =
            new FreelancerMarketplaceAdapter(new FreelancerConfig(), client);
        Projeto projeto = Projeto.builder()
            .id(PlataformaFreela.FREELANCER.idInterno(77L))
            .idExterno(77L)
            .plataforma(PlataformaFreela.FREELANCER)
            .build();
        String esperado =
            MarketplaceHttpContract.mensagemInicialFreelancer(AtendimentoTextos.abordagemInicial());
        when(client.enviarPergunta(org.mockito.ArgumentMatchers.eq(77L),
            org.mockito.ArgumentMatchers.eq(esperado), org.mockito.ArgumentMatchers.any())).thenReturn(true);

        boolean enviada = adapter.enviarPergunta(projeto, AtendimentoTextos.abordagemInicial());

        assertTrue(enviada);
        verify(client).enviarPergunta(org.mockito.ArgumentMatchers.eq(77L),
            org.mockito.ArgumentMatchers.eq(esperado), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("Freelancer nao envia quando falta idExterno")
    void freelancerNaoEnviaSemIdExterno() {
        FreelancerClient client = mock(FreelancerClient.class);
        FreelancerMarketplaceAdapter adapter =
            new FreelancerMarketplaceAdapter(new FreelancerConfig(), client);
        Projeto projeto = Projeto.builder()
            .id(PlataformaFreela.FREELANCER.idInterno(78L))
            .plataforma(PlataformaFreela.FREELANCER)
            .build();

        assertFalse(adapter.enviarPergunta(projeto, AtendimentoTextos.abordagemInicial()));
        verify(client, never()).enviarPergunta(ArgumentMatchers.anyLong(),
            ArgumentMatchers.anyString());
    }
}
