package com.freelasatende.model.dto;

import com.freelasatende.model.entity.MensagemClienteRegistro;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ControleOverviewDTO {

    private boolean podeOperar;
    private String motivoOperacao;
    private boolean escritaRealHabilitada;
    private boolean simulacaoEnvioHabilitada;
    private boolean sessaoValida;
    private String statusConexoes;
    private Map<String, Object> health;
    private int errosHoje;
    private long totalPendencias;
    private long totalBloqueios;
    private long totalEnvios;
    private long totalEnviosSimulados;
    private List<AcaoControleDTO> pendencias;
    private List<MensagemClienteRegistro> bloqueiosRecentes;
    private List<MensagemClienteRegistro> enviosRecentes;
    private List<MensagemClienteRegistro> simulacoesRecentes;
    private List<Map<String, String>> errosRecentes;
    private Map<String, Object> aprendizado;
    private Map<String, Object> qualidadeOperacional;
    private Map<String, Object> auditoriaInboxResumo;
    private EnsinoFocoDTO focoEnsino;
    private LocalDateTime data;
}
