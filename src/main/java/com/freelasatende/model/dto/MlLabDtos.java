package com.freelasatende.model.dto;

import com.freelasatende.model.enums.MlLabCandidateStatus;
import com.freelasatende.model.enums.MlLabRunStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class MlLabDtos {

    private MlLabDtos() {}

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRunRequest {
        private Integer totalCiclos;
        private String objetivo;
        private Boolean usarIaReal;
        private Integer maxTokensPorChamada;
        private String selectionMode;
        private Integer parallelWorkers;
        private Integer eliteCount;
        private Integer repechageCount;
        private Integer childProtectionCycles;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatRequest {
        private String mensagem;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActionResult {
        private boolean sucesso;
        private String mensagem;
        private RunOverview overview;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RunOverview {
        private Long id;
        private MlLabRunStatus status;
        private String objetivo;
        private int totalCiclos;
        private int ciclosConcluidos;
        private int totalAgentes;
        private String bestAgentKey;
        private double bestScore;
        private boolean externalWritesBlocked;
        private boolean realIaEnabled;
        private String iaProviderName;
        private String iaModelName;
        private int maxTokensPerCall;
        private int iaCallsTotal;
        private int deterministicFallbackTotal;
        private String selectionMode;
        private int parallelWorkers;
        private int eliteCount;
        private int repechageCount;
        private int childProtectionCycles;
        private int geracaoAtual;
        private int evolucoesTotal;
        private String modoEvolucao;
        private String artifactDir;
        private String corpusSummary;
        private String erro;
        private LocalDateTime dataCriacao;
        private LocalDateTime dataAtualizacao;
        private List<AgentView> agents;
        private List<CycleView> recentCycles;
        private List<RuleCandidateView> candidates;
        private Map<String, Object> safety;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentView {
        private Long id;
        private String agentKey;
        private String perfil;
        private String variante;
        private String estrategia;
        private double scoreTotal;
        private int respostasTotal;
        private int geracao;
        private double fitnessScore;
        private String selectionBand;
        private double parentDelta;
        private int protectedCyclesLeft;
        private String selectionReason;
        private String linhagem;
        private String identidadeFinal;
        private String ultimaResposta;
        private List<String> regras;
        private List<String> duvidas;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CycleView {
        private int numero;
        private String cenario;
        private String bestAgentKey;
        private double bestScore;
        private String resumo;
        private LocalDateTime dataCriacao;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuleCandidateView {
        private Long id;
        private String agentKey;
        private int cycleNumber;
        private String regra;
        private String origem;
        private double score;
        private MlLabCandidateStatus status;
        private LocalDateTime dataCriacao;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatResponse {
        private Long runId;
        private String agentKey;
        private String perfil;
        private String variante;
        private String resposta;
        private List<String> regras;
        private List<String> duvidas;
        private double scoreTotal;
    }
}
