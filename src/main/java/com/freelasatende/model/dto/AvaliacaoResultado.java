package com.freelasatende.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resultado da avaliação da IA sobre um projeto.
 * Contém o veredito (capaz ou não) e o motivo da recusa, se aplicável.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvaliacaoResultado {

    /** true se somos capazes de atender o projeto. */
    private boolean capaz;

    /** Motivo da recusa (null se capaz). */
    private String motivoRecusa;

    /** Motivo curto de interesse quando a avaliação aprova o projeto. */
    private String motivoInteresse;
}
