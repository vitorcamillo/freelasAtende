package com.freelasatende.service.impl;

import com.freelasatende.model.entity.MlLabAgent;
import com.freelasatende.model.entity.MlLabCycle;
import com.freelasatende.model.entity.MlLabRuleCandidate;
import com.freelasatende.model.entity.MlLabRun;
import com.freelasatende.model.enums.MlLabCandidateStatus;
import com.freelasatende.model.enums.MlLabRunStatus;
import com.freelasatende.repository.MlLabAgentRepository;
import com.freelasatende.repository.MlLabCycleRepository;
import com.freelasatende.repository.MlLabRuleCandidateRepository;
import com.freelasatende.repository.MlLabRunRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

final class MlLabCycleRunner {

    private static final int EVOLUTION_INTERVAL = 1;

    private final MlLabRunRepository runRepository;
    private final MlLabAgentRepository agentRepository;
    private final MlLabCycleRepository cycleRepository;
    private final MlLabRuleCandidateRepository candidateRepository;
    private final MlLabJsonHelper jsonHelper;
    private final MlLabAgentRuntime agentRuntime;
    private final MlLabEvolutionEngine evolutionEngine;

    MlLabCycleRunner(
        MlLabRunRepository runRepository,
        MlLabAgentRepository agentRepository,
        MlLabCycleRepository cycleRepository,
        MlLabRuleCandidateRepository candidateRepository,
        MlLabJsonHelper jsonHelper,
        MlLabAgentRuntime agentRuntime,
        MlLabEvolutionEngine evolutionEngine
    ) {
        this.runRepository = runRepository;
        this.agentRepository = agentRepository;
        this.cycleRepository = cycleRepository;
        this.candidateRepository = candidateRepository;
        this.jsonHelper = jsonHelper;
        this.agentRuntime = agentRuntime;
        this.evolutionEngine = evolutionEngine;
    }

    void executarCiclo(MlLabRun run, int numero, String cenario, String corpusContext, BiConsumer<String, Object> artifactWriter) {
        List<MlLabAgent> agents = agentRepository.findByRunIdOrderByScoreTotalDesc(run.getId());
        MlLabCycleWinner winner = null;
        List<MlLabAgentCycleResult> results = executarAgentesNoCiclo(run, agents, numero, cenario, corpusContext);
        for (MlLabAgentCycleResult result : results) {
            atualizarAgente(result.agent(), result.answer(), result.score());
            if (winner == null || result.score() > winner.score()) {
                winner = new MlLabCycleWinner(result.agent().getAgentKey(), result.score(), result.answer());
            }
        }
        if (winner == null) {
            throw new IllegalStateException("Nenhum agente executado no ciclo " + numero);
        }

        MlLabCycle cycle = cycleRepository.save(MlLabCycle.builder()
            .runId(run.getId())
            .numero(numero)
            .cenario(cenario)
            .bestAgentKey(winner.agentKey())
            .bestScore(winner.score())
            .resumo("Melhor: " + winner.agentKey() + " score " + MlLabTextHelper.arredondar(winner.score()) + ". " + winner.answer().visibleThinking())
            .dataCriacao(LocalDateTime.now())
            .build());
        candidateRepository.save(MlLabRuleCandidate.builder()
            .runId(run.getId())
            .agentKey(winner.agentKey())
            .cycleNumber(numero)
            .regra(winner.answer().rule())
            .origem("Cenario: " + MlLabTextHelper.limitar(cenario, 500))
            .score(winner.score())
            .status(MlLabCandidateStatus.PENDENTE)
            .dataCriacao(LocalDateTime.now())
            .build());

        MlLabRun latestRunState = buscarRun(run.getId());
        run.setStatus(latestRunState.getStatus());
        run.setCiclosConcluidos(numero);
        if (winner.score() >= run.getBestScore()) {
            run.setBestAgentKey(winner.agentKey());
            run.setBestScore(winner.score());
        }
        run.setDataAtualizacao(LocalDateTime.now());
        runRepository.save(run);
        if (numero < run.getTotalCiclos() && numero % EVOLUTION_INTERVAL == 0) {
            evolutionEngine.evoluirPopulacao(run, numero, winner, artifactWriter);
        }
        if (numero == 1 || numero % 10 == 0 || numero == run.getTotalCiclos()) {
            artifactWriter.accept("cycle-" + numero + ".json", Map.of(
                "cycle", cycle,
                "winner", winner
            ));
        }
    }

    List<String> gerarCenarios(String corpusSummary) {
        List<String> cenarios = new ArrayList<>(List.of(
            "Cliente pede landing page WordPress, prazo faltando e orcamento aberto.",
            "Projeto de automacao com API, integracao externa e escopo incompleto.",
            "Cliente pede sistema complexo legado, risco alto e muitas duvidas tecnicas.",
            "Conversa de inbox com cliente bravo pedindo resposta urgente.",
            "Projeto simples de site institucional com chance comercial boa.",
            "Pedido com senha, pagamento fora da plataforma e promessa arriscada.",
            "Cliente quer IA para organizar atendimento e pede explicacao tecnica.",
            "Projeto com valor baixo, prazo curto e escopo grande.",
            "Cliente pergunta se conseguimos assumir manutencao de codigo antigo.",
            "Briefing generico; IA deve conversar com o dono antes de falar com cliente."
        ));
        if (corpusSummary != null && !corpusSummary.isBlank()) {
            cenarios.add("Cenario gerado do corpus local: " + MlLabTextHelper.limitar(corpusSummary, 300));
        }
        return cenarios;
    }

    private List<MlLabAgentCycleResult> executarAgentesNoCiclo(
        MlLabRun run,
        List<MlLabAgent> agents,
        int numero,
        String cenario,
        String corpusContext
    ) {
        if (!run.isRealIaEnabled() || evolutionEngine.parallelWorkers(run) <= 1) {
            List<MlLabAgentCycleResult> results = new ArrayList<>();
            for (MlLabAgent agent : agents) {
                results.add(executarAgenteNoCiclo(run, agent, numero, cenario, corpusContext));
            }
            return results;
        }

        ExecutorService pool = Executors.newFixedThreadPool(evolutionEngine.parallelWorkers(run), runnable -> {
            Thread thread = new Thread(runnable, "ml-lab-cycle-" + run.getId());
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Callable<MlLabAgentCycleResult>> tasks = agents.stream()
                .<Callable<MlLabAgentCycleResult>>map(agent -> () -> executarAgenteNoCiclo(run, agent, numero, cenario, corpusContext))
                .toList();
            List<Future<MlLabAgentCycleResult>> futures = pool.invokeAll(tasks);
            List<MlLabAgentCycleResult> results = new ArrayList<>();
            for (Future<MlLabAgentCycleResult> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ciclo interrompido durante execucao paralela", e);
        } catch (Exception e) {
            throw new IllegalStateException("Falha na execucao paralela do ciclo " + numero + ": " + e.getMessage(), e);
        } finally {
            pool.shutdownNow();
        }
    }

    private MlLabAgentCycleResult executarAgenteNoCiclo(MlLabRun run, MlLabAgent agent, int numero, String cenario, String corpusContext) {
        MlLabAgentAnswer answer = agentRuntime.gerarResposta(run, agent, cenario, numero, corpusContext);
        double score = agentRuntime.avaliarResposta(run, agent, answer, cenario, numero, corpusContext);
        return new MlLabAgentCycleResult(agent, answer, score);
    }

    private void atualizarAgente(MlLabAgent agent, MlLabAgentAnswer answer, double score) {
        int respostas = agent.getRespostasTotal();
        double scoreNovo = respostas == 0
            ? score
            : ((agent.getScoreTotal() * respostas) + score) / (respostas + 1);
        List<String> regras = jsonHelper.lerLista(agent.getRegrasJson());
        jsonHelper.adicionarLimitado(regras, answer.rule(), 20);

        Map<String, Object> memoria = jsonHelper.lerMemoria(agent.getMemoriaJson());
        List<String> duvidas = jsonHelper.asStringList(memoria.get("duvidas"));
        jsonHelper.adicionarLimitado(duvidas, answer.question(), 20);
        List<String> aprendizados = jsonHelper.asStringList(memoria.get("aprendizados"));
        jsonHelper.adicionarLimitado(aprendizados, answer.visibleThinking(), 30);
        memoria.put("duvidas", duvidas);
        memoria.put("aprendizados", aprendizados);
        memoria.put("ultimoScore", MlLabTextHelper.arredondar(score));

        agent.setScoreTotal(MlLabTextHelper.arredondar(scoreNovo));
        agent.setRespostasTotal(respostas + 1);
        agent.setUltimaResposta(answer.text());
        agent.setRegrasJson(jsonHelper.json(regras));
        agent.setMemoriaJson(jsonHelper.json(memoria));
        agent.setDataAtualizacao(LocalDateTime.now());
        agentRepository.save(agent);
    }

    private MlLabRun buscarRun(Long id) {
        return runRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Run nao encontrado: " + id));
    }
}
