package com.freelasatende.controller;

import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.AcaoPendente;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ControleControllerInboxAuditoriaTest extends ControleControllerTestSupport {

    @Test
    @DisplayName("Auditoria da inbox lê todas as conversas sem marcar como visualizada")
    void auditoriaInboxLeTodasConversasSemVisualizar() throws Exception {
        ConversaResumo conversaCliente = ConversaResumo.builder()
            .idConversa(77L)
            .idProjeto(88L)
            .nomeProjeto("Landing page")
            .nomeCliente("Cliente")
            .idCliente(1613360L)
            .idFreelancer(269409L)
            .nomeFreelancer("Vitor Camillo")
            .build();
        ConversaResumo conversaMinha = ConversaResumo.builder()
            .idConversa(78L)
            .idProjeto(89L)
            .nomeProjeto("Sistema web")
            .nomeCliente("Cliente 2")
            .idCliente(1613361L)
            .idFreelancer(269409L)
            .nomeFreelancer("Vitor Camillo")
            .build();
        MensagemConversa cliente = MensagemConversa.builder()
            .id(102L)
            .idConversa(77L)
            .idPessoa(1613360L)
            .nomePessoa("Cliente")
            .texto("Consegue confirmar o prazo?")
            .dataCriacaoMillis(2000L)
            .enviadaPorMim(false)
            .build();
        MensagemConversa minha = MensagemConversa.builder()
            .id(103L)
            .idConversa(78L)
            .idPessoa(269409L)
            .nomePessoa("Vitor Camillo")
            .texto("Te respondi por aqui.")
            .dataCriacaoMillis(2100L)
            .enviadaPorMim(true)
            .build();
        when(scraperService.listarConversasInbox()).thenReturn(List.of(conversaCliente, conversaMinha));
        when(scraperService.listarMensagensConversa(77L, false)).thenReturn(List.of(cliente));
        when(scraperService.listarMensagensConversa(78L, false)).thenReturn(List.of(minha));

        mockMvc.perform(post("/api/controle/auditar-inbox"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.leituraSemMarcarVisualizada").value(true))
            .andExpect(jsonPath("$.totalConversas").value(2))
            .andExpect(jsonPath("$.totalMensagens").value(2))
            .andExpect(jsonPath("$.totalUltimaCliente").value(1))
            .andExpect(jsonPath("$.totalUltimaMinha").value(1))
            .andExpect(jsonPath("$.conversas[0].status").value("ULTIMA_CLIENTE_ACIONAVEL"))
            .andExpect(jsonPath("$.conversas[0].analiseOperacional").value(org.hamcrest.Matchers.containsString("ultima fala util e do cliente")))
            .andExpect(jsonPath("$.conversas[1].status").value("ULTIMA_MINHA"));

        verify(scraperService).listarMensagensConversa(77L, false);
        verify(scraperService).listarMensagensConversa(78L, false);
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
    }

    @Test
    @DisplayName("Auditoria da inbox persiste diagnóstico, pergunta da IA e relatório local")
    void auditoriaInboxPersisteDiagnosticoPerguntaERelatorio() throws Exception {
        AcaoPendente acao = salvarAcaoConversa();
        ConversaResumo conversa = ConversaResumo.builder()
            .idConversa(77L)
            .idProjeto(44L)
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
            .texto("Consegue confirmar o prazo?")
            .dataCriacaoMillis(2000L)
            .enviadaPorMim(false)
            .build();
        when(scraperService.listarConversasInbox()).thenReturn(List.of(conversa));
        when(scraperService.listarMensagensConversa(77L, false)).thenReturn(List.of(mensagemCliente));

        mockMvc.perform(post("/api/controle/auditar-inbox"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.auditoriaId").exists())
            .andExpect(jsonPath("$.persistida").value(true))
            .andExpect(jsonPath("$.relatorioHtml").value("target/copiloto-seguro-inbox/index.html"))
            .andExpect(jsonPath("$.painelCenarios.lido").value(1))
            .andExpect(jsonPath("$.painelCenarios.perguntouAoDono").value(1))
            .andExpect(jsonPath("$.painelCenarios.respondeu").value(1))
            .andExpect(jsonPath("$.painelCenarios.vazouContexto").value(0))
            .andExpect(jsonPath("$.conversas[0].acaoPendenteId").value(acao.getId()))
            .andExpect(jsonPath("$.conversas[0].decisaoIa").value("PERGUNTOU_AO_DONO"))
            .andExpect(jsonPath("$.conversas[0].perguntaParaUsuario").value("Essa resposta esta certa para este cliente?"))
            .andExpect(jsonPath("$.conversas[0].duvidasInternas[0]").value("confirmar se o prazo precisa ser perguntado antes de responder"))
            .andExpect(jsonPath("$.conversas[0].decisaoQualidade").exists());

        assertEquals(1, inboxAuditoriaRegistroRepository.count());
        assertEquals(1, inboxConversaAuditoriaRegistroRepository.count());
        assertTrue(Files.exists(AUDITORIA_INBOX_HTML));
        assertTrue(Files.exists(AUDITORIA_INBOX_JSON));
        assertTrue(Files.exists(AUDITORIA_INBOX_RESUMO));
        assertTrue(Files.readString(AUDITORIA_INBOX_HTML).contains("PERGUNTOU_AO_DONO"));
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
    }

    @Test
    @DisplayName("Overview e endpoint de conversas expõem auditoria persistida")
    void overviewEEndpointConversasExpoemAuditoriaPersistida() throws Exception {
        salvarAcaoConversa();
        ConversaResumo conversa = ConversaResumo.builder()
            .idConversa(77L)
            .idProjeto(44L)
            .nomeProjeto("Landing page")
            .nomeCliente("Cliente")
            .idCliente(1613360L)
            .build();
        MensagemConversa mensagemCliente = MensagemConversa.builder()
            .id(102L)
            .idConversa(77L)
            .idPessoa(1613360L)
            .nomePessoa("Cliente")
            .texto("Consegue confirmar o prazo?")
            .dataCriacaoMillis(2000L)
            .enviadaPorMim(false)
            .build();
        when(scraperService.listarConversasInbox()).thenReturn(List.of(conversa));
        when(scraperService.listarMensagensConversa(77L, false)).thenReturn(List.of(mensagemCliente));

        mockMvc.perform(post("/api/controle/auditar-inbox"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/controle/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.auditoriaInboxResumo.persistida").value(true))
            .andExpect(jsonPath("$.auditoriaInboxResumo.totalConversas").value(1))
            .andExpect(jsonPath("$.auditoriaInboxResumo.leituraSemMarcarVisualizada").value(true))
            .andExpect(jsonPath("$.auditoriaInboxResumo.escritaRealHabilitada").value(true))
            .andExpect(jsonPath("$.auditoriaInboxResumo.relatorioHtml").value("target/copiloto-seguro-inbox/index.html"));

        mockMvc.perform(get("/api/controle/auditoria-inbox/conversas"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].conversaId").value(77L))
            .andExpect(jsonPath("$[0].decisaoIa").value("PERGUNTOU_AO_DONO"))
            .andExpect(jsonPath("$[0].rascunhoPendente").value("Oi, consigo seguir por aqui."));
    }
}
