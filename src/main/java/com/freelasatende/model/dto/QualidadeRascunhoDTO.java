package com.freelasatende.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualidadeRascunhoDTO {

    private Integer scoreNaturalidade;
    private Integer scoreClareza;
    private Integer scoreRisco;
    private Integer scoreDuplicidade;
    private Integer scoreConfianca;
    private String nivelConfianca;
    private String decisaoRecomendada;
    private String comentario;
    private List<String> sinais;

    public boolean bloqueante() {
        return "BLOQUEAR".equalsIgnoreCase(decisaoRecomendada)
            || "BLOQUEADA".equalsIgnoreCase(nivelConfianca);
    }
}
