package com.freelasatende.controller;

import com.freelasatende.model.dto.ControleOverviewDTO;
import com.freelasatende.model.dto.DashboardStatsDTO;
import com.freelasatende.model.enums.ModoSistema;
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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Varredura de controle de acesso de TODAS as rotas /api/** e da pagina GET /dashboard
 * quando a requisicao vem de um cliente REMOTO (203.0.113.10), sem token
 * X-Control-Token/Authorization e sem cabecalhos X-Forwarded-For/X-Real-IP.
 *
 * Regra do sistema (app de um unico operador): toda rota /api/** e a pagina /dashboard
 * devem exigir origem local real ou token — ou seja, devolver 401/403 para cliente remoto.
 * Hoje o ControleApiAuthInterceptor (registrado em config/WebMvcConfig.addInterceptors)
 * so protege /api/controle/**, /api/modos/** e /api/ml-lab/**.
 *
 * Testes:
 * - nenhumaRotaAbertaNovaAlemDasConhecidas (ATIVO): quebra se surgir rota aberta fora de
 *   ROTAS_ABERTAS_ACHADO (comando: mvn -Dtest='RotasApiVarreduraTest' test).
 * - todaRotaApiExigeOrigemLocalOuToken (@Disabled ACHADO-FA-SEC-ROTAS-01-1): descreve o
 *   comportamento correto desejado — TODAS as rotas devolvem 401/403 para cliente remoto.
 * - rotasDosPrefixosProtegidosDevolvem403ParaClienteRemoto (ATIVO): sanidade da protecao
 *   atual; quebra se a protecao dos 3 prefixos existentes deixar de funcionar.
 * - streamSseDeLogsAbreAssincronoERegistraUmEmitter (ATIVO): cobre a rota
 *   SSE a parte — abre o stream com remoteAddr remoto (rota aberta = ACHADO-FA-SEC-ROTAS-01-1
 *   ja documentado), confere asyncStarted e 1 emitter novo e, no finally, limpa o mapa
 *   estatico EMITTERS (o MockMvc nao dispara o onCompletion; a remocao nao e provada aqui). A rota
 *   /api/dashboard/logs/stream fica FORA do laco HTTP da varredura: com o emitter SSE
 *   aberto o resultado assincrono nunca e setado e asyncDispatch/getAsyncResult travam
 *   a JVM (rodada 2 da fatia FA-REV-01).
 *
 * Seguranca do proprio teste: TODOS os servicos/clients injetados nos 7 controllers sao
 * @MockBean — nenhuma chamada real (login em marketplace, gravacao de cookies, Telegram,
 * IA, navegador) acontece aqui; o teste verifica apenas a camada de acesso. Bancos usam
 * H2 em memoria (application-test.yml) e nao ha rede externa.
 */
@SpringBootTest(properties = {
    "scheduling.enabled=false",
    "ia.memoria-arquivo=target/test-memoria-varredura-rotas.json"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RotasApiVarreduraTest {

    /** IP de teste nao roteavel (TEST-NET-3) simulando cliente de fora da maquina. */
    private static final String IP_REMOTO = "203.0.113.10";

    /**
     * Rota SSE de logs: NAO entra no laco HTTP da varredura (emitter SSE aberto nunca
     * termina e asyncDispatch/getAsyncResult travam a JVM); tem teste dedicado e segue
     * listada em ROTAS_ABERTAS_ACHADO.
     */
    private static final String ROTA_SSE_LOGS = "GET /api/dashboard/logs/stream";

    /**
     * Prefixos protegidos hoje por ControleApiAuthInterceptor
     * (config/WebMvcConfig.addInterceptors: /api/controle/**, /api/modos/**, /api/ml-lab/**).
     */
    private static final List<String> PREFIXOS_PROTEGIDOS_HOJE =
        List.of("/api/controle", "/api/modos", "/api/ml-lab");

    /**
     * Rotas que HOJE respondem diferente de 401/403 para cliente remoto — formato
     * "METODO padrao" (padrao exatamente como registrado no RequestMappingHandlerMapping).
     * Cada entrada deveria exigir origem local ou token (ACHADO-FA-SEC-ROTAS-01-1);
     * enquanto a correcao nao sai, uma rota aberta NOVA quebrar
     * nenhumaRotaAbertaNovaAlemDasConhecidas em vez de passar silenciosamente.
     */
    private static final Set<String> ROTAS_ABERTAS_ACHADO = Set.of(
        // StatusController — diagnostico e disparo de login sem protecao
        "GET /api/status",                              // expoe status de login/conexoes do operador
        "POST /api/telegram/test",                      // envia mensagem real no Telegram
        "POST /api/auth/login",                         // renova sessao do marketplace
        "POST /api/auth/login-assistido",               // dispara login de navegador (driverless)
        "POST /api/auth/upwork/login-assistido",        // dispara login Upwork driverless
        "POST /api/auth/freelancer/login-assistido",    // dispara login Freelancer driverless
        "POST /api/auth/cookies",                       // grava cookies de sessao enviados no corpo
        "GET /api/auth/session",                        // dispara auto-login se sessao invalida
        "GET /api/conexoes",                            // expoe status de conexoes
        "GET /api/debug/telegram",                      // expoe configuracao do bot (tamanhos dos valores)
        "GET /api/acoes-pendentes",                     // le dados operacionais pendentes
        "GET /api/acoes-pendentes/count",               // le contagem de pendencias
        "GET /api/mensagens-cliente-registro",          // le registro de mensagens a clientes
        // DashboardController — pagina e dados em tempo real sem protecao
        "GET /dashboard",                               // pagina do painel operacional
        "GET /api/dashboard/stats",                     // estatisticas do sistema
        "GET /api/dashboard/projetos",                  // projetos recentes
        "GET /api/dashboard/aprovados",                 // projetos aprovados
        "GET /api/dashboard/recusados",                 // projetos recusados
        "GET /api/dashboard/conversas",                 // inbox de todos os marketplaces
        "GET /api/dashboard/conversas/{id}/mensagens",  // mensagens de uma conversa
        "GET /api/dashboard/logs/stream",               // stream SSE de logs
        "GET /api/dashboard/logs",                      // logs recentes
        "GET /api/dashboard/resumo",                    // resumo para widgets
        "GET /api/dashboard/ia/memoria",                // diagnostico da memoria da IA
        "POST /api/dashboard/ia/memoria/regras",        // grava regras consolidadas da IA
        "GET /api/dashboard/health",                    // saude de cada subsistema
        "GET /api/dashboard/ops",                       // resumo operacional (travas e fila)
        "GET /api/dashboard/errors/recent",             // erros recentes
        // ProjetoController — leitura e mutacao de projetos sem protecao
        "GET /api/projetos",                            // lista todos os projetos
        "GET /api/projetos/{id}",                       // detalhe de projeto
        "GET /api/projetos/nao-processados",            // projetos nao processados
        "POST /api/projetos/{id}/ignorar",              // marca projeto como IGNORADO
        // AprendizadoController — memoria de aprendizado sem protecao
        "GET /api/aprendizados",                        // historico de aprendizados
        "GET /api/aprendizados/regras",                 // regras aprendidas
        "POST /api/aprendizados"                        // registra decisao/aprendizado
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    // Servicos/clients dos 7 controllers — todos mockados: nenhuma chamada real acontece.
    @MockBean
    private MarketplaceGateway marketplaceGateway;

    @MockBean
    private TelegramServiceImpl telegramService;

    @MockBean
    private GerenciadorConexoesService gerenciadorConexoesService;

    @MockBean
    private AutenticacaoService autenticacaoService;

    @MockBean
    private AvaliadorIaService avaliadorIaService;

    @MockBean
    private DashboardService dashboardService;

    @MockBean
    private HistoricoService historicoService;

    @MockBean
    private ControleOperacionalService controleOperacionalService;

    @MockBean
    private AprendizadoService aprendizadoService;

    @MockBean
    private MlLabService mlLabService;

    @MockBean
    private PollingScheduler pollingScheduler;

    @MockBean
    private SystemHealthTracker systemHealthTracker;

    @MockBean
    private LoginAssistidoAutoTrigger loginAssistidoAutoTrigger;

    @MockBean
    private LoginJavaDriverless loginJavaDriverless;

    @MockBean
    private UpworkLoginDriverless upworkLoginDriverless;

    @MockBean
    private FreelancerLoginDriverless freelancerLoginDriverless;

    @BeforeEach
    void prepararMockDosServicos() {
        reset(marketplaceGateway, telegramService, gerenciadorConexoesService, autenticacaoService,
            avaliadorIaService, dashboardService, historicoService, controleOperacionalService,
            aprendizadoService, mlLabService, pollingScheduler, systemHealthTracker,
            loginAssistidoAutoTrigger, loginJavaDriverless, upworkLoginDriverless,
            freelancerLoginDriverless);

        // Respostas "nice" apenas para a requisicao alcançar o handler sem NPE;
        // o que o teste avalia e somente o status do controle de acesso.
        when(marketplaceGateway.verificarLogins()).thenReturn(Map.of("99FREELAS", false));
        when(marketplaceGateway.verificarLoginPadrao()).thenReturn(false);
        when(marketplaceGateway.listarConversasInbox()).thenReturn(List.of());
        when(marketplaceGateway.listarMensagensConversa(anyLong(), anyBoolean())).thenReturn(List.of());
        when(gerenciadorConexoesService.getStatusConexoes()).thenReturn("mock-teste");
        when(gerenciadorConexoesService.temConexoesDisponiveis()).thenReturn(false);
        when(autenticacaoService.verificarSessaoValida()).thenReturn(false);
        when(avaliadorIaService.obterDiagnosticoMemoriaIa()).thenReturn(Map.of("saude", "OK"));
        when(dashboardService.getStats()).thenReturn(DashboardStatsDTO.builder().modoAtual("REGULAR").build());
        when(dashboardService.getProjetosRecentes(anyInt())).thenReturn(List.of());
        when(historicoService.buscarUltimas(anyInt())).thenReturn(List.of());
        when(controleOperacionalService.obterOverview()).thenReturn(ControleOverviewDTO.builder().build());
        when(aprendizadoService.buscarHistorico()).thenReturn(List.of());
        when(aprendizadoService.buscarRegrasAprendidas()).thenReturn(List.of());
        when(pollingScheduler.getModoAtual()).thenReturn(ModoSistema.REGULAR);
    }

    @AfterEach
    void garantirMapaEmittersVazio() throws Exception {
        // Encerramento de seguranca (mesmo padrao dos testes do DashboardController):
        // nenhum teste desta classe pode deixar emitter residual recebendo broadcasts.
        Field campo = DashboardController.class.getDeclaredField("EMITTERS");
        campo.setAccessible(true);
        ((Collection<?>) campo.get(null)).clear();
    }

    @Test
    @DisplayName("Nenhuma rota /api/** ou /dashboard abre para cliente remoto alem das conhecidas em ROTAS_ABERTAS_ACHADO")
    void nenhumaRotaAbertaNovaAlemDasConhecidas() {
        List<RotaVarredura> rotas = rotasAlvoDaVarredura();
        assertTrue(rotas.size() >= 30,
            () -> "A varredura deveria encontrar dezenas de rotas /api/** e /dashboard registradas, encontrou: " + rotas);

        List<String> novas = rotasAbertas(rotas).entrySet().stream()
            .filter(entrada -> !ROTAS_ABERTAS_ACHADO.contains(entrada.getKey().toString()))
            .map(entrada -> entrada.getKey() + " -> " + entrada.getValue() + " (esperado 401/403)")
            .toList();

        assertTrue(novas.isEmpty(), () ->
            "NOVAS rotas acessiveis por cliente remoto (203.0.113.10, sem token, sem X-Forwarded-For) "
            + "fora de ROTAS_ABERTAS_ACHADO — proteja no ControleApiAuthInterceptor ou justifique na constante:\n  "
            + String.join("\n  ", novas));

        List<String> obsoletas = ROTAS_ABERTAS_ACHADO.stream()
            .filter(conhecida -> rotas.stream().noneMatch(rota -> rota.toString().equals(conhecida)))
            .toList();

        assertTrue(obsoletas.isEmpty(), () ->
            "ROTAS_ABERTAS_ACHADO cita rotas que nao existem mais no RequestMappingHandlerMapping "
            + "(remover da constante):\n  " + String.join("\n  ", obsoletas));
    }

    @Test
    @Disabled("ACHADO-FA-SEC-ROTAS-01-1: rotas /api sem protecao: ver ROTAS_ABERTAS_ACHADO")
    @DisplayName("Toda rota /api/** e /dashboard devolve 401/403 para cliente remoto (comportamento correto desejado)")
    void todaRotaApiExigeOrigemLocalOuToken() {
        List<RotaVarredura> rotas = rotasAlvoDaVarredura();

        List<String> violacoes = rotas.stream()
            .filter(rota -> !rota.toString().equals(ROTA_SSE_LOGS)) // SSE: teste dedicado (laco travaria a JVM)
            .map(rota -> Map.entry(rota, statusComoClienteRemoto(rota)))
            .filter(entrada -> entrada.getValue() != 401 && entrada.getValue() != 403)
            .map(entrada -> entrada.getKey() + " -> " + entrada.getValue() + " (esperado 401/403)")
            .toList();

        assertTrue(violacoes.isEmpty(), () ->
            "Rotas que ainda aceitam cliente remoto (203.0.113.10, sem token, sem X-Forwarded-For):\n  "
            + String.join("\n  ", violacoes));
    }

    @Test
    @DisplayName("Rotas atuais dos prefixos protegidos (/api/controle, /api/modos, /api/ml-lab) devolvem 403 para cliente remoto")
    void rotasDosPrefixosProtegidosDevolvem403ParaClienteRemoto() {
        List<RotaVarredura> protegidas = rotasAlvoDaVarredura().stream()
            .filter(rota -> PREFIXOS_PROTEGIDOS_HOJE.stream().anyMatch(rota.padrao()::startsWith))
            .toList();

        assertTrue(protegidas.size() >= 30,
            () -> "A varredura deveria encontrar as rotas dos 3 prefixos protegidos, encontrou: " + protegidas);

        List<String> falhas = protegidas.stream()
            .map(rota -> Map.entry(rota, statusComoClienteRemoto(rota)))
            .filter(entrada -> entrada.getValue() != 403)
            .map(entrada -> entrada.getKey() + " -> " + entrada.getValue()
                + " (esperado 403 do ControleApiAuthInterceptor)")
            .toList();

        assertTrue(falhas.isEmpty(), () ->
            "Rotas de prefixos que deveriam estar protegidos responderam diferente de 403 "
                + "para cliente remoto:\n  " + String.join("\n  ", falhas));
    }

    @Test
    @Timeout(30)
    @DisplayName("Stream SSE de logs abre assincrono e registra exatamente um emitter")
    void streamSseDeLogsAbreAssincronoERegistraUmEmitter() throws Exception {
        List<SseEmitter> emittersAntes = emittersRegistrados();

        try {
            MvcResult resultado = mockMvc.perform(MockMvcRequestBuilders
                    .get("/api/dashboard/logs/stream")
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .with(request -> {
                        request.setRemoteAddr(IP_REMOTO);
                        return request;
                    }))
                .andReturn();

            // Rota acessivel a cliente remoto = ACHADO-FA-SEC-ROTAS-01-1, ja documentado
            // em ROTAS_ABERTAS_ACHADO; aqui so interessa o ciclo de vida do emitter.
            assertTrue(resultado.getRequest().isAsyncStarted(),
                "GET /api/dashboard/logs/stream deve abrir requisicao assincrona (SSE)");
            assertEquals(emittersAntes.size() + 1, emittersRegistrados().size(),
                "A requisicao SSE deve registrar exatamente 1 novo emitter no mapa estatico do controller");
        } finally {
            // So limpeza: o MockMvc nao dispara o onCompletion do container, entao este teste
            // NAO prova a remocao pelo controller (asserir depois da limpeza seria tautologia).
            encerrarEmittersNovos(emittersAntes);
        }
    }

    private List<RotaVarredura> rotasAlvoDaVarredura() {
        List<RotaVarredura> rotas = new ArrayList<>();

        handlerMapping.getHandlerMethods().forEach((mapeamento, handler) -> {
            Set<String> padroes = padroesDoMapeamento(mapeamento);
            Set<RequestMethod> metodosHttp = mapeamento.getMethodsCondition().getMethods();
            Set<RequestMethod> metodosEfetivos =
                metodosHttp.isEmpty() ? EnumSet.of(RequestMethod.GET) : metodosHttp;

            for (String padrao : padroes) {
                boolean alvo = padrao.startsWith("/api/") || padrao.equals("/dashboard");
                if (!alvo) {
                    continue;
                }
                for (RequestMethod metodo : metodosEfetivos) {
                    rotas.add(new RotaVarredura(metodo, padrao));
                }
            }
        });

        rotas.sort(Comparator.comparing(RotaVarredura::toString));
        return rotas;
    }

    private Set<String> padroesDoMapeamento(RequestMappingInfo mapeamento) {
        Set<String> padroes = new LinkedHashSet<>();
        if (mapeamento.getPathPatternsCondition() != null) {
            padroes.addAll(mapeamento.getPathPatternsCondition().getPatternValues());
        }
        if (mapeamento.getPatternsCondition() != null) {
            padroes.addAll(mapeamento.getPatternsCondition().getPatterns());
        }
        return padroes;
    }

    /**
     * Executa a requisicao como cliente remoto (IP 203.0.113.10), sem token e sem
     * X-Forwarded-For/X-Real-IP, preenchendo variaveis de path com "1" e corpo JSON
     * minimo em POST/PUT/PATCH. Excecao que escapa da camada web equivale a 500
     * (o controle de acesso nao barrou) — devolvida como status 500 aberto.
     *
     * Emitter criado por requisicao assincrona eventual e encerrado no finally
     * (complete() dispara na hora o onCompletion que o remove do mapa estatico
     * EMITTERS); a rota SSE de logs NAO passa por aqui (teste dedicado proprio).
     */
    private int statusComoClienteRemoto(RotaVarredura rota) {
        String caminho = rota.padrao().replaceAll("\\{[^}]+}", "1");

        MockHttpServletRequestBuilder requisicao =
            MockMvcRequestBuilders.request(HttpMethod.valueOf(rota.metodo().name()), caminho)
                .with(request -> {
                    request.setRemoteAddr(IP_REMOTO);
                    return request;
                });

        if (rota.metodo() == RequestMethod.POST
            || rota.metodo() == RequestMethod.PUT
            || rota.metodo() == RequestMethod.PATCH) {
            requisicao.contentType(MediaType.APPLICATION_JSON).content("{}");
        }

        List<SseEmitter> emittersAntes = emittersRegistrados();
        try {
            return mockMvc.perform(requisicao).andReturn().getResponse().getStatus();
        } catch (Exception falhaNaCamadaWeb) {
            return 500;
        } finally {
            encerrarEmittersNovos(emittersAntes);
        }
    }

    /**
     * Completa qualquer emitter criado pela requisicao que tenha ficado no mapa
     * EMITTERS — o complete() dispara na hora o onCompletion do controller, que o
     * remove. Propositalmente NAO usa asyncDispatch/getAsyncResult: com o emitter
     * SSE aberto o resultado assincrono nunca e setado e a espera pelo fim do
     * dispatch travou a JVM (rodada 2 da fatia FA-REV-01).
     */
    private void encerrarEmittersNovos(List<SseEmitter> emittersAntes) {
        for (SseEmitter emitter : emittersRegistrados()) {
            if (!emittersAntes.contains(emitter)) {
                try {
                    emitter.complete();
                } catch (Exception emitterJaFechado) {
                    // onCompletion do controller ja removeu o emitter do mapa
                }
            }
        }
        // MockMvc nao dispara o onCompletion do container: remove do mapa REAL o que este teste criou.
        try {
            Field campo = DashboardController.class.getDeclaredField("EMITTERS");
            campo.setAccessible(true);
            ((Collection<?>) campo.get(null)).removeIf(e -> !emittersAntes.contains(e));
        } catch (ReflectiveOperationException erro) {
            throw new IllegalStateException("Nao foi possivel limpar EMITTERS do DashboardController", erro);
        }
    }

    /**
     * Copia do mapa estatico EMITTERS do DashboardController (sem API publica).
     * Usado para garantir que a varredura nao deixe emitter vazando entre testes.
     */
    @SuppressWarnings("unchecked")
    private List<SseEmitter> emittersRegistrados() {
        try {
            Field campo = DashboardController.class.getDeclaredField("EMITTERS");
            campo.setAccessible(true);
            return new ArrayList<>((Collection<SseEmitter>) campo.get(null));
        } catch (ReflectiveOperationException erro) {
            throw new IllegalStateException("Nao foi possivel ler EMITTERS do DashboardController", erro);
        }
    }

    private Map<RotaVarredura, Integer> rotasAbertas(List<RotaVarredura> rotas) {
        Map<RotaVarredura, Integer> abertas = new LinkedHashMap<>();
        for (RotaVarredura rota : rotas) {
            if (rota.toString().equals(ROTA_SSE_LOGS)) {
                // Fora do laco: emitter SSE aberto nunca termina e asyncDispatch/getAsyncResult
                // travam a JVM; a rota segue listada em ROTAS_ABERTAS_ACHADO e tem teste dedicado
                // (streamSseDeLogsAbreAssincronoERegistraUmEmitter).
                continue;
            }
            int status = statusComoClienteRemoto(rota);
            if (status != 401 && status != 403) {
                abertas.put(rota, status);
            }
        }
        return abertas;
    }

    private record RotaVarredura(RequestMethod metodo, String padrao) {
        @Override
        public String toString() {
            return metodo + " " + padrao;
        }
    }
}
