package com.freelasatende.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * DTO para estatisticas do dashboard.
 * Todos os campos são preenchidos com dados reais do sistema.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatsDTO {

    private String modoAtual;
    private int conexoesUsadas;
    private int conexoesLimite;
    private long projetosProcessadosHoje;
    private long projetosAprovadosHoje;
    private long projetosRecusadosHoje;
    private String status99Freelas;
    private boolean telegramConectado;
    private LocalDateTime ultimoPolling;

    // Campos de saúde real do sistema
    private long uptimeSeconds;
    private String uptimeFormatado;
    private int errosHoje;
    private String statusScraper;
    private String statusIa;
    private String statusTelegram;
}