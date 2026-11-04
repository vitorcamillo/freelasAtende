package com.freelasatende.controller;

import com.freelasatende.model.dto.AvaliacaoResultado;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ControleControllerSyncTest extends ControleControllerTestSupport {

    @Test
    @DisplayName("Controle sincroniza inbox real em modo ensino e não envia resposta")
    void controleSincronizaInboxEmModoEnsinoENaoEnviaResposta() throws Exception {
        ConversaResumo conversa = ConversaResumo.builder()
            .idConversa(77L)
            .idProjeto(88L)
            .nomeProjeto("Landing page")
            .nomeCliente("Cliente")
            .idCliente(1613360L)
            .idFreelancer(269409L)
            .nomeFreelancer("Vitor Camillo")
            .build();
        MensagemConversa mensagemCliente = MensagemConversa.builder()
            .id(102L)
            .idConversa(77L)
            .idPessoa(1613360L)
            .nomePessoa("Cliente")
            .texto("Consegue confirmar o próximo passo?")
            .dataCriacaoMillis(2000L)
            .enviadaPorMim(false)
            .build();
        when(scraperService.listarConversasInbox()).thenReturn(List.of(conversa));
        when(scraperService.listarMensagensConversa(77L)).thenReturn(List.of(mensagemCliente));
        when(avaliadorIaService.sugerirRespostaConversa(any(), anyString(), anyString()))
            .thenReturn("Oi, consigo seguir por aqui e te confirmo o próximo passo.");

        mockMvc.perform(post("/api/controle/sincronizar-inbox"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(true))
            .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("Pendencias novas: 1")))
            .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("Nada foi enviado")));

        assertEquals(1, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        AcaoPendente acao = acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.PENDENTE).get(0);
        assertEquals(TipoAcaoPendente.RESPONDER_CONVERSA, acao.getTipo());
        assertEquals(ModoSistema.ENSINAMENTO, acao.getModoOrigem());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
    }

    @Test
    @DisplayName("Controle sincroniza projeto real em modo ensino e não envia proposta")
    void controleSincronizaProjetoEmModoEnsinoENaoEnviaProposta() throws Exception {
        Projeto projeto = Projeto.builder()
            .id(155L)
            .titulo("Site institucional real")
            .descricao("Preciso de um site institucional com formulário.")
            .categoria("Web")
            .urlProjeto("https://www.99freelas.com.br/project/site-institucional-155")
            .orcamento(BigDecimal.valueOf(1200))
            .dataCriacao(LocalDateTime.now())
            .build();
        when(scraperService.buscarProjetosNovos()).thenReturn(List.of(projeto));
        when(scraperService.buscarDetalhesProjeto(anyLong(), any())).thenReturn(projeto);
        when(avaliadorIaService.avaliarProjeto(any())).thenReturn(AvaliacaoResultado.builder().capaz(true).build());
        when(avaliadorIaService.gerarDuvidas(any())).thenReturn(List.of("Consegue confirmar o prazo ideal?"));

        mockMvc.perform(post("/api/controle/sincronizar-projetos"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(true))
            .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("pendencias novas: 1")))
            .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("Nada foi enviado")));

        assertEquals(1, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        AcaoPendente acao = acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.PENDENTE).get(0);
        assertEquals(TipoAcaoPendente.ENVIAR_PERGUNTA, acao.getTipo());
        assertEquals(ModoSistema.ENSINAMENTO, acao.getModoOrigem());
        verify(scraperService, never()).enviarProposta(any(), any());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
    }

    @Test
    @DisplayName("Controle reprocessa projeto ignorado no modo atual sem apagar auditoria")
    void controleReprocessaProjetoIgnoradoNoModoAtualSemApagarAuditoria() throws Exception {
        Projeto projeto = Projeto.builder()
            .id(156L)
            .titulo("Desenvolvimento de blog de noticias")
            .descricao("Finalizar blog de noticias com SEO e feeds RSS.")
            .categoria("Web")
            .status(com.freelasatende.model.enums.StatusProjeto.IGNORADO)
            .foiProcessado(true)
            .motivoRecusa("escopo complexo")
            .build();
        projetoRepository.save(projeto);
        when(scraperService.buscarDetalhesProjeto(anyLong(), any())).thenReturn(projeto);

        mockMvc.perform(post("/api/controle/projetos/{id}/reprocessar", projeto.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(true))
            .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("reprocessado")));

        mockMvc.perform(post("/api/controle/projetos/{id}/reprocessar", projeto.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(false))
            .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("nao esta ignorado")));

        assertEquals(1, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        verify(avaliadorIaService, times(1)).reavaliarProjeto(any());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
    }
}
