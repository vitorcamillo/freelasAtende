package com.freelasatende.model.entity;

import com.freelasatende.model.enums.MlLabRunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "ml_lab_runs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MlLabRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MlLabRunStatus status;

    @Column(name = "objetivo", length = 700)
    private String objetivo;

    @Column(name = "total_ciclos", nullable = false)
    private int totalCiclos;

    @Column(name = "ciclos_concluidos", nullable = false)
    private int ciclosConcluidos;

    @Column(name = "total_agentes", nullable = false)
    private int totalAgentes;

    @Column(name = "best_agent_key")
    private String bestAgentKey;

    @Column(name = "best_score")
    private double bestScore;

    @Column(name = "external_writes_blocked", nullable = false)
    private boolean externalWritesBlocked;

    @Column(name = "real_ia_enabled", nullable = false)
    private boolean realIaEnabled;

    @Column(name = "ia_provider_name")
    private String iaProviderName;

    @Column(name = "ia_model_name")
    private String iaModelName;

    @Column(name = "max_tokens_per_call")
    private int maxTokensPerCall;

    @Column(name = "ia_calls_total")
    private int iaCallsTotal;

    @Column(name = "deterministic_fallback_total")
    private int deterministicFallbackTotal;

    @Column(name = "selection_mode")
    private String selectionMode;

    @Column(name = "parallel_workers")
    private Integer parallelWorkers;

    @Column(name = "elite_count")
    private Integer eliteCount;

    @Column(name = "repechage_count")
    private Integer repechageCount;

    @Column(name = "child_protection_cycles")
    private Integer childProtectionCycles;

    @Column(name = "artifact_dir")
    private String artifactDir;

    @Column(name = "corpus_summary", columnDefinition = "TEXT")
    private String corpusSummary;

    @Column(name = "erro", columnDefinition = "TEXT")
    private String erro;

    @Column(name = "data_criacao", nullable = false)
    private LocalDateTime dataCriacao;

    @Column(name = "data_atualizacao")
    private LocalDateTime dataAtualizacao;
}
