package com.freelasatende.controller;

import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import java.nio.file.Files;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

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

class ControleControllerEnsinoTest extends ControleControllerTestSupport {

    @Test
    @DisplayName("Controle define e limpa foco de ensino por ação")
    void controleDefineELimpaFocoDeEnsinoPorAcao() throws Exception {
        AcaoPendente acao = salvarAcao("Oi, consegue confirmar o prazo ideal?");

        mockMvc.perform(post("/api/controle/foco-ensino/acoes/{id}", acao.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ativo").value(true))
            .andExpect(jsonPath("$.acaoId").value(acao.getId()))
            .andExpect(jsonPath("$.projetoId").value(44L))
            .andExpect(jsonPath("$.tituloProjeto").value("Landing page"))
            .andExpect(jsonPath("$.textoClientePreview").value("Oi, consegue confirmar o prazo ideal?"));

        mockMvc.perform(get("/api/controle/acoes-pendentes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].focoEnsinoAtual").value(true));

        mockMvc.perform(post("/api/controle/foco-ensino/limpar"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ativo").value(false))
            .andExpect(jsonPath("$.status").value("SEM_FOCO"));
    }

    @Test
    @DisplayName("Controle registra contexto de ensino, reescreve rascunho e não envia")
    void controleRegistraContextoDeEnsinoReescreveENaoEnvia() throws Exception {
        AcaoPendente acao = salvarAcao("Oi, tudo bem? Consegue confirmar prazo e escopo?");
        when(avaliadorIaService.reescreverRascunhoComOrientacao(anyString(), anyString(), anyString()))
            .thenReturn("Oi! A hospedagem na Hostinger ja esta liberada para eu mexer?");

        mockMvc.perform(post("/api/controle/acoes/{id}/contexto", acao.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"texto\":\"Ele ja recebeu mensagem antes. Pergunte so se a hospedagem esta liberada.\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(true))
            .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("Contexto salvo")))
            .andExpect(jsonPath("$.acao.status").value("PENDENTE"))
            .andExpect(jsonPath("$.acao.textoClientePreview").value("Oi! A hospedagem na Hostinger ja esta liberada para eu mexer?"))
            .andExpect(jsonPath("$.acao.observacaoUsuario").value(org.hamcrest.Matchers.containsString("Ensino:")));

        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
    }

    @Test
    @DisplayName("Controle responde dúvida da IA, aprende, reescreve e mantém pendente")
    void controleRespondeDuvidaIaAprendeReescreveEMantemPendente() throws Exception {
        AcaoPendente acao = salvarAcao("Oi, consegue confirmar o prazo ideal?");
        when(avaliadorIaService.reescreverRascunhoComOrientacao(anyString(), anyString(), anyString()))
            .thenReturn("Oi, voce consegue confirmar o prazo ideal antes de eu te passar uma proposta alinhada?");

        mockMvc.perform(post("/api/controle/acoes/{id}/responder-duvida", acao.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "resposta":"Pode perguntar prazo antes de propor.",
                      "regra":"Quando faltar prazo, perguntar antes da proposta."
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(true))
            .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("Resposta salva")))
            .andExpect(jsonPath("$.acao.status").value("PENDENTE"))
            .andExpect(jsonPath("$.acao.textoClientePreview").value("Oi, voce consegue confirmar o prazo ideal antes de eu te passar uma proposta alinhada?"))
            .andExpect(jsonPath("$.acao.observacaoUsuario").value(org.hamcrest.Matchers.containsString("Duvida IA:")));

        var aprendizados = aprendizadoRepository.findAll();
        assertTrue(aprendizados.size() >= 1);
        assertEquals(1, aprendizados.stream()
            .filter(aprendizado -> "RESPOSTA_DUVIDA_IA".equals(aprendizado.getOrigemFeedback()))
            .count());
        assertTrue(aprendizados.stream()
            .anyMatch(aprendizado -> aprendizado.getRegrasAprendidas().contains("Quando faltar prazo")));
        assertTrue(Files.readString(MEMORIA_CONTROLE).contains("Quando faltar prazo, perguntar antes da proposta."));

        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
    }

    @Test
    @DisplayName("Controle rejeita contexto de ensino vazio sem chamar IA")
    void controleRejeitaContextoDeEnsinoVazioSemChamarIa() throws Exception {
        AcaoPendente acao = salvarAcao("Oi, consegue confirmar o prazo ideal?");

        mockMvc.perform(post("/api/controle/acoes/{id}/contexto", acao.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(false))
            .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("orientação")))
            .andExpect(jsonPath("$.acao.status").value("PENDENTE"));

        verify(avaliadorIaService, never()).reescreverRascunhoComOrientacao(anyString(), anyString(), anyString());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
    }

    @Test
    @DisplayName("Controle rejeita contexto de ensino em ação já executada")
    void controleRejeitaContextoDeEnsinoEmAcaoJaExecutada() throws Exception {
        AcaoPendente acao = salvarAcao("Oi, consegue confirmar o prazo ideal?");
        acao.setStatus(StatusAcaoPendente.EXECUTADA);
        acaoPendenteRepository.save(acao);

        mockMvc.perform(post("/api/controle/acoes/{id}/contexto", acao.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"texto\":\"pergunte prazo\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(false))
            .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("não está pendente")))
            .andExpect(jsonPath("$.acao.status").value("EXECUTADA"));

        verify(avaliadorIaService, never()).reescreverRascunhoComOrientacao(anyString(), anyString(), anyString());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
    }

    @Test
    @DisplayName("Controle rejeita resposta vazia da dúvida da IA")
    void controleRejeitaRespostaVaziaDaDuvidaIa() throws Exception {
        AcaoPendente acao = salvarAcao("Oi, consegue confirmar o prazo ideal?");

        mockMvc.perform(post("/api/controle/acoes/{id}/responder-duvida", acao.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(false))
            .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("Responda a dúvida da IA")))
            .andExpect(jsonPath("$.acao.status").value("PENDENTE"));

        verify(avaliadorIaService, never()).reescreverRascunhoComOrientacao(anyString(), anyString(), anyString());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
    }

    @Test
    @DisplayName("Controle rejeita ensino em cancelamento de proposta")
    void controleRejeitaEnsinoEmCancelamentoDeProposta() throws Exception {
        AcaoPendente acao = acaoPendenteRepository.save(AcaoPendente.builder()
            .projetoId(44L)
            .tipo(TipoAcaoPendente.CANCELAR_PROPOSTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.ENSINAMENTO)
            .tituloProjeto("Landing page")
            .resumo("Cancelar proposta")
            .payloadJson("{\"projetoId\":44,\"tituloProjeto\":\"Landing page\",\"mensagem\":\"Cancelar proposta\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build());

        mockMvc.perform(post("/api/controle/acoes/{id}/contexto", acao.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"texto\":\"cancelar por risco\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(false))
            .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("Cancelamento não usa conversa de ensino")))
            .andExpect(jsonPath("$.acao.status").value("PENDENTE"));

        verify(avaliadorIaService, never()).reescreverRascunhoComOrientacao(anyString(), anyString(), anyString());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
    }
}
