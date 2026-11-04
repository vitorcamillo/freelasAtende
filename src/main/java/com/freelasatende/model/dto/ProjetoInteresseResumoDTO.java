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
public class ProjetoInteresseResumoDTO {

    private String motivoInteresse;
    private String nivelConviccao;
    private List<String> sinaisPositivos;
    private List<String> pontosAtencao;
    private String atitudeSugerida;
}
