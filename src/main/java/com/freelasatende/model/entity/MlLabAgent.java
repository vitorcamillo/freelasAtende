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
@Table(name = "ml_lab_agents")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MlLabAgent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "agent_key", nullable = false)
    private String agentKey;

    @Column(name = "perfil", nullable = false)
    private String perfil;

    @Column(name = "variante", nullable = false)
    private String variante;

    @Column(name = "estrategia", length = 700)
    private String estrategia;

    @Column(name = "score_total")
    private double scoreTotal;

    @Column(name = "respostas_total")
    private int respostasTotal;

    @Column(name = "memoria_json", columnDefinition = "TEXT")
    private String memoriaJson;

    @Column(name = "regras_json", columnDefinition = "TEXT")
    private String regrasJson;

    @Column(name = "ultima_resposta", columnDefinition = "TEXT")
    private String ultimaResposta;

    @Column(name = "data_atualizacao")
    private LocalDateTime dataAtualizacao;
}
