package com.freelasatende.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Resposta de erro padronizada para todas as APIs do sistema.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    /** UUID único para rastreamento do erro nos logs. */
    private String errorId;

    /** Código do erro (ex: SCRAPER_ERROR, IA_ERROR). */
    private String code;

    /** Mensagem amigável para exibição. */
    private String message;

    /** Detalhe técnico (preenchido apenas em ambiente de desenvolvimento). */
    private String detail;

    /** Timestamp do erro. */
    private LocalDateTime timestamp;

    /** URI da requisição que falhou. */
    private String path;
}
