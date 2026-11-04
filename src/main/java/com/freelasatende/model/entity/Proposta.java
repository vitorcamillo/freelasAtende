package com.freelasatende.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entidade que representa uma proposta feita a um projeto.
 */
@Entity
@Table(name = "propostas")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Proposta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "projeto_id", nullable = false)
    private Long projetoId;

    @Column(name = "valor_proposto", precision = 10, scale = 2)
    private BigDecimal valorProposto;

    @Column(name = "valor_minimo", precision = 10, scale = 2)
    private BigDecimal valorMinimo;

    @Column(name = "media_mercado", precision = 10, scale = 2)
    private BigDecimal mediaMercado;

    @Column(columnDefinition = "TEXT")
    private String mensagem;

    @Column(name = "data_proposta")
    private LocalDateTime dataProposta;

    private String status; // ENVIADA, ACEITA, RECUSADA, CANCELADA

    @Column(name = "foi_minha")
    private Boolean foiMinha;
}
