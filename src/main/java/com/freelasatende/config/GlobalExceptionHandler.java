package com.freelasatende.config;

import com.freelasatende.controller.DashboardController;
import com.freelasatende.exception.FreelasException;
import com.freelasatende.exception.TransicaoInvalidaException;
import com.freelasatende.model.dto.ErrorResponse;
import com.freelasatende.service.impl.SystemHealthTracker;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Handler global de exceções para todas as APIs.
 * Garante respostas padronizadas com errorId rastreável.
 */
@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final SystemHealthTracker healthTracker;

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Void> handleAsyncTimeout(
            AsyncRequestTimeoutException ex, HttpServletRequest request) {
        log.debug("Timeout assíncrono encerrado sem alerta operacional em {}", request.getRequestURI());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ResponseEntity<Void> handleAsyncRequestNotUsable(
            AsyncRequestNotUsableException ex, HttpServletRequest request) {
        log.debug("Cliente encerrou conexao antes da resposta em {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(
            Exception ex, HttpServletRequest request) {
        log.debug("Recurso não encontrado em {}: {}", request.getRequestURI(), ex.getMessage());

        ErrorResponse response = ErrorResponse.builder()
            .errorId(null)
            .code("NOT_FOUND")
            .message("Recurso não encontrado")
            .timestamp(LocalDateTime.now())
            .path(request.getRequestURI())
            .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    /**
     * Corpo JSON malformado e parametro de path/tipo invalido sao erro do
     * CLIENTE: devolvem 400 com mensagem generica, sem detalhe interno nem
     * stack trace (ACHADO-FA-API-01-3 e ACHADO-FA-API-01-2).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleCorpoMalformado(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.debug("Corpo da requisicao invalido em {}: {}", request.getRequestURI(), ex.getMessage());

        ErrorResponse response = ErrorResponse.builder()
            .errorId(null)
            .code("CORPO_INVALIDO")
            .message("Corpo da requisição inválido")
            .timestamp(LocalDateTime.now())
            .path(request.getRequestURI())
            .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTipoParametroInvalido(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        log.debug("Parametro com tipo invalido em {}: {}", request.getRequestURI(), ex.getMessage());

        ErrorResponse response = ErrorResponse.builder()
            .errorId(null)
            .code("PARAMETRO_INVALIDO")
            .message("Parâmetro da requisição inválido")
            .timestamp(LocalDateTime.now())
            .path(request.getRequestURI())
            .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(FreelasException.class)
    public ResponseEntity<ErrorResponse> handleFreelasException(
            FreelasException ex, HttpServletRequest request) {
        String errorId = UUID.randomUUID().toString().substring(0, 8);

        log.error("[{}] {} - {}: {}", errorId, ex.getCode(), request.getRequestURI(), ex.getMessage());

        String subsistema = inferirSubsistema(ex.getCode());
        healthTracker.registrarErro(subsistema, errorId, ex.getMessage());

        DashboardController.enviarLog("ERROR",
            String.format("[%s] %s: %s", errorId, ex.getCode(), ex.getMessage()));

        ErrorResponse response = ErrorResponse.builder()
            .errorId(errorId)
            .code(ex.getCode())
            .message(ex.getMessage())
            .timestamp(LocalDateTime.now())
            .path(request.getRequestURI())
            .build();

        return ResponseEntity.status(mapStatus(ex.getCode())).body(response);
    }

    /**
     * Entrada de negocio invalida (recurso inexistente, mensagem vazia) e erro do
     * CLIENTE: 400 com a mensagem de negocio, sem stack trace
     * (ACHADO-FA-UNIT-01-*).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {
        log.debug("Argumento invalido em {}: {}", request.getRequestURI(), ex.getMessage());

        ErrorResponse response = ErrorResponse.builder()
            .errorId(null)
            .code("REQUISICAO_INVALIDA")
            .message(ex.getMessage())
            .timestamp(LocalDateTime.now())
            .path(request.getRequestURI())
            .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(
            IllegalStateException ex, HttpServletRequest request) {
        String errorId = UUID.randomUUID().toString().substring(0, 8);
        log.error("[{}] IllegalState em {}: {}", errorId, request.getRequestURI(), ex.getMessage());

        healthTracker.registrarErro("SISTEMA", errorId, ex.getMessage());

        ErrorResponse response = ErrorResponse.builder()
            .errorId(errorId)
            .code("ILLEGAL_STATE")
            .message(ex.getMessage())
            .timestamp(LocalDateTime.now())
            .path(request.getRequestURI())
            .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /** Transicao recusada pelo estado atual (ML Lab): conflito do cliente, 409 sem stack trace. */
    @ExceptionHandler(TransicaoInvalidaException.class)
    public ResponseEntity<ErrorResponse> handleTransicaoInvalida(
            TransicaoInvalidaException ex, HttpServletRequest request) {
        log.debug("Transicao recusada em {}: {}", request.getRequestURI(), ex.getMessage());

        ErrorResponse response = ErrorResponse.builder()
            .errorId(null)
            .code("TRANSICAO_INVALIDA")
            .message(ex.getMessage())
            .timestamp(LocalDateTime.now())
            .path(request.getRequestURI())
            .build();

        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(
            Exception ex, HttpServletRequest request) {
        if (isClientAbort(ex)) {
            log.debug("Cliente encerrou conexao antes da resposta em {}: {}", request.getRequestURI(), ex.getMessage());
            return ResponseEntity.noContent().build();
        }

        String errorId = UUID.randomUUID().toString().substring(0, 8);
        log.error("[{}] Erro inesperado em {}: {}", errorId, request.getRequestURI(), ex.getMessage(), ex);

        healthTracker.registrarErro("SISTEMA", errorId, ex.getMessage());

        DashboardController.enviarLog("ERROR",
            String.format("[%s] Erro inesperado: %s", errorId, ex.getMessage()));

        ErrorResponse response = ErrorResponse.builder()
            .errorId(errorId)
            .code("INTERNAL_ERROR")
            .message("Erro interno do servidor")
            .timestamp(LocalDateTime.now())
            .path(request.getRequestURI())
            .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    private String inferirSubsistema(String code) {
        if (code == null) return "SISTEMA";
        return switch (code) {
            case "SCRAPER_ERROR" -> "SCRAPER";
            case "IA_ERROR" -> "IA";
            case "TELEGRAM_ERROR" -> "TELEGRAM";
            default -> "SISTEMA";
        };
    }

    private HttpStatus mapStatus(String code) {
        return switch (code) {
            case "CONEXAO_LIMITE" -> HttpStatus.TOO_MANY_REQUESTS;
            case "SCRAPER_ERROR" -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private boolean isClientAbort(Throwable ex) {
        Throwable atual = ex;
        while (atual != null) {
            String nome = atual.getClass().getName();
            String mensagem = atual.getMessage() != null ? atual.getMessage().toLowerCase() : "";
            if (nome.contains("ClientAbortException")
                || mensagem.contains("pipe quebrado")
                || mensagem.contains("broken pipe")
                || mensagem.contains("connection reset by peer")) {
                return true;
            }
            atual = atual.getCause();
        }
        return false;
    }
}
