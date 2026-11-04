package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.dto.MlLabDtos;
import com.freelasatende.model.entity.Aprendizado;
import com.freelasatende.model.entity.MlLabAgent;
import com.freelasatende.model.entity.MlLabCycle;
import com.freelasatende.model.entity.MlLabRuleCandidate;
import com.freelasatende.model.entity.MlLabRun;
import com.freelasatende.model.enums.MlLabCandidateStatus;
import com.freelasatende.model.enums.MlLabRunStatus;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.MotivoAprendizado;
import com.freelasatende.model.enums.StatusRegraAprendida;
import com.freelasatende.policy.OperationalSafetyPolicy;
import com.freelasatende.repository.MlLabAgentRepository;
import com.freelasatende.repository.MlLabCycleRepository;
import com.freelasatende.repository.MlLabRuleCandidateRepository;
import com.freelasatende.repository.MlLabRunRepository;
import com.freelasatende.service.interfaceServico.AprendizadoService;
import com.freelasatende.service.interfaceServico.IaProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testes unitarios das transicoes do MlLabServiceImpl (start com IA real em background,
 * transicoes invalidas/achados, chat, aprovacao de candidata, overview). Repositorios
 * mockados; nenhuma chamada real de IA e nenhuma escrita externa.
 */
@ExtendWith(MockitoExtension.class)
class MlLabServiceImplTransicoesTest {

    private static final long RUN_ID = 7L;
    private static final long CANDIDATA_ID = 55L;

    @TempDir
    Path tempDir;

    @Mock private MlLabRunRepository runRepository;
    @Mock private MlLabAgentRepository agentRepository;
    @Mock private MlLabCycleRepository cycleRepository;
    @Mock private MlLabRuleCandidateRepository candidateRepository;
    @Mock private AprendizadoService aprendizadoService;
    @Mock private IaProvider iaProvider;
    @Mock private MlLabCorpusService corpusService;

    private MlLabServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MlLabServiceImpl(
            runRepository,
            agentRepository,
            cycleRepository,
            candidateRepository,
            aprendizadoService,
            iaProvider,
            new ObjectMapper(),
            new OperationalSafetyPolicy(),
            corpusService
        );
        ReflectionTestUtils.setField(service, "artifactRoot", tempDir.resolve("artifacts").toString());
        ReflectionTestUtils.setField(service, "defaultRealIaEnabled", false);
    }

    @Test
    @DisplayName("start com IA real agenda execucao somente apos commit e recusa start duplo")
    void iniciarRunIaRealExecutaSomenteAposCommitERecusaStartDuplo() throws Exception {
        MlLabRun run = run(MlLabRunStatus.CRIADO, true);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(agentRepository.countByRunId(RUN_ID)).thenReturn(30L);
        when(iaProvider.estaDisponivel()).thenReturn(true);
        when(corpusService.carregar()).thenReturn(new MlLabCorpusService.Snapshot("Resumo de teste", List.of()));
        ExecutorCapturador executorControlado = new ExecutorCapturador();
        ReflectionTestUtils.setField(service, "executor", executorControlado);

        TransactionSynchronizationManager.initSynchronization();
        try {
            MlLabDtos.ActionResult primeiro = service.iniciarRun(RUN_ID);
            MlLabDtos.ActionResult segundo = service.iniciarRun(RUN_ID);

            assertTrue(primeiro.isSucesso());
            assertEquals("Run real iniciado em background. Acompanhe no dashboard.", primeiro.getMensagem());
            assertTrue(segundo.isSucesso());
            assertEquals("Run real ja esta rodando em background.", segundo.getMensagem());
            assertEquals(MlLabRunStatus.RODANDO, run.getStatus());
            List<TransactionSynchronization> sincronizacoes = TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, sincronizacoes.size());
            TransactionSynchronization sincronizacao = sincronizacoes.get(0);

            assertTrue(executorControlado.submetidas.isEmpty(),
                "Nenhuma execucao pode ser agendada antes do commit da transacao");
            verify(iaProvider, never()).enviar(anyString(), anyInt());
            // Start duplo nao reexecuta: nenhuma escrita nova em ciclo/candidata nem interacao com aprendizado.
            verify(cycleRepository, never()).save(any(MlLabCycle.class));
            verify(cycleRepository, never()).saveAll(any());
            verify(cycleRepository, never()).delete(any(MlLabCycle.class));
            verify(cycleRepository, never()).deleteById(any());
            verify(candidateRepository, never()).save(any(MlLabRuleCandidate.class));
            verify(candidateRepository, never()).saveAll(any());
            verify(candidateRepository, never()).delete(any(MlLabRuleCandidate.class));
            verify(candidateRepository, never()).deleteById(any());
            verifyNoInteractions(aprendizadoService);

            // Commit: o callback registrado deve agendar EXATAMENTE 1 execucao.
            sincronizacao.afterCommit();
            assertEquals(1, executorControlado.submetidas.size(), "afterCommit deve agendar exatamente 1 execucao do run");

            // Roda a tarefa agendada: prova que o callback nao registrou um no-op (entra no executarRun).
            executorControlado.submetidas.get(0).run();
            verify(corpusService, times(1)).carregar();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deveRecusarPausaDeRunQueNuncaFoiIniciado() {
        MlLabRun run = run(MlLabRunStatus.CRIADO, false);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        IllegalStateException excecao =
            assertThrows(IllegalStateException.class, () -> service.pausarRun(RUN_ID));

        assertTrue(excecao.getMessage().contains("nao iniciado"));
        assertEquals(MlLabRunStatus.CRIADO, run.getStatus());
        verify(runRepository, never()).save(any(MlLabRun.class));
    }

    @Test
    void deveRecusarParadaDeRunJaParado() {
        MlLabRun run = run(MlLabRunStatus.PARADO, false);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        assertThrows(IllegalStateException.class, () -> service.pararRun(RUN_ID));

        assertEquals(MlLabRunStatus.PARADO, run.getStatus());
        verify(runRepository, never()).save(any(MlLabRun.class));
    }

    @Test
    @DisplayName("chat sobre regras responde com as regras do agente, sem IA, gravando a interacao")
    void conversarSobreRegrasRetornaRegrasEDuvidasSemChamarIa() {
        when(agentRepository.findByRunIdAndAgentKey(RUN_ID, "P1-A")).thenReturn(Optional.of(agente(3, 12.5)));

        MlLabDtos.ChatResponse resposta = service.conversar(RUN_ID, "P1-A", chat("explique suas regras"));

        assertEquals(RUN_ID, resposta.getRunId().longValue());
        assertEquals("P1-A", resposta.getAgentKey());
        assertEquals("Atendimento seguro/conservador", resposta.getPerfil());
        assertTrue(resposta.getResposta().contains("Minhas regras de laboratorio"));
        assertTrue(resposta.getResposta().contains("Confirmar orcamento antes de propor"));
        assertEquals(List.of("Confirmar orcamento antes de propor"), resposta.getRegras());
        assertEquals(List.of("Qual o limite de projetos por dia?"), resposta.getDuvidas());
        assertEquals(12.5, resposta.getScoreTotal());
        verifyNoInteractions(iaProvider, aprendizadoService);
        // A interacao do chat e registrada no agente so pelos campos do chat (ACHADO-FA-UNIT-01-4).
        verify(agentRepository).registrarRespostaChat(any(), contains("Minhas regras de laboratorio"), any(LocalDateTime.class));
        verify(agentRepository, never()).save(any(MlLabAgent.class));
    }

    @Test
    @DisplayName("chat sobre duvidas lista as duvidas abertas registradas na memoria")
    void conversarSobreDuvidasListaDuvidasAbertas() {
        when(agentRepository.findByRunIdAndAgentKey(RUN_ID, "P1-A")).thenReturn(Optional.of(agente(3, 12.5)));

        MlLabDtos.ChatResponse resposta = service.conversar(RUN_ID, "P1-A", chat("quais sao suas duvidas?"));

        assertTrue(resposta.getResposta().contains("Duvidas abertas"));
        assertTrue(resposta.getResposta().contains("Qual o limite de projetos por dia?"));
        verifyNoInteractions(iaProvider);
    }

    @Test
    @DisplayName("chat sem palavra-chave responde com a identidade do agente")
    void conversarSemPalavraChaveRetornaIdentidadeDoAgente() {
        when(agentRepository.findByRunIdAndAgentKey(RUN_ID, "P1-A")).thenReturn(Optional.of(agente(3, 12.5)));

        MlLabDtos.ChatResponse resposta = service.conversar(RUN_ID, "P1-A", chat("conte sobre seu trabalho"));

        assertTrue(resposta.getResposta().contains("Identidade final/provisoria: P1-A"));
        assertTrue(resposta.getResposta().contains("geracao G3"));
        verifyNoInteractions(iaProvider);
    }

    @Test
    @DisplayName("chat com agente inexistente lanca erro de negocio")
    void conversarAgenteInexistenteLancaErro() {
        when(agentRepository.findByRunIdAndAgentKey(RUN_ID, "P99-Z")).thenReturn(Optional.empty());

        IllegalArgumentException excecao = assertThrows(IllegalArgumentException.class,
            () -> service.conversar(RUN_ID, "P99-Z", chat("oi")));

        assertEquals("Agente nao encontrado: P99-Z", excecao.getMessage());
        verifyNoInteractions(iaProvider);
    }

    @Test
    void deveRecusarMensagemVaziaNoChat() {
        when(agentRepository.findByRunIdAndAgentKey(RUN_ID, "P1-A")).thenReturn(Optional.of(agente(3, 12.5)));

        assertThrows(IllegalArgumentException.class, () -> service.conversar(RUN_ID, "P1-A", chat("   ")));
        assertThrows(IllegalArgumentException.class, () -> service.conversar(RUN_ID, "P1-A", null));
        verify(agentRepository, never()).registrarRespostaChat(any(), any(), any());
    }

    @Test
    void deveGravarInteracaoDoChatNoAgente() {
        when(agentRepository.findByRunIdAndAgentKey(RUN_ID, "P1-A")).thenReturn(Optional.of(agente(3, 12.5)));

        service.conversar(RUN_ID, "P1-A", chat("explique suas regras"));

        verify(agentRepository).registrarRespostaChat(any(), argThat(r -> !"anterior".equals(r)), any(LocalDateTime.class));
        verify(agentRepository, never()).save(any(MlLabAgent.class));
    }

    @Test
    @DisplayName("aprovar candidata muda status para APROVADA e aplica a regra na memoria principal")
    void aprovarCandidataMudaStatusEAplicaRegraNaMemoriaPrincipal() {
        when(candidateRepository.findById(CANDIDATA_ID)).thenReturn(Optional.of(candidata(MlLabCandidateStatus.PENDENTE)));
        // Releitura apos o UPDATE (contexto limpo) traz a candidata ja APROVADA do banco.
        when(candidateRepository.findTop20ByRunIdOrderByScoreDesc(RUN_ID))
            .thenReturn(List.of(candidata(MlLabCandidateStatus.APROVADA)));
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run(MlLabRunStatus.PAUSADO, false)));
        when(candidateRepository.aprovarSeAindaNaoAprovada(eq(CANDIDATA_ID), any(LocalDateTime.class))).thenReturn(1);

        MlLabDtos.ActionResult resultado = service.aprovarCandidata(CANDIDATA_ID);

        assertTrue(resultado.isSucesso());
        assertEquals("Regra aprovada no laboratorio e aplicada na memoria principal do bot.", resultado.getMensagem());

        verify(candidateRepository, times(1)).aprovarSeAindaNaoAprovada(eq(CANDIDATA_ID), any(LocalDateTime.class));
        assertEquals(MlLabCandidateStatus.APROVADA, resultado.getOverview().getCandidates().get(0).getStatus());
        verify(candidateRepository, never()).save(any(MlLabRuleCandidate.class));

        ArgumentCaptor<Aprendizado> aprendizadoCaptor = ArgumentCaptor.forClass(Aprendizado.class);
        verify(aprendizadoService, times(1)).salvar(aprendizadoCaptor.capture());
        Aprendizado salvo = aprendizadoCaptor.getValue();
        assertEquals(Boolean.TRUE, salvo.getFoiAprovado());
        assertEquals(StatusRegraAprendida.CONFIRMADA, salvo.getStatusRegra());
        assertEquals("ML_LAB_CANDIDATE_55", salvo.getMotivo());
        assertEquals(ModoSistema.ENSINAMENTO, salvo.getModoUsado());
        assertEquals("Sempre confirmar prazo e orcamento antes de enviar proposta", salvo.getRegrasAprendidas());
        assertEquals("Cenario: cliente pede landing page", salvo.getFeedbackUsuario());
        assertEquals("ML_LAB_RUN_" + RUN_ID + "_CYCLE_4", salvo.getOrigemFeedback());
        assertEquals(RUN_ID, salvo.getProjetoId().longValue());
        assertEquals(MotivoAprendizado.ESCOPO_CLARO, salvo.getMotivoAprendizado());
        assertEquals(MlLabRunStatus.PAUSADO, resultado.getOverview().getStatus());
    }

    @Test
    @DisplayName("aprovar candidata inexistente lanca erro sem salvar candidato nem aprendizado")
    void aprovarCandidataInexistenteLancaErroSemSalvar() {
        when(candidateRepository.findById(99L)).thenReturn(Optional.empty());

        IllegalArgumentException excecao =
            assertThrows(IllegalArgumentException.class, () -> service.aprovarCandidata(99L));

        assertEquals("Regra candidata nao encontrada: 99", excecao.getMessage());
        verify(candidateRepository, never()).save(any(MlLabRuleCandidate.class));
        verifyNoInteractions(aprendizadoService, runRepository);
    }

    @Test
    void aprovacaoConcorrentePerdedoraNaoDuplicaAprendizadoNemFalha() {
        when(candidateRepository.findById(CANDIDATA_ID)).thenReturn(Optional.of(candidata(MlLabCandidateStatus.PENDENTE)));
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run(MlLabRunStatus.PAUSADO, false)));
        when(candidateRepository.aprovarSeAindaNaoAprovada(eq(CANDIDATA_ID), any(LocalDateTime.class))).thenReturn(0);

        MlLabDtos.ActionResult resultado = service.aprovarCandidata(CANDIDATA_ID);

        assertTrue(resultado.isSucesso());
        assertEquals(MlLabGuardas.MSG_CANDIDATA_JA_APROVADA, resultado.getMensagem());
        verify(aprendizadoService, never()).salvar(any(Aprendizado.class));
    }

    @Test
    void deveNaoDuplicarRegraAoAprovarCandidataJaAprovada() {
        when(candidateRepository.findById(CANDIDATA_ID))
            .thenReturn(Optional.of(candidata(MlLabCandidateStatus.APROVADA)));
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run(MlLabRunStatus.PAUSADO, false)));

        service.aprovarCandidata(CANDIDATA_ID);

        verify(aprendizadoService, never()).salvar(any(Aprendizado.class));
        verify(candidateRepository, never()).save(any(MlLabRuleCandidate.class));
    }

    @Test
    @DisplayName("overview de run sem dados traz geracao 1, agregacoes vazias e escrita externa bloqueada")
    void obterOverviewRunSemDadosRetornaAgregacoesVazias() {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run(MlLabRunStatus.CRIADO, false)));

        MlLabDtos.RunOverview overview = service.obterOverview(RUN_ID);

        assertEquals(MlLabRunStatus.CRIADO, overview.getStatus());
        assertEquals(1, overview.getGeracaoAtual());
        assertEquals(0, overview.getEvolucoesTotal());
        assertEquals("Top 10 sobrevivem; demais viram filhos/mutacoes apos cada ciclo.", overview.getModoEvolucao());
        assertTrue(overview.getAgents().isEmpty());
        assertTrue(overview.getRecentCycles().isEmpty());
        assertTrue(overview.getCandidates().isEmpty());
        assertEquals("bloqueado", overview.getSafety().get("envioCliente"));
        assertTrue(service.listarAgentes(RUN_ID).isEmpty());
    }

    @Test
    @DisplayName("overview com dados agrega geracao corrente, ultimos ciclos e candidatas")
    void obterOverviewComDadosAgregaGeracaoCiclosECandidatas() {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run(MlLabRunStatus.RODANDO, false)));
        when(agentRepository.findByRunIdOrderByScoreTotalDesc(RUN_ID))
            .thenReturn(List.of(agente(4, 12.5), agente("P2-B", 1, 3.2)));
        when(cycleRepository.findTop10ByRunIdOrderByNumeroDesc(RUN_ID)).thenReturn(List.of(ciclo()));
        when(candidateRepository.findTop20ByRunIdOrderByScoreDesc(RUN_ID))
            .thenReturn(List.of(candidata(MlLabCandidateStatus.PENDENTE)));

        MlLabDtos.RunOverview overview = service.obterOverview(RUN_ID);

        assertEquals(4, overview.getGeracaoAtual());
        assertEquals(3, overview.getEvolucoesTotal());
        assertEquals(2, overview.getAgents().size());
        assertEquals("P1-A", overview.getAgents().get(0).getAgentKey());
        assertEquals(12.5, overview.getAgents().get(0).getScoreTotal());
        assertEquals(4, overview.getAgents().get(0).getGeracao());
        assertEquals("TOP10", overview.getAgents().get(0).getSelectionBand());
        assertEquals("TOP10", overview.getAgents().get(1).getSelectionBand());
        assertEquals(7, overview.getRecentCycles().get(0).getNumero());
        assertEquals("P1-A", overview.getRecentCycles().get(0).getBestAgentKey());
        assertEquals(MlLabCandidateStatus.PENDENTE, overview.getCandidates().get(0).getStatus());
        assertEquals("bloqueado", overview.getSafety().get("envioCliente"));
    }

    @Test
    @DisplayName("overview e agentes de run inexistente lancam erro com mensagem clara")
    void overviewEAgentesDeRunInexistenteLancamErro() {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.empty());

        IllegalArgumentException excecao =
            assertThrows(IllegalArgumentException.class, () -> service.obterOverview(RUN_ID));
        assertEquals("Run nao encontrado: 7", excecao.getMessage());
        assertThrows(IllegalArgumentException.class, () -> service.listarAgentes(RUN_ID));
    }

    @Test
    @DisplayName("listarRuns mapeia os ultimos runs na ordem do repositorio")
    void listarRunsRetornaUltimosRunsMapeados() {
        when(runRepository.findTop20ByOrderByDataCriacaoDesc())
            .thenReturn(List.of(run(MlLabRunStatus.CONCLUIDO, false), run(MlLabRunStatus.CRIADO, false)));

        List<MlLabDtos.RunOverview> runs = service.listarRuns();

        assertEquals(2, runs.size());
        assertEquals(MlLabRunStatus.CONCLUIDO, runs.get(0).getStatus());
        assertEquals(50, runs.get(1).getTotalCiclos());
        assertTrue(runs.get(0).isExternalWritesBlocked());
    }

    @Test
    @DisplayName("listarAgentes respeita o ranking do repositorio e decodifica memoria do agente")
    void listarAgentesRetornaRankingComMemoriaDecodificada() {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run(MlLabRunStatus.PAUSADO, false)));
        when(agentRepository.findByRunIdOrderByScoreTotalDesc(RUN_ID))
            .thenReturn(List.of(agente(4, 12.5), agente("P2-B", 1, 3.2)));

        List<MlLabDtos.AgentView> agentes = service.listarAgentes(RUN_ID);

        assertEquals(2, agentes.size());
        assertEquals("P1-A", agentes.get(0).getAgentKey());
        assertEquals(List.of("Confirmar orcamento antes de propor"), agentes.get(0).getRegras());
        assertEquals(List.of("Qual o limite de projetos por dia?"), agentes.get(0).getDuvidas());
        assertEquals("G1 base P1-A", agentes.get(0).getLinhagem());
        assertEquals("P2-B", agentes.get(1).getAgentKey());
        assertEquals(1, agentes.get(1).getGeracao());
    }

    private MlLabRun run(MlLabRunStatus status, boolean realIa) {
        return MlLabRun.builder()
            .id(RUN_ID)
            .status(status)
            .objetivo("Objetivo de teste")
            .totalCiclos(50)
            .ciclosConcluidos(0)
            .totalAgentes(30)
            .bestScore(0)
            .externalWritesBlocked(true)
            .realIaEnabled(realIa)
            .iaProviderName(realIa ? "provider-teste" : "deterministico")
            .iaModelName(realIa ? "modelo-teste" : "offline")
            .maxTokensPerCall(1800)
            .selectionMode("TOP10")
            .parallelWorkers(6)
            .eliteCount(6)
            .repechageCount(9)
            .childProtectionCycles(2)
            .artifactDir(tempDir.resolve("run-" + RUN_ID).toString())
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
    }

    private MlLabRuleCandidate candidata(MlLabCandidateStatus status) {
        return MlLabRuleCandidate.builder()
            .id(CANDIDATA_ID)
            .runId(RUN_ID)
            .agentKey("P3-B")
            .cycleNumber(4)
            .regra("Sempre confirmar prazo e orcamento antes de enviar proposta")
            .origem("Cenario: cliente pede landing page")
            .score(8.5)
            .status(status)
            .dataCriacao(LocalDateTime.now())
            .build();
    }

    private MlLabAgent agente(int geracao, double score) {
        return agente("P1-A", geracao, score);
    }

    private MlLabAgent agente(String chave, int geracao, double score) {
        return MlLabAgent.builder()
            .id(11L)
            .runId(RUN_ID)
            .agentKey(chave)
            .perfil("Atendimento seguro/conservador")
            .variante("conservadora")
            .estrategia("estrategia de teste")
            .scoreTotal(score)
            .respostasTotal(4)
            .memoriaJson("{\"geracao\":" + geracao + ",\"linhagem\":[\"G1 base " + chave
                + "\"],\"duvidas\":[\"Qual o limite de projetos por dia?\"],\"aprendizados\":[]}")
            .regrasJson("[\"Confirmar orcamento antes de propor\"]")
            .ultimaResposta("anterior")
            .dataAtualizacao(LocalDateTime.now())
            .build();
    }

    private MlLabCycle ciclo() {
        return MlLabCycle.builder()
            .id(77L)
            .runId(RUN_ID)
            .numero(7)
            .cenario("Cliente pede landing page WordPress")
            .bestAgentKey("P1-A")
            .bestScore(12.5)
            .resumo("Melhor: P1-A score 12.5")
            .dataCriacao(LocalDateTime.now())
            .build();
    }

    private MlLabDtos.ChatRequest chat(String mensagem) {
        return MlLabDtos.ChatRequest.builder().mensagem(mensagem).build();
    }

    /** Executor controlado: apenas registra as tarefas submetidas; o teste decide quando rodar. */
    private static final class ExecutorCapturador extends AbstractExecutorService {
        final List<Runnable> submetidas = new CopyOnWriteArrayList<>();

        @Override
        public void execute(Runnable comando) {
            submetidas.add(comando);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        public boolean isShutdown() { return true; }

        public boolean isTerminated() { return true; }

        public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }
}
