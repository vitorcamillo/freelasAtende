package com.freelasatende.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ControleAcaoResultadoDTO {

    private boolean sucesso;
    private String mensagem;
    private AcaoControleDTO acao;
    private Integer totalProjetosLidos;
    private Integer totalProjetosProcessados;
    private Long totalPendenciasNovas;
    private Long totalPendencias;
    private Long totalBloqueios;
    private Long totalEnvios;
    private Long totalEnviosSimulados;
}
