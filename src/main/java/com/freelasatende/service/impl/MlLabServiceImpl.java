package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.dto.MlLabDtos;
import com.freelasatende.model.entity.Aprendizado;
import com.freelasatende.model.entity.MlLabAgent;
import com.freelasatende.model.entity.MlLabRuleCandidate;
import com.freelasatende.model.entity.MlLabRun;
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
import com.freelasatende.service.interfaceServico.MlLabService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MlLabServiceImpl implements MlLabService {

    private static final int AGENT_COUNT = 30;
    private static final int DEFAULT_PARALLEL_WORKERS = 6;
    private static final int DEFAULT_ELITE_COUNT = 6;
    private static final int DEFAULT_REPECHAGE_COUNT = 9;
    private static final int DEFAULT_CHILD_PROTECTION_CYCLES = 2;

    private final MlLabRunRepository runRepository;
    private final MlLabAgentRepository agentRepository;
    private final MlLabCycleRepository cycleRepository;
    private final MlLabRuleCandidateRepository candidateRepository;
    private final AprendizadoService aprendizadoService;
    private final IaProvider iaProvider;
    private final ObjectMapper objectMapper;
    private final OperationalSafetyPolicy operationalSafetyPolicy;
    private final MlLabCorpusService corpusService;
    private MlLabJsonHelper jsonHelper;
    private MlLabAgentRuntime agentRuntime;
    private MlLabEvolutionEngine evolutionEngine;
    private MlLabViewMapper viewMapper;
    private MlLabCycleRunner cycleRunner;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ml-lab-real-ia");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Long> runningRuns = ConcurrentHashMap.newKeySet();

    private MlLabJsonHelper jsonHelper() {
        if (jsonHelper == null) {
            jsonHelper = new MlLabJsonHelper(objectMapper);
        }
        return jsonHelper;
    }

    private MlLabAgentRuntime agentRuntime() {
        if (agentRuntime == null) {
            agentRuntime = new MlLabAgentRuntime(iaProvider, objectMapper);
        }
        return agentRuntime;
    }

    private MlLabEvolutionEngine evolutionEngine() {
        if (evolutionEngine == null) {
            evolutionEngine = new MlLabEvolutionEngine(agentRepository, runRepository, jsonHelper());
        }
        return evolutionEngine;
    }

    private MlLabViewMapper viewMapper() {
        if (viewMapper == null) {
            viewMapper = new MlLabViewMapper(
                agentRepository,
                cycleRepository,
                candidateRepository,
                operationalSafetyPolicy,
                jsonHelper(),
                evolutionEngine()
            );
        }
        return viewMapper;
    }

    private MlLabCycleRunner cycleRunner() {
        if (cycleRunner == null) {
            cycleRunner = new MlLabCycleRunner(
                runRepository,
                agentRepository,
                cycleRepository,
                candidateRepository,
                jsonHelper(),
                agentRuntime(),
                evolutionEngine()
            );
        }
        return cycleRunner;
    }

    @Value("${ml-lab.artifact-dir:data/ml-lab/runs}")
    private String artifactRoot;

    @Value("${ml-lab.real-ia-enabled:false}")
    private boolean defaultRealIaEnabled;

    @PreDestroy
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    @Override
    @Transactional
    public MlLabDtos.RunOverview criarRun(MlLabDtos.CreateRunRequest request) {
        int ciclos = MlLabGuardas.normalizarCiclos(request != null ? request.getTotalCiclos() : null);
        String objetivo = MlLabGuardas.textoOuPadrao(
            request != null ? request.getObjetivo() : null,
            "Evoluir 30 IAs de atendimento em 100 ciclos, sem envio externo."
        );
        boolean usarIaReal = request != null && request.getUsarIaReal() != null
            ? request.getUsarIaReal()
            : defaultRealIaEnabled;
        int maxTokens = MlLabGuardas.normalizarMaxTokens(request != null ? request.getMaxTokensPorChamada() : null);
        String selectionMode = MlLabGuardas.normalizarSelectionMode(request != null ? request.getSelectionMode() : null);
        int parallelWorkers = MlLabGuardas.normalizarFaixa(request != null ? request.getParallelWorkers() : null, DEFAULT_PARALLEL_WORKERS, 1, AGENT_COUNT);
        int eliteCount = MlLabGuardas.normalizarFaixa(request != null ? request.getEliteCount() : null, DEFAULT_ELITE_COUNT, 1, AGENT_COUNT);
        int repechageCount = MlLabGuardas.normalizarFaixa(request != null ? request.getRepechageCount() : null, DEFAULT_REPECHAGE_COUNT, 0, AGENT_COUNT - eliteCount);
        int childProtectionCycles = MlLabGuardas.normalizarFaixa(
            request != null ? request.getChildProtectionCycles() : null,
            DEFAULT_CHILD_PROTECTION_CYCLES,
            0,
            10
        );
        MlLabCorpusService.Snapshot corpus = corpusService.carregar();
        LocalDateTime agora = LocalDateTime.now();
        MlLabRun run = runRepository.save(MlLabRun.builder()
            .status(MlLabRunStatus.CRIADO)
            .objetivo(objetivo)
            .totalCiclos(ciclos)
            .ciclosConcluidos(0)
            .totalAgentes(AGENT_COUNT)
            .bestScore(0)
            .externalWritesBlocked(operationalSafetyPolicy.externalWritesBlockedInLab())
            .realIaEnabled(usarIaReal)
            .iaProviderName(usarIaReal ? MlLabGuardas.textoOuPadrao(iaProvider.getNome(), "IaProvider") : "deterministico")
            .iaModelName(usarIaReal ? MlLabGuardas.textoOuPadrao(iaProvider.getModelo(), "modelo-configurado") : "offline")
            .maxTokensPerCall(maxTokens)
            .iaCallsTotal(0)
            .deterministicFallbackTotal(0)
            .selectionMode(selectionMode)
            .parallelWorkers(parallelWorkers)
            .eliteCount(eliteCount)
            .repechageCount(repechageCount)
            .childProtectionCycles(childProtectionCycles)
            .artifactDir("")
            .corpusSummary(corpus.summary())
            .dataCriacao(agora)
            .dataAtualizacao(agora)
            .build());
        Path artifactDir = Path.of(artifactRoot, "run-" + run.getId());
        run.setArtifactDir(artifactDir.toString());
        runRepository.save(run);
        criarAgentes(run.getId());
        salvarArtefato(run, "corpus-summary.json", Map.of(
            "runId", run.getId(),
            "summary", corpus.summary(),
            "snippets", corpus.snippets()
        ));
        return mapOverview(run);
    }

    @Override
    @Transactional
    public MlLabDtos.ActionResult iniciarRun(Long id) {
        MlLabRun run = buscarRun(id);
        if (run.getStatus() == MlLabRunStatus.CONCLUIDO) {
            return resultado(true, "Run ja concluido.", mapOverview(run));
        }
        if (agentRepository.countByRunId(id) != AGENT_COUNT) {
            throw new IllegalStateException("Run precisa ter exatamente 30 agentes antes de iniciar.");
        }
        if (run.isRealIaEnabled() && !iaProvider.estaDisponivel()) {
            run.setStatus(MlLabRunStatus.ERRO);
            run.setErro("IA real habilitada, mas provider indisponivel: " + run.getIaProviderName() + "/" + run.getIaModelName());
            run.setDataAtualizacao(LocalDateTime.now());
            runRepository.save(run);
            return resultado(false, run.getErro(), mapOverview(run));
        }

        run.setStatus(MlLabRunStatus.RODANDO);
        run.setErro(null);
        run.setDataAtualizacao(LocalDateTime.now());
        runRepository.save(run);

        if (run.isRealIaEnabled()) {
            if (!runningRuns.add(id)) {
                return resultado(true, "Run real ja esta rodando em background.", mapOverview(run));
            }
            agendarExecucaoAposCommit(id);
            return resultado(true, "Run real iniciado em background. Acompanhe no dashboard.", mapOverview(run));
        }

        return executarRun(id);
    }

    private void agendarExecucaoAposCommit(Long id) {
        Runnable tarefa = () -> {
            try {
                executarRun(id);
            } finally {
                runningRuns.remove(id);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            executor.submit(tarefa);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.submit(tarefa);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    runningRuns.remove(id);
                }
            }
        });
    }

    private MlLabDtos.ActionResult executarRun(Long id) {
        MlLabRun run = buscarRun(id);
        try {
            if (run.getStatus() == MlLabRunStatus.CRIADO) {
                run.setStatus(MlLabRunStatus.RODANDO);
            }
            MlLabCorpusService.Snapshot corpus = corpusService.carregar();
            run.setCorpusSummary(corpus.summary());
            run.setDataAtualizacao(LocalDateTime.now());
            runRepository.save(run);
            List<String> cenarios = cycleRunner().gerarCenarios(corpus.summary());
            for (int ciclo = run.getCiclosConcluidos() + 1; ciclo <= run.getTotalCiclos(); ciclo++) {
                run = buscarRun(id);
                if (run.getStatus() == MlLabRunStatus.PAUSADO || run.getStatus() == MlLabRunStatus.PARADO) {
                    return resultado(true, "Run interrompido em estado " + run.getStatus() + ".", mapOverview(run));
                }
                MlLabRun runAtual = run;
                cycleRunner().executarCiclo(
                    run,
                    ciclo,
                    cenarios.get((ciclo - 1) % cenarios.size()),
                    corpusService.contexto(corpus, ciclo),
                    (filename, payload) -> salvarArtefato(runAtual, filename, payload)
                );
            }
            run = buscarRun(id);
            // CONCLUIDO implica que o laco acima terminou todos os ciclos. Reforcar
            // a invariavel evita persistir status terminal com contador obsoleto
            // quando o start transacional e o worker rapido se intercalam.
            run.setCiclosConcluidos(run.getTotalCiclos());
            run.setStatus(MlLabRunStatus.CONCLUIDO);
            run.setDataAtualizacao(LocalDateTime.now());
            runRepository.save(run);
            salvarArtefato(run, "final-overview.json", mapOverview(run));
            salvarArtefato(run, "final-trained-agents.json", resumoFinalAgentes(run.getId()));
            return resultado(true, "Run concluido com 30 agentes e " + run.getTotalCiclos() + " ciclos.", mapOverview(run));
        } catch (Exception e) {
            log.error("Erro no ML Lab run {}: {}", id, e.getMessage(), e);
            run = buscarRun(id);
            run.setStatus(MlLabRunStatus.ERRO);
            run.setErro(e.getMessage());
            run.setDataAtualizacao(LocalDateTime.now());
            runRepository.save(run);
            return resultado(false, "Run falhou: " + e.getMessage(), mapOverview(run));
        }
    }

    @Override
    @Transactional
    public MlLabDtos.ActionResult pausarRun(Long id) {
        MlLabRun run = buscarRun(id);
        MlLabGuardas.exigirRunEmExecucaoParaPausar(run);
        if (run.getStatus() != MlLabRunStatus.CONCLUIDO) {
            run.setStatus(MlLabRunStatus.PAUSADO);
            run.setDataAtualizacao(LocalDateTime.now());
            runRepository.save(run);
        }
        return resultado(true, "Run pausado. Nenhum envio externo existe neste laboratorio.", mapOverview(run));
    }

    @Override
    @Transactional
    public MlLabDtos.ActionResult pararRun(Long id) {
        MlLabRun run = buscarRun(id);
        MlLabGuardas.exigirRunNaoParadoParaParar(run);
        if (run.getStatus() != MlLabRunStatus.CONCLUIDO) {
            run.setStatus(MlLabRunStatus.PARADO);
            run.setDataAtualizacao(LocalDateTime.now());
            runRepository.save(run);
        }
        return resultado(true, "Run parado. Estado preservado para auditoria.", mapOverview(run));
    }

    @Override
    @Transactional(readOnly = true)
    public MlLabDtos.RunOverview obterOverview(Long id) {
        return mapOverview(buscarRun(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MlLabDtos.RunOverview> listarRuns() {
        return runRepository.findTop20ByOrderByDataCriacaoDesc().stream()
            .map(this::mapOverview)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MlLabDtos.AgentView> listarAgentes(Long runId) {
        MlLabRun run = buscarRun(runId);
        List<MlLabAgent> agents = agentRepository.findByRunIdOrderByScoreTotalDesc(runId);
        return agents.stream()
            .map(agent -> mapAgent(agent, run, agents))
            .toList();
    }

    @Override
    @Transactional
    public MlLabDtos.ChatResponse conversar(Long runId, String agentKey, MlLabDtos.ChatRequest request) {
        MlLabAgent agent = agentRepository.findByRunIdAndAgentKey(runId, agentKey)
            .orElseThrow(() -> new IllegalArgumentException("Agente nao encontrado: " + agentKey));
        String mensagem = MlLabGuardas.exigirMensagemChat(request);
        List<String> regras = jsonHelper().lerLista(agent.getRegrasJson());
        List<String> duvidas = jsonHelper().lerDuvidas(agent.getMemoriaJson());
        String resposta = responderChat(agent, mensagem, regras, duvidas);
        // UPDATE so dos campos do chat (sem setters na entidade gerenciada); respostasTotal
        // NAO muda: ele pesa a media de avaliacao dos ciclos e o chat nao e avaliado.
        agentRepository.registrarRespostaChat(agent.getId(), resposta, LocalDateTime.now());
        return MlLabDtos.ChatResponse.builder()
            .runId(runId)
            .agentKey(agent.getAgentKey())
            .perfil(agent.getPerfil())
            .variante(agent.getVariante())
            .resposta(resposta)
            .regras(regras)
            .duvidas(duvidas)
            .scoreTotal(agent.getScoreTotal())
            .build();
    }

    @Override
    @Transactional
    public MlLabDtos.ActionResult aprovarCandidata(Long candidateId) {
        MlLabRuleCandidate candidate = candidateRepository.findById(candidateId)
            .orElseThrow(() -> new IllegalArgumentException("Regra candidata nao encontrada: " + candidateId));
        MlLabRun run = buscarRun(candidate.getRunId());
        if (MlLabGuardas.candidataJaAprovada(candidate)) {
            return resultado(true, MlLabGuardas.MSG_CANDIDATA_JA_APROVADA, mapOverview(run));
        }
        // Transicao condicional no banco: aprovacoes simultaneas nao duplicam o aprendizado.
        LocalDateTime agora = LocalDateTime.now();
        if (candidateRepository.aprovarSeAindaNaoAprovada(candidate.getId(), agora) == 0) {
            return resultado(true, MlLabGuardas.MSG_CANDIDATA_JA_APROVADA, mapOverview(run));
        }
        aprendizadoService.salvar(Aprendizado.builder()
            .data(LocalDateTime.now())
            .projetoId(candidate.getRunId())
            .tituloProjeto("ML Lab run " + candidate.getRunId() + " / " + candidate.getAgentKey())
            .foiAprovado(true)
            .motivo("ML_LAB_CANDIDATE_" + candidate.getId())
            .modoUsado(ModoSistema.ENSINAMENTO)
            .feedbackUsuario(candidate.getOrigem())
            .regrasAprendidas(candidate.getRegra())
            .motivoAprendizado(classificarMotivoMlLab(candidate.getRegra()))
            .statusRegra(StatusRegraAprendida.CONFIRMADA)
            .origemFeedback("ML_LAB_RUN_" + candidate.getRunId() + "_CYCLE_" + candidate.getCycleNumber())
            .build());
        return resultado(
            true,
            "Regra aprovada no laboratorio e aplicada na memoria principal do bot.",
            mapOverview(run)
        );
    }

    private MotivoAprendizado classificarMotivoMlLab(String regra) {
        MotivoAprendizado motivo = MotivoAprendizado.fromTexto(regra);
        return motivo != MotivoAprendizado.OUTRO ? motivo : MotivoAprendizado.ESCOPO_CLARO;
    }

    private void criarAgentes(Long runId) {
        List<MlLabAgentProfile> profiles = MlLabAgentCatalog.perfis();
        List<MlLabAgentVariant> variants = MlLabAgentCatalog.variantes();
        for (int profileIndex = 0; profileIndex < profiles.size(); profileIndex++) {
            MlLabAgentProfile profile = profiles.get(profileIndex);
            for (MlLabAgentVariant variant : variants) {
                String key = "P" + (profileIndex + 1) + "-" + variant.code();
                agentRepository.save(MlLabAgent.builder()
                    .runId(runId)
                    .agentKey(key)
                    .perfil(profile.name())
                    .variante(variant.name())
                    .estrategia(profile.strategy() + " | " + variant.strategy())
                    .scoreTotal(0)
                    .respostasTotal(0)
                    .memoriaJson(jsonHelper().json(Map.of(
                        "geracao", 1,
                        "linhagem", List.of("G1 base " + key),
                        "duvidas", List.of("Quais tipos de projeto devemos priorizar agora?"),
                        "aprendizados", List.of()
                    )))
                    .regrasJson(jsonHelper().json(new ArrayList<String>()))
                    .ultimaResposta("")
                    .dataAtualizacao(LocalDateTime.now())
                    .build());
            }
        }
    }

    private List<Map<String, Object>> resumoFinalAgentes(Long runId) { return viewMapper().resumoFinalAgentes(runId); }

    private MlLabDtos.RunOverview mapOverview(MlLabRun run) { return viewMapper().mapOverview(run); }

    private MlLabDtos.AgentView mapAgent(MlLabAgent agent, MlLabRun run, List<MlLabAgent> ranking) { return viewMapper().mapAgent(agent, run, ranking); }

    private String responderChat(MlLabAgent agent, String mensagem, List<String> regras, List<String> duvidas) { return viewMapper().responderChat(agent, mensagem, regras, duvidas); }

    private void salvarArtefato(MlLabRun run, String filename, Object payload) {
        try {
            Path dir = Path.of(run.getArtifactDir());
            Files.createDirectories(dir);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(filename).toFile(), payload);
        } catch (Exception e) {
            log.warn("Nao foi possivel salvar artefato ML Lab {}: {}", filename, e.getMessage());
        }
    }

    private MlLabRun buscarRun(Long id) {
        return runRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Run nao encontrado: " + id));
    }

    private MlLabDtos.ActionResult resultado(boolean sucesso, String mensagem, MlLabDtos.RunOverview overview) {
        return MlLabDtos.ActionResult.builder()
            .sucesso(sucesso)
            .mensagem(mensagem)
            .overview(overview)
            .build();
    }
}
