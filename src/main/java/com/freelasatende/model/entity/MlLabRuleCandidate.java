package com.freelasatende.model.entity;

import com.freelasatende.model.enums.MlLabCandidateStatus;
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
@Table(name = "ml_lab_rule_candidates")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MlLabRuleCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "agent_key", nullable = false)
    private String agentKey;

    @Column(name = "cycle_number", nullable = false)
    private int cycleNumber;

    @Column(name = "regra", columnDefinition = "TEXT", nullable = false)
    private String regra;

    @Column(name = "origem", columnDefinition = "TEXT")
    private String origem;

    @Column(name = "score")
    private double score;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MlLabCandidateStatus status;

    @Column(name = "data_criacao", nullable = false)
    private LocalDateTime dataCriacao;

    @Column(name = "data_decisao")
    private LocalDateTime dataDecisao;
}
