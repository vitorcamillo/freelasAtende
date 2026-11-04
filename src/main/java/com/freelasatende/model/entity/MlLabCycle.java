package com.freelasatende.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "ml_lab_cycles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MlLabCycle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "numero", nullable = false)
    private int numero;

    @Column(name = "cenario", columnDefinition = "TEXT")
    private String cenario;

    @Column(name = "best_agent_key")
    private String bestAgentKey;

    @Column(name = "best_score")
    private double bestScore;

    @Column(name = "resumo", columnDefinition = "TEXT")
    private String resumo;

    @Column(name = "data_criacao", nullable = false)
    private LocalDateTime dataCriacao;
}
