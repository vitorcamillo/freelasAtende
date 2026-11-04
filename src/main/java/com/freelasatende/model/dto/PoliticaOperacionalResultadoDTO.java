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
public class PoliticaOperacionalResultadoDTO {

    private boolean permitido;
    private String status;
    private String motivo;
    private List<RegraOperacionalDTO> checklist;
    private List<String> sinais;
}
