package com.freelasatende.service.impl;

import com.freelasatende.model.dto.MlLabDtos;
import com.freelasatende.model.entity.MlLabAgent;
import com.freelasatende.model.entity.MlLabCycle;
import com.freelasatende.model.entity.MlLabRuleCandidate;
import com.freelasatende.model.entity.MlLabRun;
import com.freelasatende.policy.OperationalSafetyPolicy;
import com.freelasatende.repository.MlLabAgentRepository;
import com.freelasatende.repository.MlLabCycleRepository;
import com.freelasatende.repository.MlLabRuleCandidateRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class MlLabViewMapper {

    private final MlLabAgentRepository agentRepository;
    private final MlLabCycleRepository cycleRepository;
    private final MlLabRuleCandidateRepository candidateRepository;
    private final OperationalSafetyPolicy operationalSafetyPolicy;
    private final MlLabJsonHelper jsonHelper;
    private final MlLabEvolutionEngine evolutionEngine;

    MlLabViewMapper(
        MlLabAgentRepository agentRepository,
        MlLabCycleRepository cycleRepository,
        MlLabRuleCandidateRepository candidateRepository,
        OperationalSafetyPolicy operationalSafetyPolicy,
        MlLabJsonHelper jsonHelper,
        MlLabEvolutionEngine evolutionEngine
    ) {
        this.agentRepository = agentRepository;
        this.cycleRepository = cycleRepository;
        this.candidateRepository = candidateRepository;
        this.operationalSafetyPolicy = operationalSafetyPolicy;
        this.jsonHelper = jsonHelper;
        this.evolutionEngine = evolutionEngine;
    }

    List<Map<String, Object>> resumoFinalAgentes(Long runId) {
        return agentRepository.findByRunIdOrderByScoreTotalDesc(runId).stream()
            .map(agent -> {
                List<String> regras = jsonHelper.lerLista(agent.getRegrasJson());
                List<String> duvidas = jsonHelper.lerDuvidas(agent.getMemoriaJson());
                Map<String, Object> memoria = jsonHelper.lerMemoria(agent.getMemoriaJson());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("agentKey", agent.getAgentKey());
                item.put("geracao", evolutionEngine.intMemoria(memoria, "geracao", 1));
                item.put("perfilFinal", agent.getPerfil());
                item.put("varianteFinal", agent.getVariante());
                item.put("scoreTotal", MlLabTextHelper.arredondar(agent.getScoreTotal()));
                item.put("respostasTotal", agent.getRespostasTotal());
                item.put("identidadeFinal", evolutionEngine.identidadeFinal(agent, regras, duvidas));
                item.put("regrasFinais", regras);
                item.put("duvidasResiduais", duvidas);
                item.put("linhagem", jsonHelper.asStringList(memoria.get("linhagem")));
                item.put("estrategiaFinal", agent.getEstrategia());
                return item;
            })
            .toList();
    }

    MlLabDtos.RunOverview mapOverview(MlLabRun run) {
        List<MlLabAgent> rankedAgents = agentRepository.findByRunIdOrderByScoreTotalDesc(run.getId());
        int geracaoAtual = evolutionEngine.geracaoAtual(rankedAgents);
        return MlLabDtos.RunOverview.builder()
            .id(run.getId())
            .status(run.getStatus())
            .objetivo(run.getObjetivo())
            .totalCiclos(run.getTotalCiclos())
            .ciclosConcluidos(run.getCiclosConcluidos())
            .totalAgentes(run.getTotalAgentes())
            .bestAgentKey(run.getBestAgentKey())
            .bestScore(MlLabTextHelper.arredondar(run.getBestScore()))
            .externalWritesBlocked(run.isExternalWritesBlocked())
            .realIaEnabled(run.isRealIaEnabled())
            .iaProviderName(run.getIaProviderName())
            .iaModelName(run.getIaModelName())
            .maxTokensPerCall(run.getMaxTokensPerCall())
            .iaCallsTotal(run.getIaCallsTotal())
            .deterministicFallbackTotal(run.getDeterministicFallbackTotal())
            .geracaoAtual(geracaoAtual)
            .evolucoesTotal(Math.max(0, geracaoAtual - 1))
            .modoEvolucao(evolutionEngine.modoEvolucao(run))
            .artifactDir(run.getArtifactDir())
            .corpusSummary(run.getCorpusSummary())
            .erro(run.getErro())
            .dataCriacao(run.getDataCriacao())
            .dataAtualizacao(run.getDataAtualizacao())
            .selectionMode(evolutionEngine.selectionMode(run))
            .parallelWorkers(evolutionEngine.parallelWorkers(run))
            .eliteCount(evolutionEngine.eliteCount(run))
            .repechageCount(evolutionEngine.repechageCount(run))
            .childProtectionCycles(evolutionEngine.childProtectionCycles(run))
            .agents(rankedAgents.stream().map(agent -> mapAgent(agent, run, rankedAgents)).limit(30).toList())
            .recentCycles(cycleRepository.findTop10ByRunIdOrderByNumeroDesc(run.getId()).stream().map(this::mapCycle).toList())
            .candidates(candidateRepository.findTop20ByRunIdOrderByScoreDesc(run.getId()).stream().map(this::mapCandidate).toList())
            .safety(operationalSafetyPolicy.mlLabSafetySnapshot(
                run.isRealIaEnabled(),
                run.getIaProviderName(),
                run.getIaModelName(),
                evolutionEngine.selectionMode(run),
                evolutionEngine.parallelWorkers(run)
            ))
            .build();
    }

    MlLabDtos.AgentView mapAgent(MlLabAgent agent, MlLabRun run, List<MlLabAgent> ranking) {
        Map<String, Object> memoria = jsonHelper.lerMemoria(agent.getMemoriaJson());
        int geracao = evolutionEngine.intMemoria(memoria, "geracao", 1);
        String linhagem = String.join(" -> ", jsonHelper.asStringList(memoria.get("linhagem")).stream().limit(6).toList());
        List<String> regras = jsonHelper.lerLista(agent.getRegrasJson());
        List<String> duvidas = jsonHelper.lerDuvidas(agent.getMemoriaJson());
        MlLabSelectionDecision decision = evolutionEngine.selectionDecision(run, ranking, agent, run.getCiclosConcluidos());
        int rank = evolutionEngine.selectionRank(run, ranking, agent);
        String band = evolutionEngine.selectionBand(run, agent, rank);
        Map<String, MlLabAgent> byKey = ranking.stream().collect(java.util.stream.Collectors.toMap(MlLabAgent::getAgentKey, item -> item));
        return MlLabDtos.AgentView.builder()
            .id(agent.getId())
            .agentKey(agent.getAgentKey())
            .perfil(agent.getPerfil())
            .variante(agent.getVariante())
            .estrategia(agent.getEstrategia())
            .scoreTotal(MlLabTextHelper.arredondar(agent.getScoreTotal()))
            .respostasTotal(agent.getRespostasTotal())
            .geracao(geracao)
            .fitnessScore(decision.fitnessScore())
            .selectionBand(band)
            .parentDelta(evolutionEngine.parentDelta(agent, byKey))
            .protectedCyclesLeft(evolutionEngine.protectedCyclesLeft(agent, run, run.getCiclosConcluidos()))
            .selectionReason(decision.reason())
            .linhagem(linhagem)
            .identidadeFinal(evolutionEngine.identidadeFinal(agent, regras, duvidas))
            .ultimaResposta(agent.getUltimaResposta())
            .regras(regras)
            .duvidas(duvidas)
            .build();
    }

    MlLabDtos.CycleView mapCycle(MlLabCycle cycle) {
        return MlLabDtos.CycleView.builder()
            .numero(cycle.getNumero())
            .cenario(cycle.getCenario())
            .bestAgentKey(cycle.getBestAgentKey())
            .bestScore(MlLabTextHelper.arredondar(cycle.getBestScore()))
            .resumo(cycle.getResumo())
            .dataCriacao(cycle.getDataCriacao())
            .build();
    }

    MlLabDtos.RuleCandidateView mapCandidate(MlLabRuleCandidate candidate) {
        return MlLabDtos.RuleCandidateView.builder()
            .id(candidate.getId())
            .agentKey(candidate.getAgentKey())
            .cycleNumber(candidate.getCycleNumber())
            .regra(candidate.getRegra())
            .origem(candidate.getOrigem())
            .score(MlLabTextHelper.arredondar(candidate.getScore()))
            .status(candidate.getStatus())
            .dataCriacao(candidate.getDataCriacao())
            .build();
    }

    String responderChat(MlLabAgent agent, String mensagem, List<String> regras, List<String> duvidas) {
        String texto = mensagem == null ? "" : mensagem.toLowerCase(Locale.ROOT);
        if (texto.contains("regra")) {
            return regras.isEmpty()
                ? "Ainda nao tenho regras maduras. Rode ciclos ou aprove candidatas no laboratorio."
                : "Minhas regras de laboratorio: " + String.join(" | ", regras.stream().limit(8).toList());
        }
        if (texto.contains("duvida") || texto.contains("nao sabe") || texto.contains("não sabe")) {
            return duvidas.isEmpty()
                ? "Sem duvidas abertas. Preciso de novos cenarios para refinar."
                : "Duvidas abertas: " + String.join(" | ", duvidas.stream().limit(8).toList());
        }
        if (texto.contains("virou") || texto.contains("tornou") || texto.contains("quem e") || texto.contains("quem é")
            || texto.contains("final") || texto.contains("identidade")) {
            return evolutionEngine.identidadeFinal(agent, regras, duvidas);
        }
        return evolutionEngine.identidadeFinal(agent, regras, duvidas)
            + " Posso explicar minhas regras finais, linhagem ou como eu decidiria um projeto, sempre sem envio externo.";
    }
}
