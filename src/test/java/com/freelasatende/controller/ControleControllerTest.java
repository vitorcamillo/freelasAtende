package com.freelasatende.controller;

import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.MensagemClienteRegistro;
import com.freelasatende.model.enums.StatusMensagemCliente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ControleControllerTest extends ControleControllerTestSupport {

    @Test
    @DisplayName("Controle lista pendências com texto ao cliente")
    void controleListaPendenciasComTextoAoCliente() throws Exception {
        AcaoPendente acao = salvarAcao("Oi, consegue confirmar o prazo ideal?");

        mockMvc.perform(get("/api/controle/acoes-pendentes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(acao.getId()))
            .andExpect(jsonPath("$[0].textoClientePreview").value("Oi, consegue confirmar o prazo ideal?"))
            .andExpect(jsonPath("$[0].nivelConfianca").exists())
            .andExpect(jsonPath("$[0].scoreNaturalidade").exists())
            .andExpect(jsonPath("$[0].scoreConfianca").exists())
            .andExpect(jsonPath("$[0].decisaoQualidade").exists())
            .andExpect(jsonPath("$[0].checklistRegras").isArray())
            .andExpect(jsonPath("$[0].feedbackObrigatorio").value(true))
            .andExpect(jsonPath("$[0].motivosAprendizado").isArray())
            .andExpect(jsonPath("$[0].nivelAutonomia").value("PERGUNTA_CLIENTE"))
            .andExpect(jsonPath("$[0].entendimentoIa").value("Projeto possivel, mas falta confirmar prazo."))
            .andExpect(jsonPath("$[0].analiseIa").value("Li o projeto e encontrei falta de prazo."))
            .andExpect(jsonPath("$[0].opcoesIa[0]").value("Responder a IA antes de enviar"))
            .andExpect(jsonPath("$[0].atitudeSugerida").value("Conversar com voce antes de perguntar ao cliente."))
            .andExpect(jsonPath("$[0].conversaEnsinoObrigatoria").value(true))
            .andExpect(jsonPath("$[0].conversaEnsinoRespondida").value(false))
            .andExpect(jsonPath("$[0].informacoesConfirmadas[0]").value("Titulo: Landing page"))
            .andExpect(jsonPath("$[0].informacoesPendentes[0]").value("Confirmar prazo antes da proposta."))
            .andExpect(jsonPath("$[0].sabeFazer[0]").value("rascunhar pergunta curta"))
            .andExpect(jsonPath("$[0].duvidasInternas[0]").value("confirmar se prazo e requisito bloqueante"))
            .andExpect(jsonPath("$[0].perguntasParaCliente[0]").value("Consegue confirmar o prazo ideal?"))
            .andExpect(jsonPath("$[0].riscosDecisao[0]").value("proposta sem prazo pode ficar imprecisa"))
            .andExpect(jsonPath("$[0].proximaAcaoSugerida").value("Perguntar ao cliente se voce aprovar."))
            .andExpect(jsonPath("$[0].perguntaParaUsuario").value("Essa pergunta esta certa para este projeto?"))
            .andExpect(jsonPath("$[0].regraAprendizadoSugerida").value("Quando faltar prazo, perguntar antes da proposta."));
    }

    @Test
    @DisplayName("Controle expõe contexto de autoria da conversa")
    void controleExpoeContextoDeAutoriaDaConversa() throws Exception {
        AcaoPendente acao = salvarAcaoConversa();

        mockMvc.perform(get("/api/controle/acoes-pendentes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(acao.getId()))
            .andExpect(jsonPath("$[0].conversaId").value(77L))
            .andExpect(jsonPath("$[0].mensagemGatilhoId").value(102L))
            .andExpect(jsonPath("$[0].mensagemGatilhoAutor").value("CLIENTE"))
            .andExpect(jsonPath("$[0].idCliente").value(1613360L))
            .andExpect(jsonPath("$[0].idFreelancer").value(269409L))
            .andExpect(jsonPath("$[0].autoriaConfiavel").value(true))
            .andExpect(jsonPath("$[0].ultimasMensagensMinhas[0].autor").value("EU"))
            .andExpect(jsonPath("$[0].ultimasMensagensCliente[0].autor").value("CLIENTE"))
            .andExpect(jsonPath("$[0].mensagensIgnoradasResumo[0]").exists());
    }

    @Test
    @DisplayName("Overview expõe pendências, bloqueios e saúde")
    void overviewExpoePendenciasBloqueiosESaude() throws Exception {
        salvarAcao("Oi, consegue confirmar o prazo ideal?");
        mensagemClienteRegistroRepository.save(MensagemClienteRegistro.builder()
            .projetoId(44L)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .fingerprint("fingerprint-bloqueado")
            .textoPreview("Teste tecnico automatizado")
            .status(StatusMensagemCliente.BLOQUEADA)
            .motivoBloqueio("Tom de sistema")
            .data(LocalDateTime.now())
            .build());

        mockMvc.perform(get("/api/controle/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessaoValida").value(true))
            .andExpect(jsonPath("$.escritaRealHabilitada").value(true))
            .andExpect(jsonPath("$.simulacaoEnvioHabilitada").value(false))
            .andExpect(jsonPath("$.totalPendencias").value(1))
            .andExpect(jsonPath("$.totalBloqueios").value(1))
            .andExpect(jsonPath("$.totalEnviosSimulados").value(0))
            .andExpect(jsonPath("$.aprendizado.memoriaIa.saude").value("OK"))
            .andExpect(jsonPath("$.aprendizado.operacional").exists())
            .andExpect(jsonPath("$.qualidadeOperacional.scoreMedioFila").exists())
            .andExpect(jsonPath("$.qualidadeOperacional.pendenciasAlta").exists())
            .andExpect(jsonPath("$.qualidadeOperacional.resumo").exists())
            .andExpect(jsonPath("$.focoEnsino.status").value("SEM_FOCO"));
    }

    @Test
    @DisplayName("Reescrever valida e mantém ação pendente")
    void reescreverValidaEMantemAcaoPendente() throws Exception {
        AcaoPendente acao = salvarAcao("Oi, consegue confirmar o prazo ideal?");

        mockMvc.perform(post("/api/controle/acoes/{id}/reescrever", acao.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"texto\":\"Oi, voce consegue confirmar qual prazo faz mais sentido?\",\"comentario\":\"ajuste\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(true))
            .andExpect(jsonPath("$.acao.status").value("PENDENTE"))
            .andExpect(jsonPath("$.acao.textoClientePreview").value("Oi, voce consegue confirmar qual prazo faz mais sentido?"));
    }

    @Test
    @DisplayName("Reescrever com termo proibido bloqueia sem enviar")
    void reescreverComTermoProibidoBloqueiaSemEnviar() throws Exception {
        AcaoPendente acao = salvarAcao("Oi, consegue confirmar o prazo ideal?");

        mockMvc.perform(post("/api/controle/acoes/{id}/reescrever", acao.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"texto\":\"Teste tecnico automatizado: mensagem de validacao\",\"comentario\":\"bloquear\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(false))
            .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("bloqueado")));
    }

    @Test
    @DisplayName("Aprovar chama scraper uma única vez")
    void aprovarChamaScraperUmaUnicaVez() throws Exception {
        AcaoPendente acao = salvarAcao("Oi, consegue confirmar o prazo ideal?");

        mockMvc.perform(post("/api/controle/acoes/{id}/aprovar", acao.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comentario\":\"ok\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(true))
            .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("executada")));

        verify(scraperService, times(1)).enviarPergunta(44L, "Oi, consegue confirmar o prazo ideal?");
    }

    @Test
    @DisplayName("Aprovar reporta falha quando marketplace nao confirma envio")
    void aprovarReportaFalhaQuandoMarketplaceNaoConfirmaEnvio() throws Exception {
        AcaoPendente acao = salvarAcao("Oi, consegue confirmar o prazo ideal?");
        when(scraperService.enviarPergunta(44L, "Oi, consegue confirmar o prazo ideal?")).thenReturn(false);

        mockMvc.perform(post("/api/controle/acoes/{id}/aprovar", acao.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comentario\":\"ok\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(false))
            .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("falhou")))
            .andExpect(jsonPath("$.acao.status").value("ERRO"));

        verify(scraperService, times(1)).enviarPergunta(44L, "Oi, consegue confirmar o prazo ideal?");
    }

    @Test
    @DisplayName("Recusar marca ação como rejeitada")
    void recusarMarcaAcaoComoRejeitada() throws Exception {
        AcaoPendente acao = salvarAcao("Oi, consegue confirmar o prazo ideal?");

        mockMvc.perform(post("/api/controle/acoes/{id}/recusar", acao.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comentario\":\"nao enviar\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(true))
            .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("rejeitada")));
    }

    @Test
    @DisplayName("Dashboard carrega abas e assets externos")
    void dashboardCarregaIdsPrincipaisDoComandoSeguro() throws Exception {
        mockMvc.perform(get("/dashboard"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("controlCenterApp")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("data-abordagem-inicial=\"Ja foi atendido?\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("command-dock")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("dashboardTabs")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("pauseButton")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("resumeButton")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("autoOnButton")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("autoOffButton")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("tab-operar")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"tab-auditoria\" class=\"tab-panel\" data-panel=\"auditoria\" role=\"tabpanel\" hidden")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"tab-logs\" class=\"tab-panel\" data-panel=\"logs\" role=\"tabpanel\" hidden")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"tab-inbox\" class=\"tab-panel\" data-panel=\"inbox\" role=\"tabpanel\" hidden")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"tab-ml-lab\" class=\"tab-panel\" data-panel=\"ml-lab\" role=\"tabpanel\" hidden")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"tab-controle\" class=\"tab-panel\" data-panel=\"controle\" role=\"tabpanel\" hidden")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("teachingFocusPanel")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("mlLabCreateButton")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("panicButton")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("kpiConexoesTopo")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("rewriteModal")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/css/dashboard.css")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/dashboard.js")));
    }
}
