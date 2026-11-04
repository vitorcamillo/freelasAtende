package com.freelasatende.service.impl;

import com.freelasatende.model.entity.MlLabAgent;
import com.freelasatende.model.entity.MlLabRun;
import com.freelasatende.repository.MlLabAgentRepository;
import com.freelasatende.repository.MlLabRunRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

final class MlLabEvolutionEngine {

    private static final int AGENT_COUNT = 30;
    private static final int EVOLUTION_SURVIVORS = 10;
    private static final String SELECTION_TOP10 = "TOP10";
    private static final String SELECTION_MULTI_CRITERIA = "MULTI_CRITERIA";
    private static final int DEFAULT_PARALLEL_WORKERS = 6;
    private static final int DEFAULT_ELITE_COUNT = 6;
    private static final int DEFAULT_REPECHAGE_COUNT = 9;
    private static final int DEFAULT_CHILD_PROTECTION_CYCLES = 2;

    private final MlLabAgentRepository agentRepository;
    private final MlLabRunRepository runRepository;
    private final MlLabJsonHelper jsonHelper;

    MlLabEvolutionEngine(MlLabAgentRepository agentRepository, MlLabRunRepository runRepository, MlLabJsonHelper jsonHelper) {
        this.agentRepository = agentRepository;
        this.runRepository = runRepository;
        this.jsonHelper = jsonHelper;
    }

    void evoluirPopulacao(MlLabRun run, int ciclo, MlLabCycleWinner winner, BiConsumer<String, Object> artifactWriter) {
        List<MlLabAgent> ranking = agentRepository.findByRunIdOrderByScoreTotalDesc(run.getId());
        if (ranking.size() < AGENT_COUNT) {
            throw new IllegalStateException("Populacao incompleta para evolucao: " + ranking.size());
        }
        int proximaGeracao = geracaoAtual(ranking) + 1;
        List<MlLabSelectionDecision> decisions = selecionarPopulacao(run, ranking, ciclo);
        List<MlLabAgent> pais = decisions.stream()
            .filter(MlLabSelectionDecision::survives)
            .map(MlLabSelectionDecision::agent)
            .limit(EVOLUTION_SURVIVORS)
            .toList();
        if (pais.isEmpty()) {
            pais = ranking.stream().limit(EVOLUTION_SURVIVORS).toList();
        }
        Set<Long> survivorIds = decisions.stream()
            .filter(MlLabSelectionDecision::survives)
            .map(decision -> decision.agent().getId())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<MlLabAgent> substituidos = decisions.stream()
            .filter(decision -> !survivorIds.contains(decision.agent().getId()))
            .map(MlLabSelectionDecision::agent)
            .toList();
        for (MlLabAgent pai : pais) {
            marcarSobrevivente(pai, proximaGeracao, ciclo, winner);
        }
        for (MlLabSelectionDecision decision : decisions) {
            if (decision.survives() && !pais.contains(decision.agent())) {
                marcarSobrevivente(decision.agent(), proximaGeracao, ciclo, winner);
            }
        }
        for (int index = 0; index < substituidos.size(); index++) {
            MlLabAgent filho = substituidos.get(index);
            MlLabAgent paiA = pais.get(index % pais.size());
            MlLabAgent paiB = pais.get((index * 3 + 1) % pais.size());
            mutarFilho(filho, paiA, paiB, proximaGeracao, ciclo, index + 1);
        }
        run.setCorpusSummary(resumoCorpusEvolucao(run, proximaGeracao, substituidos.size()));
        run.setDataAtualizacao(LocalDateTime.now());
        runRepository.save(run);
        artifactWriter.accept("evolution-cycle-" + ciclo + ".json", Map.of(
            "cycle", ciclo,
            "generation", proximaGeracao,
            "winner", winner,
            "selectionMode", selectionMode(run),
            "decisions", decisions.stream().map(this::decisionArtifact).toList(),
            "parents", pais.stream().map(MlLabAgent::getAgentKey).toList(),
            "mutated", substituidos.stream().map(MlLabAgent::getAgentKey).toList()
        ));
    }

    List<MlLabSelectionDecision> selecionarPopulacao(MlLabRun run, List<MlLabAgent> ranking, int ciclo) {
        if (!SELECTION_MULTI_CRITERIA.equals(selectionMode(run))) {
            return ranking.stream()
                .map(agent -> new MlLabSelectionDecision(agent, MlLabTextHelper.arredondar(agent.getScoreTotal()), "TOP10", ranking.indexOf(agent) < EVOLUTION_SURVIVORS, "Top 10 por score medio"))
                .toList();
        }
        List<MlLabSelectionDecision> scored = ranking.stream()
            .map(agent -> selectionDecision(run, ranking, agent, ciclo))
            .sorted(Comparator.comparingDouble(MlLabSelectionDecision::fitnessScore).reversed())
            .toList();
        Set<Long> survivors = new LinkedHashSet<>();
        for (int i = 0; i < scored.size() && i < eliteCount(run); i++) {
            survivors.add(scored.get(i).agent().getId());
        }
        int repechageLimit = Math.min(scored.size(), eliteCount(run) + repechageCount(run));
        for (int i = eliteCount(run); i < repechageLimit; i++) {
            survivors.add(scored.get(i).agent().getId());
        }
        for (MlLabSelectionDecision decision : scored) {
            if (protectedCyclesLeft(decision.agent(), run, ciclo) > 0) {
                survivors.add(decision.agent().getId());
            }
        }
        List<MlLabSelectionDecision> finalDecisions = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            MlLabSelectionDecision decision = scored.get(i);
            String band = i < eliteCount(run)
                ? "ELITE"
                : i < repechageLimit ? "REPESCAGEM" : protectedCyclesLeft(decision.agent(), run, ciclo) > 0 ? "PROTEGIDO" : "CORTE";
            String reason = band + " fitness=" + MlLabTextHelper.arredondar(decision.fitnessScore()) + "; " + decision.reason();
            finalDecisions.add(new MlLabSelectionDecision(decision.agent(), decision.fitnessScore(), band, survivors.contains(decision.agent().getId()), reason));
        }
        return finalDecisions;
    }

    MlLabSelectionDecision selectionDecision(MlLabRun run, List<MlLabAgent> ranking, MlLabAgent agent, int ciclo) {
        Map<String, MlLabAgent> byKey = ranking.stream().collect(java.util.stream.Collectors.toMap(MlLabAgent::getAgentKey, agentItem -> agentItem));
        double score = Math.max(0, Math.min(100, agent.getScoreTotal()));
        double parentDelta = parentDelta(agent, byKey);
        double ruleQuality = ruleQuality(agent);
        double naturalidade = naturalidade(agent);
        double diversidade = diversityScore(agent);
        double reliability = reliabilityScore(agent);
        double improvement = Math.max(0, Math.min(100, 50 + parentDelta));
        double fitness = (score * 0.40) + (improvement * 0.20) + (ruleQuality * 0.15)
            + (naturalidade * 0.10) + (diversidade * 0.10) + (reliability * 0.05);
        String reason = "score " + MlLabTextHelper.arredondar(score)
            + ", deltaPais " + MlLabTextHelper.arredondar(parentDelta)
            + ", regra " + MlLabTextHelper.arredondar(ruleQuality)
            + ", naturalidade " + MlLabTextHelper.arredondar(naturalidade)
            + ", protegido " + protectedCyclesLeft(agent, run, ciclo);
        return new MlLabSelectionDecision(agent, MlLabTextHelper.arredondar(fitness), "PENDENTE", false, reason);
    }

    int selectionRank(MlLabRun run, List<MlLabAgent> ranking, MlLabAgent agent) {
        if (!SELECTION_MULTI_CRITERIA.equals(selectionMode(run))) {
            return ranking.indexOf(agent) + 1;
        }
        List<MlLabAgent> fitnessRanking = ranking.stream()
            .sorted(Comparator.comparingDouble((MlLabAgent candidate) -> selectionDecision(run, ranking, candidate, run.getCiclosConcluidos()).fitnessScore()).reversed())
            .toList();
        return fitnessRanking.indexOf(agent) + 1;
    }

    String selectionBand(MlLabRun run, MlLabAgent agent, int rank) {
        if (!SELECTION_MULTI_CRITERIA.equals(selectionMode(run))) {
            return rank <= EVOLUTION_SURVIVORS ? "TOP10" : "CORTE";
        }
        if (rank <= eliteCount(run)) return "ELITE";
        if (rank <= eliteCount(run) + repechageCount(run)) return "REPESCAGEM";
        if (protectedCyclesLeft(agent, run, run.getCiclosConcluidos()) > 0) return "PROTEGIDO";
        return "CORTE";
    }

    int geracaoAtual(List<MlLabAgent> agents) {
        return agents.stream()
            .map(MlLabAgent::getMemoriaJson)
            .map(jsonHelper::lerMemoria)
            .mapToInt(memoria -> intMemoria(memoria, "geracao", 1))
            .max()
            .orElse(1);
    }

    int intMemoria(Map<String, Object> memoria, String field, int fallback) {
        Object value = memoria.get(field);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    double parentDelta(MlLabAgent agent, Map<String, MlLabAgent> byKey) {
        List<String> pais = parentKeys(agent);
        List<Double> parentScores = pais.stream()
            .map(byKey::get)
            .filter(parent -> parent != null && parent.getRespostasTotal() > 0)
            .map(MlLabAgent::getScoreTotal)
            .toList();
        if (parentScores.isEmpty()) return 0;
        double avg = parentScores.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return MlLabTextHelper.arredondar(agent.getScoreTotal() - avg);
    }

    int protectedCyclesLeft(MlLabAgent agent, MlLabRun run, int ciclo) {
        if (!SELECTION_MULTI_CRITERIA.equals(selectionMode(run))) return 0;
        Map<String, Object> memoria = jsonHelper.lerMemoria(agent.getMemoriaJson());
        if (!"filho-mutacao".equals(String.valueOf(memoria.getOrDefault("tipoEvolucao", "")))) return 0;
        int criadoNoCiclo = intMemoria(memoria, "cicloCriacao", ciclo);
        int idade = Math.max(0, ciclo - criadoNoCiclo);
        return Math.max(0, childProtectionCycles(run) - idade);
    }

    String identidadeFinal(MlLabAgent agent, List<String> regras, List<String> duvidas) {
        Map<String, Object> memoria = jsonHelper.lerMemoria(agent.getMemoriaJson());
        int geracao = intMemoria(memoria, "geracao", 1);
        String tipo = textoOuPadrao(String.valueOf(memoria.getOrDefault("tipoEvolucao", "base")), "base");
        String regraPrincipal = regras.isEmpty() ? "ainda sem regra dominante" : regras.get(0);
        String duvidaResidual = duvidas.isEmpty() ? "sem duvida residual registrada" : duvidas.get(0);
        return "Identidade final/provisoria: " + agent.getAgentKey()
            + " virou uma IA " + agent.getPerfil()
            + ", geracao G" + geracao
            + ", tipo " + tipo
            + ", score medio " + MlLabTextHelper.arredondar(agent.getScoreTotal())
            + ". Regra dominante: " + regraPrincipal
            + ". Duvida residual: " + duvidaResidual + ".";
    }

    String selectionMode(MlLabRun run) { return normalizarSelectionMode(run.getSelectionMode()); }

    String modoEvolucao(MlLabRun run) {
        if (SELECTION_MULTI_CRITERIA.equals(selectionMode(run))) {
            return "Multi-criterio: elite " + eliteCount(run)
                + ", repescagem " + repechageCount(run)
                + ", filhos protegidos por " + childProtectionCycles(run)
                + " ciclos, " + parallelWorkers(run) + " workers.";
        }
        return "Top " + EVOLUTION_SURVIVORS + " sobrevivem; demais viram filhos/mutacoes apos cada ciclo.";
    }

    int parallelWorkers(MlLabRun run) {
        return normalizarFaixa(run.getParallelWorkers(), DEFAULT_PARALLEL_WORKERS, 1, AGENT_COUNT);
    }

    int eliteCount(MlLabRun run) {
        return normalizarFaixa(run.getEliteCount(), DEFAULT_ELITE_COUNT, 1, AGENT_COUNT);
    }

    int repechageCount(MlLabRun run) {
        return normalizarFaixa(run.getRepechageCount(), DEFAULT_REPECHAGE_COUNT, 0, AGENT_COUNT - eliteCount(run));
    }

    int childProtectionCycles(MlLabRun run) {
        return normalizarFaixa(run.getChildProtectionCycles(), DEFAULT_CHILD_PROTECTION_CYCLES, 0, 10);
    }

    private Map<String, Object> decisionArtifact(MlLabSelectionDecision decision) {
        return Map.of(
            "agentKey", decision.agent().getAgentKey(),
            "fitnessScore", MlLabTextHelper.arredondar(decision.fitnessScore()),
            "band", decision.band(),
            "survives", decision.survives(),
            "reason", decision.reason()
        );
    }

    private void marcarSobrevivente(MlLabAgent agent, int geracao, int ciclo, MlLabCycleWinner winner) {
        Map<String, Object> memoria = jsonHelper.lerMemoria(agent.getMemoriaJson());
        memoria.put("geracao", geracao);
        memoria.put("tipoEvolucao", "sobrevivente");
        memoria.put("ultimoCicloEvolucao", ciclo);
        memoria.put("motivoSobrevivencia", "Top " + EVOLUTION_SURVIVORS + " no ranking; vencedor ciclo " + winner.agentKey());
        memoria.put("cicloCriacao", memoria.getOrDefault("cicloCriacao", 0));
        List<String> linhagem = jsonHelper.asStringList(memoria.get("linhagem"));
        jsonHelper.adicionarLimitado(linhagem, "G" + geracao + " sobreviveu no ciclo " + ciclo + " como " + agent.getAgentKey(), 20);
        memoria.put("linhagem", linhagem);
        agent.setVariante("G" + geracao + " sobrevivente");
        agent.setMemoriaJson(jsonHelper.json(memoria));
        agent.setDataAtualizacao(LocalDateTime.now());
        agentRepository.save(agent);
    }

    private void mutarFilho(MlLabAgent filho, MlLabAgent paiA, MlLabAgent paiB, int geracao, int ciclo, int mutationIndex) {
        Map<String, Object> memoriaPaiA = jsonHelper.lerMemoria(paiA.getMemoriaJson());
        Map<String, Object> memoriaPaiB = jsonHelper.lerMemoria(paiB.getMemoriaJson());
        List<String> regras = new ArrayList<>();
        regras.addAll(jsonHelper.lerLista(paiA.getRegrasJson()).stream().limit(8).toList());
        regras.addAll(jsonHelper.lerLista(paiB.getRegrasJson()).stream().limit(8).toList());
        regras.add("G" + geracao + ": combinar pontos fortes de " + paiA.getAgentKey()
            + " e " + paiB.getAgentKey() + ", mantendo aprovacao manual e pergunta antes de promessa.");

        List<String> duvidas = new ArrayList<>();
        duvidas.addAll(jsonHelper.asStringList(memoriaPaiA.get("duvidas")).stream().limit(5).toList());
        duvidas.addAll(jsonHelper.asStringList(memoriaPaiB.get("duvidas")).stream().limit(5).toList());
        jsonHelper.adicionarLimitado(duvidas, "O que esta geracao ainda nao sabe sobre nosso criterio de projeto bom?", 12);

        List<String> aprendizados = new ArrayList<>();
        aprendizados.addAll(jsonHelper.asStringList(memoriaPaiA.get("aprendizados")).stream().limit(6).toList());
        aprendizados.addAll(jsonHelper.asStringList(memoriaPaiB.get("aprendizados")).stream().limit(6).toList());
        jsonHelper.adicionarLimitado(aprendizados, "Mutacao G" + geracao + " criada no ciclo " + ciclo
            + " a partir de " + paiA.getAgentKey() + " + " + paiB.getAgentKey() + ".", 20);

        Map<String, Object> memoria = new LinkedHashMap<>();
        memoria.put("geracao", geracao);
        memoria.put("tipoEvolucao", "filho-mutacao");
        memoria.put("cicloCriacao", ciclo);
        memoria.put("ultimoCicloEvolucao", ciclo);
        memoria.put("pais", List.of(paiA.getAgentKey(), paiB.getAgentKey()));
        memoria.put("linhagem", List.of(
            "G" + geracao + " filho de " + paiA.getAgentKey() + " + " + paiB.getAgentKey(),
            "Substituiu " + filho.getAgentKey() + " no ciclo " + ciclo
        ));
        memoria.put("duvidas", duvidas.stream().distinct().limit(12).toList());
        memoria.put("aprendizados", aprendizados.stream().distinct().limit(20).toList());
        memoria.put("mutacao", "M" + mutationIndex + ": " + focoMutacao(mutationIndex));

        filho.setPerfil(MlLabTextHelper.limitar("G" + geracao + " filho " + paiA.getAgentKey() + "+" + paiB.getAgentKey(), 240));
        filho.setVariante("mutacao " + mutationIndex);
        filho.setEstrategia(MlLabTextHelper.limitar("Evolucao automatica. Herdou de " + paiA.getPerfil() + " e " + paiB.getPerfil()
            + ". Foco: " + focoMutacao(mutationIndex) + ". Regras herdadas: " + String.join(" | ", regras.stream().limit(4).toList()), 690));
        filho.setScoreTotal(0);
        filho.setRespostasTotal(0);
        filho.setUltimaResposta("G" + geracao + " criada automaticamente a partir dos melhores agentes; aguardando proximo teste.");
        filho.setRegrasJson(jsonHelper.json(regras.stream().distinct().limit(20).toList()));
        filho.setMemoriaJson(jsonHelper.json(memoria));
        filho.setDataAtualizacao(LocalDateTime.now());
        agentRepository.save(filho);
    }

    private String focoMutacao(int index) {
        return switch (Math.floorMod(index - 1, 6)) {
            case 0 -> "perguntar duvida critica antes de proposta";
            case 1 -> "cortar resposta generica e ser humano";
            case 2 -> "proteger margem, prazo e escopo";
            case 3 -> "diagnosticar risco tecnico cedo";
            case 4 -> "transformar aprendizado em regra curta";
            default -> "balancear venda consultiva com seguranca";
        };
    }

    private List<String> parentKeys(MlLabAgent agent) {
        Map<String, Object> memoria = jsonHelper.lerMemoria(agent.getMemoriaJson());
        List<String> pais = jsonHelper.asStringList(memoria.get("pais"));
        if (!pais.isEmpty()) return pais;
        String text = (agent.getPerfil() + " " + String.join(" ", jsonHelper.asStringList(memoria.get("linhagem")))).toUpperCase(Locale.ROOT);
        java.util.regex.Matcher matcher = Pattern.compile("(P\\d+-[ABC])\\s*\\+\\s*(P\\d+-[ABC])").matcher(text);
        if (matcher.find()) {
            return List.of(matcher.group(1), matcher.group(2));
        }
        return List.of();
    }

    private double ruleQuality(MlLabAgent agent) {
        List<String> regras = jsonHelper.lerLista(agent.getRegrasJson());
        if (regras.isEmpty()) return 20;
        String regra = regras.get(0).toLowerCase(Locale.ROOT);
        double score = Math.min(45, regra.length() / 4.0);
        if (MlLabTextHelper.contem(regra, "sempre", "nunca", "antes")) score += 18;
        if (MlLabTextHelper.contem(regra, "prazo", "valor", "escopo", "dono")) score += 16;
        if (MlLabTextHelper.contem(regra, "senha", "pagamento fora", "credenciais", "risco")) score += 12;
        return Math.min(100, score);
    }

    private double naturalidade(MlLabAgent agent) {
        String resposta = agent.getUltimaResposta() == null ? "" : agent.getUltimaResposta();
        if (resposta.isBlank()) return 20;
        double score = resposta.length() <= 900 ? 60 : 40;
        if (MlLabTextHelper.contem(resposta, "oi", "entendi", "preciso", "confirmar", "antes")) score += 20;
        if (MlLabTextHelper.contem(resposta, "json", "laboratorio", "simulacao")) score -= 20;
        return Math.max(0, Math.min(100, score));
    }

    private double diversityScore(MlLabAgent agent) {
        Map<String, Object> memoria = jsonHelper.lerMemoria(agent.getMemoriaJson());
        int linhagem = jsonHelper.asStringList(memoria.get("linhagem")).size();
        double score = 45 + Math.min(30, linhagem * 5.0);
        if ("filho-mutacao".equals(String.valueOf(memoria.getOrDefault("tipoEvolucao", "")))) score += 15;
        return Math.min(100, score);
    }

    private double reliabilityScore(MlLabAgent agent) {
        String resposta = (agent.getUltimaResposta() == null ? "" : agent.getUltimaResposta()).toLowerCase(Locale.ROOT);
        String regra = String.join(" ", jsonHelper.lerLista(agent.getRegrasJson())).toLowerCase(Locale.ROOT);
        double score = 90;
        if (resposta.contains("parse json falhou") || regra.contains("json valido")) score -= 35;
        if (agent.getRespostasTotal() == 0) score -= 15;
        return Math.max(0, score);
    }

    private String resumoCorpusEvolucao(MlLabRun run, int proximaGeracao, int filhosCriados) {
        String base = run.getCorpusSummary() == null ? "" : run.getCorpusSummary();
        int marker = base.indexOf(" Evolucao automatica ativa:");
        if (marker >= 0) {
            base = base.substring(0, marker).trim();
        }
        return base + " Evolucao automatica ativa: modo " + selectionMode(run)
            + ", elite " + eliteCount(run)
            + ", repescagem " + repechageCount(run)
            + ", protecao filhos " + childProtectionCycles(run)
            + ", workers " + parallelWorkers(run)
            + ", filhos/mutacoes criados no ciclo " + filhosCriados
            + ". Geracao atual G" + proximaGeracao + ".";
    }

    private int normalizarFaixa(Integer value, int fallback, int min, int max) {
        if (value == null) return fallback;
        return Math.max(min, Math.min(max, value));
    }

    private String normalizarSelectionMode(String value) {
        if (value == null || value.isBlank()) return SELECTION_TOP10;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return SELECTION_MULTI_CRITERIA.equals(normalized) ? SELECTION_MULTI_CRITERIA : SELECTION_TOP10;
    }

    private String textoOuPadrao(String valor, String padrao) { return valor == null || valor.isBlank() ? padrao : valor.trim(); }
}
