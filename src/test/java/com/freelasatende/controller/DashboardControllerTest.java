package com.freelasatende.controller;

import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.DashboardStatsDTO;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.Projeto;
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

import java.lang.reflect.Field;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato offline das rotas de pagina e leitura do DashboardController:
 * GET /dashboard, /api/dashboard/stats, /projetos, /aprovados, /recusados,
 * /conversas, /conversas/{id}/mensagens e /errors/recent.
 *
 * A questao de autenticacao/origem local das rotas /api/dashboard e tratada pelo
 * ControleApiAuthInterceptor (cobrindo /api/** e /dashboard) e provada em
 * RotasApiVarreduraTest; aqui o foco e o contrato de cada rota com os servicos
 * mockados. Streaming SSE, logs, resumo, memoria de IA, health e ops ficam em
 * DashboardControllerParte2Test.
 *
 * ACHADOS de producao corrigidos (testes religados como prova):
 * - ACHADO-FA-API-01-2: id nao numerico em /conversas/{id}/mensagens devolve
 *   400 (GlobalExceptionHandler.handleTipoParametroInvalido).
 * - ACHADO-FA-API-01-4: GET /errors/recent mascara segredo (RedatorSegredos).
 */
@SpringBootTest(properties = {
    "scheduling.enabled=false",
    "ia.memoria-arquivo=target/test-memoria-dashboard-api.json"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardControllerTest {

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
        when(dashboardService.getProjetosAprovados(anyInt())).thenReturn(List.of());
        when(dashboardService.getProjetosRecusados(anyInt())).thenReturn(List.of());
        when(marketplaceGateway.listarConversasInbox()).thenReturn(List.of());
        when(marketplaceGateway.listarMensagensConversa(anyLong(), anyBoolean())).thenReturn(List.of());
        when(historicoService.buscarUltimas(anyInt())).thenReturn(List.of());
        when(avaliadorIaService.obterDiagnosticoMemoriaIa()).thenReturn(java.util.Map.of());
        when(healthTracker.getHealthCompleto()).thenReturn(java.util.Map.of());
        when(healthTracker.getErrosRecentes(anyInt())).thenReturn(List.of());
        when(controleOperacionalService.obterOverview()).thenReturn(
            com.freelasatende.model.dto.ControleOverviewDTO.builder().build());
    }

    @AfterEach
    void limparEstadoEstatico() {
        limparEstadoEstaticoDoController();
    }

    @Test
    @DisplayName("Pagina /dashboard renderiza abordagem inicial e app do centro de controle")
    void dashboardPaginaRenderizaAbordagemInicial() throws Exception {
        mockMvc.perform(get("/dashboard"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("controlCenterApp")))
            .andExpect(content().string(containsString("data-abordagem-inicial=\"Ja foi atendido?\"")));
    }

    @Test
    @DisplayName("/stats devolve estatisticas preenchidas pelo DashboardService")
    void statsDevolveEstatisticasDoServico() throws Exception {
        when(dashboardService.getStats()).thenReturn(DashboardStatsDTO.builder()
            .modoAtual("REGULAR")
            .conexoesUsadas(12)
            .conexoesLimite(50)
            .projetosProcessadosHoje(7)
            .projetosAprovadosHoje(5)
            .projetosRecusadosHoje(2)
            .status99Freelas("ONLINE")
            .telegramConectado(true)
            .errosHoje(1)
            .build());

        mockMvc.perform(get("/api/dashboard/stats"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.modoAtual").value("REGULAR"))
            .andExpect(jsonPath("$.conexoesUsadas").value(12))
            .andExpect(jsonPath("$.conexoesLimite").value(50))
            .andExpect(jsonPath("$.projetosProcessadosHoje").value(7))
            .andExpect(jsonPath("$.projetosAprovadosHoje").value(5))
            .andExpect(jsonPath("$.projetosRecusadosHoje").value(2))
            .andExpect(jsonPath("$.status99Freelas").value("ONLINE"))
            .andExpect(jsonPath("$.telegramConectado").value(true))
            .andExpect(jsonPath("$.errosHoje").value(1));
    }

    @Test
    @DisplayName("/stats sem dados devolve zeros e false sem erro")
    void statsSemDadosDevolveZerosSemErro() throws Exception {
        mockMvc.perform(get("/api/dashboard/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.modoAtual").value("REGULAR"))
            .andExpect(jsonPath("$.conexoesUsadas").value(0))
            .andExpect(jsonPath("$.projetosProcessadosHoje").value(0))
            .andExpect(jsonPath("$.projetosAprovadosHoje").value(0))
            .andExpect(jsonPath("$.projetosRecusadosHoje").value(0))
            .andExpect(jsonPath("$.telegramConectado").value(false))
            .andExpect(jsonPath("$.errosHoje").value(0));
    }

    @Test
    @DisplayName("/projetos devolve projetos recentes com campos principais")
    void projetosRecentesDevolveProjetosComCampos() throws Exception {
        when(dashboardService.getProjetosRecentes(20)).thenReturn(List.of(projetoTeste()));

        mockMvc.perform(get("/api/dashboard/projetos"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].titulo").value("Landing page"))
            .andExpect(jsonPath("$[0].nomeCliente").value("Cliente Teste"));

        verify(dashboardService).getProjetosRecentes(20);
    }

    @Test
    @DisplayName("/projetos repassa o limite ao servico (padrao 20, custom 7)")
    void projetosRecentesRepassaLimiteAoServico() throws Exception {
        mockMvc.perform(get("/api/dashboard/projetos"))
            .andExpect(status().isOk());
        verify(dashboardService).getProjetosRecentes(20);

        mockMvc.perform(get("/api/dashboard/projetos").param("limite", "7"))
            .andExpect(status().isOk());
        verify(dashboardService).getProjetosRecentes(7);
    }

    @Test
    @DisplayName("/projetos sem dados devolve lista vazia sem erro")
    void projetosRecentesSemDadosDevolveListaVazia() throws Exception {
        mockMvc.perform(get("/api/dashboard/projetos"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("/aprovados devolve projetos com limite padrao 50")
    void aprovadosDevolveProjetosComLimitePadrao50() throws Exception {
        when(dashboardService.getProjetosAprovados(50)).thenReturn(List.of(projetoTeste()));

        mockMvc.perform(get("/api/dashboard/aprovados"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].titulo").value("Landing page"));

        verify(dashboardService).getProjetosAprovados(50);
    }

    @Test
    @DisplayName("/recusados devolve projetos com motivo da recusa")
    void recusadosDevolveMotivoDaRecusa() throws Exception {
        when(dashboardService.getProjetosRecusados(50)).thenReturn(List.of(
            Projeto.builder().id(2L).titulo("App de delivery").motivoRecusa("Fora do escopo").build()));

        mockMvc.perform(get("/api/dashboard/recusados"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$[0].titulo").value("App de delivery"))
            .andExpect(jsonPath("$[0].motivoRecusa").value("Fora do escopo"));

        verify(dashboardService).getProjetosRecusados(50);
    }

    @Test
    @DisplayName("/aprovados e /recusados sem dados devolvem listas vazias")
    void aprovadosERecusadosSemDadosDevolvemListasVazias() throws Exception {
        mockMvc.perform(get("/api/dashboard/aprovados"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(get("/api/dashboard/recusados"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("/conversas devolve resumo da inbox com plataforma")
    void conversasDevolveResumoDaInbox() throws Exception {
        when(marketplaceGateway.listarConversasInbox()).thenReturn(List.of(
            ConversaResumo.builder()
                .idConversa(77L)
                .idProjeto(44L)
                .nomeProjeto("Landing page")
                .nomeCliente("Cliente Teste")
                .nomeFreelancer("Operador")
                .ultimaMensagem("Ola, tudo bem?")
                .visualizada(false)
                .readOnly(false)
                .build()));

        mockMvc.perform(get("/api/dashboard/conversas"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$[0].idConversa").value(77))
            .andExpect(jsonPath("$[0].idProjeto").value(44))
            .andExpect(jsonPath("$[0].nomeProjeto").value("Landing page"))
            .andExpect(jsonPath("$[0].nomeCliente").value("Cliente Teste"))
            .andExpect(jsonPath("$[0].ultimaMensagem").value("Ola, tudo bem?"))
            .andExpect(jsonPath("$[0].visualizada").value(false))
            .andExpect(jsonPath("$[0].plataforma").exists());
    }

    @Test
    @DisplayName("/conversas sem dados devolve lista vazia sem erro")
    void conversasSemDadosDevolvemListaVazia() throws Exception {
        mockMvc.perform(get("/api/dashboard/conversas"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("/conversas/{id}/mensagens devolve mensagens da conversa")
    void mensagensDevolveMensagensDaConversa() throws Exception {
        when(marketplaceGateway.listarMensagensConversa(77L, false)).thenReturn(List.of(
            MensagemConversa.builder()
                .id(1L)
                .idConversa(77L)
                .nomePessoa("Cliente Teste")
                .texto("Ola, tudo bem?")
                .enviadaPorMim(false)
                .visualizada(false)
                .build()));

        mockMvc.perform(get("/api/dashboard/conversas/77/mensagens"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].idConversa").value(77))
            .andExpect(jsonPath("$[0].nomePessoa").value("Cliente Teste"))
            .andExpect(jsonPath("$[0].texto").value("Ola, tudo bem?"))
            .andExpect(jsonPath("$[0].enviadaPorMim").value(false));

        verify(marketplaceGateway).listarMensagensConversa(77L, false);
    }

    @Test
    @DisplayName("/conversas/{id}/mensagens?visualizar=true repassa a flag ao servico")
    void mensagensRepassaFlagVisualizarAoServico() throws Exception {
        mockMvc.perform(get("/api/dashboard/conversas/77/mensagens").param("visualizar", "true"))
            .andExpect(status().isOk());

        verify(marketplaceGateway).listarMensagensConversa(77L, true);
    }

    @Test
    @DisplayName("Mensagens de conversa inexistente devolvem lista vazia sem erro")
    void mensagensDeConversaInexistenteDevolvemListaVazia() throws Exception {
        when(marketplaceGateway.listarMensagensConversa(999999L, false)).thenReturn(List.of());

        mockMvc.perform(get("/api/dashboard/conversas/999999/mensagens"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());

        verify(marketplaceGateway).listarMensagensConversa(999999L, false);
    }

    @Test
    @DisplayName("Id nao numerico em mensagens devolve 400 sem stack trace (ACHADO-FA-API-01-2 corrigido)")
    void mensagensComIdNaoNumericoDevolvem400SemStackTrace() throws Exception {
        mockMvc.perform(get("/api/dashboard/conversas/nao-numerico/mensagens"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(not(containsString("Exception"))))
            .andExpect(content().string(not(containsString("at com.freelasatende"))));
    }

    @Test
    @DisplayName("errors/recent devolve erros do SystemHealthTracker")
    void errorsRecentDevolvemErrosDoHealthTracker() throws Exception {
        when(healthTracker.getErrosRecentes(20)).thenReturn(List.of(
            Map.of("errorId", "abc12345", "subsistema", "SCRAPER",
                "mensagem", "Falha ao carregar sessao", "timestamp", "2026-09-30T10:00:00")));

        mockMvc.perform(get("/api/dashboard/errors/recent"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$[0].errorId").value("abc12345"))
            .andExpect(jsonPath("$[0].subsistema").value("SCRAPER"))
            .andExpect(jsonPath("$[0].mensagem").value("Falha ao carregar sessao"))
            .andExpect(jsonPath("$[0].timestamp").value("2026-09-30T10:00:00"));
    }

    @Test
    @DisplayName("errors/recent sem dados devolve lista vazia sem erro")
    void errorsRecentSemDadosDevolvemListaVazia() throws Exception {
        mockMvc.perform(get("/api/dashboard/errors/recent"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("Erros recentes nao expõem token de teste (ACHADO-FA-API-01-4 corrigido)")
    void errorsRecentNaoExpoemSegredoDeTeste() throws Exception {
        when(healthTracker.getErrosRecentes(20)).thenReturn(List.of(
            Map.of("errorId", "err-1", "subsistema", "SCRAPER",
                "mensagem", "Erro de autenticacao com token=segredo-de-teste",
                "timestamp", "2026-09-30T10:00:00")));

        mockMvc.perform(get("/api/dashboard/errors/recent"))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("segredo-de-teste"))));
    }

    protected Projeto projetoTeste() {
        return Projeto.builder()
            .id(1L)
            .idExterno(424242L)
            .titulo("Landing page")
            .nomeCliente("Cliente Teste")
            .build();
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
