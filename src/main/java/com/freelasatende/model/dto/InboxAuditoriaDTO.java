package com.freelasatende.model.dto;

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
public class InboxAuditoriaDTO {

    private Long auditoriaId;
    private boolean persistida;
    private boolean leituraSemMarcarVisualizada;
    private boolean escritaRealHabilitada;
    private boolean simulacaoEnvioHabilitada;
    private boolean telegramSimulado;
    private boolean iaRealHabilitada;
    private boolean iaPronta;
    private String motivoIa;
    private int totalConversas;
    private int totalMensagens;
    private int totalUltimaCliente;
    private int totalUltimaMinha;
    private int totalSemTextoUtil;
    private int totalAcionaveis;
    private int totalPendenciasExistentes;
    private int totalIgnoradas;
    private Map<String, Object> painelCenarios;
    private String relatorioHtml;
    private String relatorioJson;
    private List<InboxConversaAuditoriaDTO> conversas;
    private LocalDateTime data;
}
