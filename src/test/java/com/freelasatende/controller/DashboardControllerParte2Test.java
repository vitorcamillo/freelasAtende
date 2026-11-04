package com.freelasatende.controller;

import com.freelasatende.model.dto.ControleOverviewDTO;
import com.freelasatende.model.dto.DashboardStatsDTO;
import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.scheduler.PollingScheduler;
import com.freelasatende.service.impl.FreelancerLoginDriverless;
import com.freelasatende.service.impl.LoginAssistidoAutoTrigger;
import com.freelasatende.service.impl.LoginJavaDriverless;
import com.freelasatende.service.impl.MarketplaceGateway;
import com.freelasatende.service.impl.SystemHealthTracker;
import com.freelasatende.service.impl.TelegramServiceImpl;
import com.freelasatende.service.impl.UpworkLoginDriverless;
import com.freelasatende.service.interfaceServico.AprendizadoService;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.ControleOperacionalService;
import com.freelasatende.service.interfaceServico.DashboardService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.HistoricoService;
import com.freelasatende.service.interfaceServico.MlLabService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato offline das rotas de tempo real e operacao do DashboardController:
 * GET /api/dashboard/logs/stream (SSE), /logs, /resumo, /ia/memoria,
 * POST /ia/memoria/regras, /health e /ops. Rotas de pagina, projetos,
 * conversas e /errors/recent ficam em DashboardControllerTest.
 *
 * A questao de autenticacao/origem local das rotas /api/dashboard e tratada pelo
 * ControleApiAuthInterceptor (cobrindo /api/** e /dashboard) e provada em
 * RotasApiVarreduraTest; aqui o foco e o contrato de cada rota com os servicos
 * mockados.
 *
 * ACHADOS de producao corrigidos (testes religados como prova):
 * - ACHADO-FA-API-01-1: GET /logs mascara segredo (RedatorSegredos).
 * - ACHADO-FA-API-01-3: corpo JSON malformado no POST /ia/memoria/regras
 *   devolve 400 (GlobalExceptionHandler.handleCorpoMalformado).
 */
@SpringBootTest(properties = {
    "scheduling.enabled=false",
    "ia.memoria-arquivo=target/test-memoria-dashboard-api.json"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardControllerParte2Test {

    @Autowired
    protected MockMvc mockMvc;

    @MockBean
    protected DashboardService dashboardService;

    @MockBean
    protected HistoricoService historicoService;

    @MockBean
    protected AvaliadorIaService avaliadorIaService;

    @MockBean
    protected SystemHealthTracker healthTracker;

    @MockBean
    protected MarketplaceGateway marketplaceGateway;

    @MockBean
    protected ControleOperacionalService controleOperacionalService;

    @MockBean
    protected TelegramServiceImpl telegramService;

    @MockBean
    protected GerenciadorConexoesService gerenciadorConexoesService;

    @MockBean
    protected AutenticacaoService autenticacaoService;

    @MockBean
    protected AprendizadoService aprendizadoService;

    @MockBean
    protected MlLabService mlLabService;

    @MockBean
    protected PollingScheduler pollingScheduler;

    @MockBean
    protected LoginAssistidoAutoTrigger loginAssistidoAutoTrigger;

    @MockBean
    protected LoginJavaDriverless loginJavaDriverless;

    @MockBean
    protected UpworkLoginDriverless upworkLoginDriverless;

    @MockBean
    protected FreelancerLoginDriverless freelancerLoginDriverless;

    @BeforeEach
    void prepararMocksEEstadoEstatico() {
        limparEstadoEstaticoDoController();
        reset(dashboardService, historicoService, avaliadorIaService, healthTracker,
            marketplaceGateway, controleOperacionalService, telegramService,
            gerenciadorConexoesService, autenticacaoService, aprendizadoService,
            mlLabService, pollingScheduler, loginAssistidoAutoTrigger,
            loginJavaDriverless, upworkLoginDriverless, freelancerLoginDriverless);

        when(dashboardService.getStats()).thenReturn(DashboardStatsDTO.builder().modoAtual("REGULAR").build());
        when(dashboardService.getProjetosRecentes(anyInt())).thenReturn(List.of());
        when(historicoService.buscarUltimas(anyInt())).thenReturn(List.of());
        when(avaliadorIaService.obterDiagnosticoMemoriaIa()).thenReturn(Map.of());
        when(healthTracker.getHealthCompleto()).thenReturn(Map.of());
        when(healthTracker.getErrosRecentes(anyInt())).thenReturn(List.of());
        when(controleOperacionalService.obterOverview()).thenReturn(ControleOverviewDTO.builder().build());
    }

    @AfterEach
    void limparEstadoEstatico() {
        limparEstadoEstaticoDoController();
    }

    @Test
    @DisplayName("logs/stream inicia SSE com text/event-stream e evento connected")
    void streamLogsIniciaSseComEventoConectado() throws Exception {
        MvcResult resultado = mockMvc.perform(get("/api/dashboard/logs/stream")
                .accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(request().asyncStarted())
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn();

        String corpo = resultado.getResponse().getContentAsString();
        assertTrue(corpo.contains("event:connected"), () -> "Evento inicial connected ausente: " + corpo);
        assertTrue(corpo.contains("Dashboard conectado"), () -> "Dados do evento connected ausentes: " + corpo);

        resultado.getRequest().getAsyncContext().complete();
    }

    @Test
    @DisplayName("logs/stream transmite broadcast de novo log ao cliente conectado")
    void streamLogsTransmiteBroadcastDeLog() throws Exception {
        MvcResult resultado = mockMvc.perform(get("/api/dashboard/logs/stream")
                .accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(request().asyncStarted())
            .andExpect(status().isOk())
            .andReturn();

        DashboardController.enviarLog("INFO", "mensagem-broadcast-sse-fa-api-01");

        String corpo = resultado.getResponse().getContentAsString();
        assertTrue(corpo.contains("event:log"), () -> "Evento de log ausente no stream: " + corpo);
        assertTrue(corpo.contains("mensagem-broadcast-sse-fa-api-01"),
            () -> "Mensagem transmitida ausente no stream: " + corpo);

        resultado.getRequest().getAsyncContext().complete();
    }

    @Test
    @DisplayName("logs devolve historico ordenado do mais recente com tipo classificado")
    void logsDevolveHistoricoOrdenadoComTipoClassificado() throws Exception {
        LocalDateTime agora = LocalDateTime.now();
        when(historicoService.buscarUltimas(50)).thenReturn(List.of(
            interacao(TipoInteracao.PROPOSTA_ENVIADA, "Landing antiga",
                "Proposta com valor 100", agora.minusHours(2)),
            interacao(TipoInteracao.LIMITE_ATINGIDO, "Raspador de dados",
                "Limite de conexoes atingido", agora.minusHours(1)),
            interacao(TipoInteracao.ERRO_SISTEMA, "Raspador de dados",
                "Falha ao enviar pergunta", agora.minusMinutes(30))));

        mockMvc.perform(get("/api/dashboard/logs"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$[0].tipo").value("ERROR"))
            .andExpect(jsonPath("$[0].mensagem", containsString("ERRO_SISTEMA")))
            .andExpect(jsonPath("$[0].mensagem", containsString("Raspador de dados")))
            .andExpect(jsonPath("$[0].mensagem", containsString("Falha ao enviar pergunta")))
            .andExpect(jsonPath("$[1].tipo").value("WARN"))
            .andExpect(jsonPath("$[2].tipo").value("INFO"))
            .andExpect(jsonPath("$[2].mensagem", containsString("Landing antiga")));
    }

    @Test
    @DisplayName("logs normaliza limite para o intervalo 1..200")
    void logsNormalizaLimiteParaOFaixaValida() throws Exception {
        mockMvc.perform(get("/api/dashboard/logs").param("limite", "5000"))
            .andExpect(status().isOk());
        verify(historicoService).buscarUltimas(200);

        mockMvc.perform(get("/api/dashboard/logs").param("limite", "0"))
            .andExpect(status().isOk());
        verify(historicoService).buscarUltimas(1);
    }

    @Test
    @DisplayName("logs devolve no maximo o limite pedido mesmo com mais historico")
    void logsRespeitamLimiteNaResposta() throws Exception {
        LocalDateTime agora = LocalDateTime.now();
        when(historicoService.buscarUltimas(2)).thenReturn(List.of(
            interacao(TipoInteracao.PROPOSTA_ENVIADA, "Projeto A", "msg A", agora.minusHours(3)),
            interacao(TipoInteracao.PROPOSTA_ENVIADA, "Projeto B", "msg B", agora.minusHours(2)),
            interacao(TipoInteracao.PROPOSTA_ENVIADA, "Projeto C", "msg C", agora.minusHours(1))));

        mockMvc.perform(get("/api/dashboard/logs").param("limite", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    @DisplayName("logs sem dados devolvem lista vazia sem erro")
    void logsSemDadosDevolvemListaVazia() throws Exception {
        mockMvc.perform(get("/api/dashboard/logs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("Logs do dashboard nao expõem token de teste (ACHADO-FA-API-01-1 corrigido)")
    void logsNaoExpoemSegredoDeTeste() throws Exception {
        when(historicoService.buscarUltimas(50)).thenReturn(List.of(
            interacao(TipoInteracao.ERRO_SISTEMA, "Diagnostico",
                "Falha de autenticacao com token=segredo-de-teste", LocalDateTime.now())));

        mockMvc.perform(get("/api/dashboard/logs"))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("segredo-de-teste"))));
    }

    @Test
    @DisplayName("resumo devolve stats, projetos recentes (5) e timestamp")
    void resumoDevolveStatsProjetosETimestamp() throws Exception {
        when(dashboardService.getStats()).thenReturn(DashboardStatsDTO.builder()
            .modoAtual("ENSINAMENTO").projetosProcessadosHoje(9).build());
        when(dashboardService.getProjetosRecentes(5)).thenReturn(List.of(
            Projeto.builder().id(1L).titulo("Landing page").build()));

        mockMvc.perform(get("/api/dashboard/resumo"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.stats.modoAtual").value("ENSINAMENTO"))
            .andExpect(jsonPath("$.stats.projetosProcessadosHoje").value(9))
            .andExpect(jsonPath("$.projetosRecentes[0].titulo").value("Landing page"))
            .andExpect(jsonPath("$.timestamp").exists());

        verify(dashboardService).getProjetosRecentes(5);
    }

    @Test
    @DisplayName("ia/memoria devolve diagnostico do AvaliadorIaService")
    void iaMemoriaDevolveDiagnosticoDoServico() throws Exception {
        when(avaliadorIaService.obterDiagnosticoMemoriaIa())
            .thenReturn(Map.of("saude", "OK", "totalRegras", 3));

        mockMvc.perform(get("/api/dashboard/ia/memoria"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.saude").value("OK"))
            .andExpect(jsonPath("$.totalRegras").value(3));
    }

    @Test
    @DisplayName("ia/memoria sem dados devolve resposta vazia sem erro")
    void iaMemoriaSemDadosDevolveRespostaVazia() throws Exception {
        mockMvc.perform(get("/api/dashboard/ia/memoria"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("POST ia/memoria/regras com corpo valido grava no servico")
    void regrasValidasSaoGravadasNoServico() throws Exception {
        mockMvc.perform(post("/api/dashboard/ia/memoria/regras")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"regras\":\"Quando faltar prazo, perguntar antes da proposta.\"}"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.mensagem").value("Regras salvas com sucesso!"));

        verify(avaliadorIaService).salvarRegrasConsolidadas("Quando faltar prazo, perguntar antes da proposta.");
    }

    @Test
    @DisplayName("POST ia/memoria/regras com falha do servico devolve 500 sem stack trace")
    void regrasComFalhaDoServicoDevolvem500SemStackTrace() throws Exception {
        doThrow(new IllegalStateException("falha simulada de gravacao"))
            .when(avaliadorIaService).salvarRegrasConsolidadas(anyString());

        mockMvc.perform(post("/api/dashboard/ia/memoria/regras")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"regras\":\"regra qualquer\"}"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.status").value("erro"))
            .andExpect(jsonPath("$.mensagem").value("falha simulada de gravacao"))
            .andExpect(content().string(not(containsString("IllegalStateException"))))
            .andExpect(content().string(not(containsString("at com.freelasatende"))));
    }

    @Test
    @DisplayName("Corpo JSON malformado em regras devolve 400 sem stack trace (ACHADO-FA-API-01-3 corrigido)")
    void regrasComCorpoMalformadoDevolve400SemStackTrace() throws Exception {
        mockMvc.perform(post("/api/dashboard/ia/memoria/regras")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"regras\": json quebrado"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(not(containsString("Exception"))))
            .andExpect(content().string(not(containsString("at com.freelasatende"))));
    }

    @Test
    @DisplayName("health devolve saude dos subsistemas do SystemHealthTracker")
    void healthDevolveSaudeDosSubsistemas() throws Exception {
        when(healthTracker.getHealthCompleto()).thenReturn(Map.of(
            "uptimeSeconds", 120L,
            "totalErrosHoje", 0,
            "subsistemas", Map.of("scraper", Map.of("status", "UNKNOWN", "errosHoje", 2))));

        mockMvc.perform(get("/api/dashboard/health"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.uptimeSeconds").value(120))
            .andExpect(jsonPath("$.totalErrosHoje").value(0))
            .andExpect(jsonPath("$.subsistemas.scraper.status").value("UNKNOWN"))
            .andExpect(jsonPath("$.subsistemas.scraper.errosHoje").value(2));
    }

    @Test
    @DisplayName("ops com trava ativa devolve modoSeguro, fila e alerta OK")
    void opsDevolveResumoOperacionalComTravaAtiva() throws Exception {
        when(controleOperacionalService.obterOverview()).thenReturn(ControleOverviewDTO.builder()
            .podeOperar(false)
            .motivoOperacao("Trava de escrita ativa")
            .escritaRealHabilitada(false)
            .simulacaoEnvioHabilitada(true)
            .sessaoValida(true)
            .totalPendencias(3)
            .totalBloqueios(1)
            .totalEnvios(0)
            .totalEnviosSimulados(2)
            .errosHoje(0)
            .auditoriaInboxResumo(Map.of("persistida", true))
            .build());

        mockMvc.perform(get("/api/dashboard/ops"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.modoSeguro").value(true))
            .andExpect(jsonPath("$.podeOperar").value(false))
            .andExpect(jsonPath("$.motivoOperacao").value("Trava de escrita ativa"))
            .andExpect(jsonPath("$.escrita.realHabilitada").value(false))
            .andExpect(jsonPath("$.escrita.simulacaoHabilitada").value(true))
            .andExpect(jsonPath("$.escrita.totalEnviosReais").value(0))
            .andExpect(jsonPath("$.escrita.totalEnviosSimulados").value(2))
            .andExpect(jsonPath("$.escrita.travaAtiva").value(true))
            .andExpect(jsonPath("$.fila.pendencias").value(3))
            .andExpect(jsonPath("$.fila.bloqueios").value(1))
            .andExpect(jsonPath("$.fila.errosHoje").value(0))
            .andExpect(jsonPath("$.fila.sessaoValida").value(true))
            .andExpect(jsonPath("$.auditoriaInbox.persistida").value(true))
            .andExpect(jsonPath("$.alertas[0].nivel").value("OK"))
            .andExpect(jsonPath("$.alertas[0].titulo").value("Trava de escrita ativa"))
            .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("ops alerta CRITICO com escrita real, sessao invalida e erros no dia")
    void opsAlertaEscritaRealSessaoInvalidaEErros() throws Exception {
        when(controleOperacionalService.obterOverview()).thenReturn(ControleOverviewDTO.builder()
            .podeOperar(true)
            .escritaRealHabilitada(true)
            .sessaoValida(false)
            .errosHoje(2)
            .build());

        mockMvc.perform(get("/api/dashboard/ops"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.modoSeguro").value(false))
            .andExpect(jsonPath("$.escrita.travaAtiva").value(false))
            .andExpect(jsonPath("$.alertas[0].nivel").value("CRITICO"))
            .andExpect(jsonPath("$.alertas[0].titulo").value("Escrita real habilitada"))
            .andExpect(jsonPath("$.alertas[1].nivel").value("WARN"))
            .andExpect(jsonPath("$.alertas[1].titulo").value("Sessao 99Freelas invalida"))
            .andExpect(jsonPath("$.alertas[2].nivel").value("WARN"))
            .andExpect(jsonPath("$.alertas[2].titulo").value("Erros registrados hoje"))
            .andExpect(jsonPath("$.alertas[3].nivel").value("INFO"))
            .andExpect(jsonPath("$.alertas[3].titulo").value("Auditoria de inbox pendente"));
    }

    @Test
    @DisplayName("ops alerta CRITICO quando auditoria aponta vazamento de contexto")
    void opsAlertaVazamentoDeContextoNaAuditoria() throws Exception {
        when(controleOperacionalService.obterOverview()).thenReturn(ControleOverviewDTO.builder()
            .escritaRealHabilitada(false)
            .sessaoValida(true)
            .errosHoje(0)
            .auditoriaInboxResumo(Map.of(
                "persistida", true,
                "painelCenarios", Map.of("vazouContexto", 2)))
            .build());

        mockMvc.perform(get("/api/dashboard/ops"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.alertas[0].nivel").value("OK"))
            .andExpect(jsonPath("$.alertas[1].nivel").value("CRITICO"))
            .andExpect(jsonPath("$.alertas[1].titulo").value("Possivel vazamento de contexto"));
    }

    @Test
    @DisplayName("ops sem dados devolve modoSeguro sem erro")
    void opsSemDadosDevolvemModoSeguroSemErro() throws Exception {
        mockMvc.perform(get("/api/dashboard/ops"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.modoSeguro").value(true))
            .andExpect(jsonPath("$.escrita.travaAtiva").value(true))
            .andExpect(jsonPath("$.alertas[0].nivel").value("OK"));
    }

    protected Interacao interacao(TipoInteracao tipo, String tituloProjeto, String conteudo, LocalDateTime data) {
        return Interacao.builder().projetoId(44L).projetoTitulo(tituloProjeto)
            .tipo(tipo).conteudo(conteudo).data(data).build();
    }

    private static void limparEstadoEstaticoDoController() {
        try {
            Field campoBuffer = DashboardController.class.getDeclaredField("LOG_BUFFER");
            campoBuffer.setAccessible(true);
            ((Deque<?>) campoBuffer.get(null)).clear();
            Field campoEmitters = DashboardController.class.getDeclaredField("EMITTERS");
            campoEmitters.setAccessible(true);
            ((List<?>) campoEmitters.get(null)).clear();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Nao foi possivel limpar estado estatico do DashboardController", e);
        }
    }
}
