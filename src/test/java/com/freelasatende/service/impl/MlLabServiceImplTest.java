package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.dto.MlLabDtos;
import com.freelasatende.model.entity.MlLabAgent;
import com.freelasatende.model.entity.MlLabCycle;
import com.freelasatende.model.entity.MlLabRuleCandidate;
import com.freelasatende.model.entity.MlLabRun;
import com.freelasatende.model.enums.MlLabCandidateStatus;
import com.freelasatende.model.enums.MlLabRunStatus;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testes unitarios do ciclo de vida do MlLabServiceImpl (criacao do run,
 * maquina de estados deterministica e erros). Repositorios mockados, runtime
 * deterministico real (sem IA, sem rede, sem Thread.sleep).
 */
@ExtendWith(MockitoExtension.class)
class MlLabServiceImplTest {

    private static final long RUN_ID = 7L;

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
    @DisplayName("criarRun sem request usa padroes, grava run CRIADO e cria exatamente 30 agentes")
    void criarRunRequestNulaUsaPadroesECriaTrintaAgentes() {
        when(runRepository.save(any(MlLabRun.class))).thenAnswer(inv -> {
            MlLabRun salva = inv.getArgument(0);
            if (salva.getId() == null) {
                salva.setId(1L);
            }
            return salva;
        });
        when(corpusService.carregar()).thenReturn(snapshot());

        MlLabDtos.RunOverview overview = service.criarRun(null);

        assertEquals(1L, overview.getId().longValue());
        assertEquals(MlLabRunStatus.CRIADO, overview.getStatus());
        assertEquals(100, overview.getTotalCiclos());
        assertEquals(30, overview.getTotalAgentes());
        assertEquals("Evoluir 30 IAs de atendimento em 100 ciclos, sem envio externo.", overview.getObjetivo());
        assertTrue(overview.isExternalWritesBlocked());
        assertFalse(overview.isRealIaEnabled());
        assertEquals("deterministico", overview.getIaProviderName());
        assertEquals("offline", overview.getIaModelName());
        assertEquals(1800, overview.getMaxTokensPerCall());
        assertEquals("TOP10", overview.getSelectionMode());
        assertEquals(6, overview.getParallelWorkers());
        assertEquals(6, overview.getEliteCount());
        assertEquals(9, overview.getRepechageCount());
        assertEquals(2, overview.getChildProtectionCycles());
        assertEquals("Corpus de teste. Escrita externa bloqueada.", overview.getCorpusSummary());

        ArgumentCaptor<MlLabRun> runCaptor = ArgumentCaptor.forClass(MlLabRun.class);
        verify(runRepository, times(2)).save(runCaptor.capture());
        assertTrue(runCaptor.getValue().getArtifactDir().endsWith("run-1"));
        assertEquals(0, runCaptor.getValue().getCiclosConcluidos());

        ArgumentCaptor<MlLabAgent> agentCaptor = ArgumentCaptor.forClass(MlLabAgent.class);
        verify(agentRepository, times(30)).save(agentCaptor.capture());
        Set<String> chaves = agentCaptor.getAllValues().stream()
            .map(MlLabAgent::getAgentKey)
            .collect(Collectors.toSet());
        assertEquals(30, chaves.size());
        assertTrue(chaves.contains("P1-A"));
        assertTrue(chaves.contains("P10-C"));
        assertTrue(Files.exists(Path.of(runCaptor.getValue().getArtifactDir(), "corpus-summary.json")));
    }

    @Test
    @DisplayName("criarRun clampa parametros fora da faixa e usa defaults em valores invalidos")
    void criarRunClampaParametrosForaDaFaixaEUsaDefaultsInvalidos() {
        when(runRepository.save(any(MlLabRun.class))).thenAnswer(inv -> {
            MlLabRun salva = inv.getArgument(0);
            if (salva.getId() == null) {
                salva.setId(2L);
            }
            return salva;
        });
        when(corpusService.carregar()).thenReturn(snapshot());

        service.criarRun(MlLabDtos.CreateRunRequest.builder()
            .totalCiclos(5000)
            .objetivo("  Treinar IAs para projetos web  ")
            .maxTokensPorChamada(100)
            .selectionMode("multi_criteria")
            .parallelWorkers(999)
            .eliteCount(0)
            .repechageCount(999)
            .childProtectionCycles(99)
            .build());

        ArgumentCaptor<MlLabRun> captor = ArgumentCaptor.forClass(MlLabRun.class);
        verify(runRepository, times(2)).save(captor.capture());
        MlLabRun clampada = captor.getValue();
        assertEquals(500, clampada.getTotalCiclos());
        assertEquals(512, clampada.getMaxTokensPerCall());
        assertEquals("MULTI_CRITERIA", clampada.getSelectionMode());
        assertEquals(30, clampada.getParallelWorkers());
        assertEquals(1, clampada.getEliteCount());
        assertEquals(29, clampada.getRepechageCount());
        assertEquals(10, clampada.getChildProtectionCycles());
        assertEquals("Treinar IAs para projetos web", clampada.getObjetivo());

        ArgumentCaptor<MlLabRun> captorPadroes = ArgumentCaptor.forClass(MlLabRun.class);
        service.criarRun(MlLabDtos.CreateRunRequest.builder()
            .totalCiclos(-3)
            .maxTokensPorChamada(0)
            .selectionMode(" ")
            .build());
        verify(runRepository, times(4)).save(captorPadroes.capture());
        MlLabRun padroes = captorPadroes.getAllValues().get(3);
        assertEquals(100, padroes.getTotalCiclos());
        assertEquals(1800, padroes.getMaxTokensPerCall());
        assertEquals("TOP10", padroes.getSelectionMode());
    }

    @Test
    @DisplayName("iniciarRun deterministico conclui 1 ciclo gravando ciclo e candidata, sem chamar IA")
    void iniciarRunDeterministicoConcluiRunGravandoCicloECandidataSemChamarIa() {
        MlLabRun run = run(MlLabRunStatus.CRIADO, 1, 0, false);
        stubExecucaoDeterministica(run);

        MlLabDtos.ActionResult resultado = service.iniciarRun(RUN_ID);

        assertTrue(resultado.isSucesso());
        assertEquals("Run concluido com 30 agentes e 1 ciclos.", resultado.getMensagem());
        assertEquals(MlLabRunStatus.CONCLUIDO, run.getStatus());
        assertEquals(1, run.getCiclosConcluidos());
        assertNotNull(run.getBestAgentKey());
        assertTrue(run.getBestScore() > 0);
        assertEquals(30, resultado.getOverview().getAgents().size());

        ArgumentCaptor<MlLabCycle> cicloCaptor = ArgumentCaptor.forClass(MlLabCycle.class);
        verify(cycleRepository, times(1)).save(cicloCaptor.capture());
        assertEquals(1, cicloCaptor.getValue().getNumero());
        assertEquals(RUN_ID, cicloCaptor.getValue().getRunId().longValue());
        assertEquals(cicloCaptor.getValue().getBestAgentKey(), run.getBestAgentKey());

        ArgumentCaptor<MlLabRuleCandidate> candidataCaptor = ArgumentCaptor.forClass(MlLabRuleCandidate.class);
        verify(candidateRepository, times(1)).save(candidataCaptor.capture());
        assertEquals(MlLabCandidateStatus.PENDENTE, candidataCaptor.getValue().getStatus());
        assertEquals(1, candidataCaptor.getValue().getCycleNumber());
        assertFalse(candidataCaptor.getValue().getRegra().isBlank());
        assertTrue(candidataCaptor.getValue().getOrigem().startsWith("Cenario: "));

        verify(agentRepository, times(30)).save(any(MlLabAgent.class));
        verifyNoInteractions(iaProvider, aprendizadoService);
        assertTrue(Files.exists(Path.of(run.getArtifactDir(), "cycle-1.json")));
        assertTrue(Files.exists(Path.of(run.getArtifactDir(), "final-overview.json")));
    }

    @Test
    @DisplayName("iniciarRun em run pausada retoma do ciclo seguinte e vai ate concluir")
    void iniciarRunRetomaRunPausadaDoProximoCicloAteConcluir() {
        MlLabRun run = run(MlLabRunStatus.PAUSADO, 2, 1, false);
        stubExecucaoDeterministica(run);

        MlLabDtos.ActionResult resultado = service.iniciarRun(RUN_ID);

        assertTrue(resultado.isSucesso());
        assertEquals("Run concluido com 30 agentes e 2 ciclos.", resultado.getMensagem());
        assertEquals(MlLabRunStatus.CONCLUIDO, run.getStatus());
        assertEquals(2, run.getCiclosConcluidos());

        ArgumentCaptor<MlLabCycle> cicloCaptor = ArgumentCaptor.forClass(MlLabCycle.class);
        verify(cycleRepository, times(1)).save(cicloCaptor.capture());
        assertEquals(2, cicloCaptor.getValue().getNumero());
        assertFalse(Files.exists(Path.of(run.getArtifactDir(), "cycle-1.json")));
        assertTrue(Files.exists(Path.of(run.getArtifactDir(), "cycle-2.json")));

        ArgumentCaptor<MlLabRuleCandidate> candidataCaptor = ArgumentCaptor.forClass(MlLabRuleCandidate.class);
        verify(candidateRepository, times(1)).save(candidataCaptor.capture());
        assertEquals(2, candidataCaptor.getValue().getCycleNumber());
        verifyNoInteractions(iaProvider);
    }

    @Test
    @DisplayName("iniciarRun respeita pausa externa entre ciclos e mantem status PAUSADO")
    void iniciarRunRespeitaPausaExternaEntreCiclosEPreservaPausado() {
        MlLabRun iniciando = run(MlLabRunStatus.CRIADO, 3, 0, false);
        MlLabRun pausado = run(MlLabRunStatus.PAUSADO, 3, 0, false);
        when(runRepository.findById(RUN_ID))
            .thenReturn(Optional.of(iniciando), Optional.of(iniciando), Optional.of(pausado));
        when(agentRepository.countByRunId(RUN_ID)).thenReturn(30L);
        when(corpusService.carregar()).thenReturn(snapshot());

        MlLabDtos.ActionResult resultado = service.iniciarRun(RUN_ID);

        assertTrue(resultado.isSucesso());
        assertEquals("Run interrompido em estado PAUSADO.", resultado.getMensagem());
        assertEquals(MlLabRunStatus.PAUSADO, resultado.getOverview().getStatus());
        assertEquals(MlLabRunStatus.PAUSADO, pausado.getStatus());

        ArgumentCaptor<MlLabRun> captor = ArgumentCaptor.forClass(MlLabRun.class);
        verify(runRepository, times(2)).save(captor.capture());
        assertTrue(captor.getAllValues().stream().allMatch(r -> r.getStatus() == MlLabRunStatus.RODANDO));
        verify(cycleRepository, never()).save(any(MlLabCycle.class));
        verifyNoInteractions(iaProvider);
    }

    @Test
    @DisplayName("iniciarRun em run concluida retorna sucesso idempotente sem salvar nem reexecutar")
    void iniciarRunConcluidaRetornaSucessoSemReexecutar() {
        MlLabRun run = run(MlLabRunStatus.CONCLUIDO, 100, 100, false);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        MlLabDtos.ActionResult resultado = service.iniciarRun(RUN_ID);

        assertTrue(resultado.isSucesso());
        assertEquals("Run ja concluido.", resultado.getMensagem());
        assertEquals(MlLabRunStatus.CONCLUIDO, resultado.getOverview().getStatus());
        verify(runRepository, never()).save(any(MlLabRun.class));
        verify(agentRepository, never()).countByRunId(any());
        // O mapOverview le ciclos e candidatas para montar a resposta; a intencao
        // do teste e "nao reexecuta": nenhuma escrita nova em ciclo ou candidata.
        verify(cycleRepository, never()).save(any(MlLabCycle.class));
        verify(cycleRepository, never()).saveAll(any());
        verify(cycleRepository, never()).delete(any(MlLabCycle.class));
        verify(cycleRepository, never()).deleteById(any());
        verify(candidateRepository, never()).save(any(MlLabRuleCandidate.class));
        verify(candidateRepository, never()).saveAll(any());
        verify(candidateRepository, never()).delete(any(MlLabRuleCandidate.class));
        verify(candidateRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("iniciarRun de id inexistente lanca erro de negocio e nao salva nada")
    void iniciarRunInexistenteLancaErroSemSalvar() {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.empty());

        IllegalArgumentException excecao =
            assertThrows(IllegalArgumentException.class, () -> service.iniciarRun(RUN_ID));

        assertEquals("Run nao encontrado: 7", excecao.getMessage());
        verify(runRepository, never()).save(any(MlLabRun.class));
        verifyNoInteractions(agentRepository, cycleRepository, candidateRepository);
    }

    @Test
    @DisplayName("iniciarRun exige exatamente 30 agentes antes de comecar")
    void iniciarRunExigeExatamenteTrintaAgentes() {
        MlLabRun run = run(MlLabRunStatus.CRIADO, 100, 0, false);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(agentRepository.countByRunId(RUN_ID)).thenReturn(29L);

        IllegalStateException excecao =
            assertThrows(IllegalStateException.class, () -> service.iniciarRun(RUN_ID));

        assertEquals("Run precisa ter exatamente 30 agentes antes de iniciar.", excecao.getMessage());
        assertEquals(MlLabRunStatus.CRIADO, run.getStatus());
        verify(runRepository, never()).save(any(MlLabRun.class));
    }

    @Test
    @DisplayName("iniciarRun com IA real indisponivel grava status ERRO e falha sem chamar a IA")
    void iniciarRunComIaRealIndisponivelGravaStatusErro() throws Exception {
        MlLabRun run = run(MlLabRunStatus.CRIADO, 100, 0, true);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(agentRepository.countByRunId(RUN_ID)).thenReturn(30L);
        when(iaProvider.estaDisponivel()).thenReturn(false);

        MlLabDtos.ActionResult resultado = service.iniciarRun(RUN_ID);

        assertFalse(resultado.isSucesso());
        assertTrue(resultado.getMensagem().contains("provider indisponivel"));
        assertEquals(MlLabRunStatus.ERRO, run.getStatus());
        assertTrue(run.getErro().contains("indisponivel"));
        ArgumentCaptor<MlLabRun> captor = ArgumentCaptor.forClass(MlLabRun.class);
        verify(runRepository, times(1)).save(captor.capture());
        assertEquals(MlLabRunStatus.ERRO, captor.getValue().getStatus());
        verify(iaProvider, never()).enviar(anyString(), anyInt());
        // O mapOverview le ciclos para montar a resposta do erro; o que nao pode
        // acontecer e gravar ciclo novo quando o provider esta indisponivel.
        verify(cycleRepository, never()).save(any(MlLabCycle.class));
        verify(cycleRepository, never()).saveAll(any());
        verify(cycleRepository, never()).delete(any(MlLabCycle.class));
        verify(cycleRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("pausarRun em run rodando grava status PAUSADO")
    void pausarRunRodandoGravaStatusPausado() {
        MlLabRun run = run(MlLabRunStatus.RODANDO, 100, 10, false);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        MlLabDtos.ActionResult resultado = service.pausarRun(RUN_ID);

        assertTrue(resultado.isSucesso());
        assertEquals("Run pausado. Nenhum envio externo existe neste laboratorio.", resultado.getMensagem());
        assertEquals(MlLabRunStatus.PAUSADO, run.getStatus());
        ArgumentCaptor<MlLabRun> captor = ArgumentCaptor.forClass(MlLabRun.class);
        verify(runRepository, times(1)).save(captor.capture());
        assertEquals(MlLabRunStatus.PAUSADO, captor.getValue().getStatus());
    }

    @Test
    @DisplayName("pararRun em run rodando grava status PARADO")
    void pararRunRodandoGravaStatusParado() {
        MlLabRun run = run(MlLabRunStatus.RODANDO, 100, 10, false);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        MlLabDtos.ActionResult resultado = service.pararRun(RUN_ID);

        assertTrue(resultado.isSucesso());
        assertEquals("Run parado. Estado preservado para auditoria.", resultado.getMensagem());
        assertEquals(MlLabRunStatus.PARADO, run.getStatus());
        ArgumentCaptor<MlLabRun> captor = ArgumentCaptor.forClass(MlLabRun.class);
        verify(runRepository, times(1)).save(captor.capture());
        assertEquals(MlLabRunStatus.PARADO, captor.getValue().getStatus());
    }

    @Test
    @DisplayName("pausar e parar run concluida preservam o estado terminal sem nova gravacao")
    void pausarEPararRunConcluidaPreservamEstadoTerminal() {
        MlLabRun run = run(MlLabRunStatus.CONCLUIDO, 100, 100, false);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        MlLabDtos.ActionResult pausa = service.pausarRun(RUN_ID);
        MlLabDtos.ActionResult parada = service.pararRun(RUN_ID);

        assertTrue(pausa.isSucesso());
        assertTrue(parada.isSucesso());
        assertEquals(MlLabRunStatus.CONCLUIDO, run.getStatus());
        verify(runRepository, never()).save(any(MlLabRun.class));
    }

    @Test
    @DisplayName("pausar e parar run inexistente lancam erro e nao salvam nada")
    void pausarEPararRunInexistenteLancamErro() {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.pausarRun(RUN_ID));
        assertThrows(IllegalArgumentException.class, () -> service.pararRun(RUN_ID));
        verify(runRepository, never()).save(any(MlLabRun.class));
    }

    private MlLabRun run(MlLabRunStatus status, int totalCiclos, int ciclosConcluidos, boolean realIa) {
        return MlLabRun.builder()
            .id(RUN_ID)
            .status(status)
            .objetivo("Objetivo de teste")
            .totalCiclos(totalCiclos)
            .ciclosConcluidos(ciclosConcluidos)
            .totalAgentes(30)
            .bestScore(0)
            .externalWritesBlocked(true)
            .realIaEnabled(realIa)
            .iaProviderName(realIa ? "provider-teste" : "deterministico")
            .iaModelName(realIa ? "modelo-teste" : "offline")
            .maxTokensPerCall(1800)
            .iaCallsTotal(0)
            .deterministicFallbackTotal(0)
            .selectionMode("TOP10")
            .parallelWorkers(6)
            .eliteCount(6)
            .repechageCount(9)
            .childProtectionCycles(2)
            .artifactDir(tempDir.resolve("run-" + RUN_ID).toString())
            .corpusSummary("resumo-anterior")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
    }

    private MlLabCorpusService.Snapshot snapshot() {
        return new MlLabCorpusService.Snapshot(
            "Corpus de teste. Escrita externa bloqueada.",
            List.of("Trecho 1 do corpus", "Trecho 2 do corpus")
        );
    }

    private void stubExecucaoDeterministica(MlLabRun run) {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(agentRepository.countByRunId(RUN_ID)).thenReturn(30L);
        when(agentRepository.findByRunIdOrderByScoreTotalDesc(RUN_ID)).thenReturn(trintaAgentes());
        when(corpusService.carregar()).thenReturn(snapshot());
        when(corpusService.contexto(any(MlLabCorpusService.Snapshot.class), anyInt())).thenReturn("");
        when(cycleRepository.save(any(MlLabCycle.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private List<MlLabAgent> trintaAgentes() {
        String[][] variantes = {{"A", "conservadora"}, {"B", "equilibrada"}, {"C", "exploratoria"}};
        List<MlLabAgent> agentes = new ArrayList<>();
        for (int perfil = 1; perfil <= 10; perfil++) {
            for (String[] variante : variantes) {
                String chave = "P" + perfil + "-" + variante[0];
                agentes.add(MlLabAgent.builder()
                    .id((long) agentes.size() + 1)
                    .runId(RUN_ID)
                    .agentKey(chave)
                    .perfil("Atendimento seguro/conservador")
                    .variante(variante[1])
                    .estrategia("estrategia de teste")
                    .scoreTotal(0)
                    .respostasTotal(0)
                    .memoriaJson("{\"geracao\":1,\"linhagem\":[\"G1 base " + chave
                        + "\"],\"duvidas\":[\"Duvida inicial?\"],\"aprendizados\":[]}")
                    .regrasJson("[]")
                    .ultimaResposta("")
                    .dataAtualizacao(LocalDateTime.now())
                    .build());
            }
        }
        return agentes;
    }
}
