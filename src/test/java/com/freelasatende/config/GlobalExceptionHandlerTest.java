package com.freelasatende.config;

import com.freelasatende.exception.TransicaoInvalidaException;
import com.freelasatende.model.dto.ErrorResponse;
import com.freelasatende.service.impl.SystemHealthTracker;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GlobalExceptionHandlerTest {

    @Test
    void rotaInexistenteNaoContaComoErroOperacional() {
        SystemHealthTracker healthTracker = new SystemHealthTracker();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(healthTracker);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/dashboard/status");

        ResponseEntity<ErrorResponse> response = handler.handleNotFound(
            new NoResourceFoundException(HttpMethod.GET, "api/dashboard/status"),
            request
        );

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("NOT_FOUND", response.getBody().getCode());
        assertNull(response.getBody().getErrorId());
        assertEquals(0, healthTracker.getTotalErrosHoje());
    }

    @Test
    void transicaoInvalidaEConflitoEIllegalStateGenericoSegueErroInterno() {
        SystemHealthTracker healthTracker = new SystemHealthTracker();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(healthTracker);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ml-lab/runs/1/pausar");

        ResponseEntity<ErrorResponse> transicao = handler.handleTransicaoInvalida(
            new TransicaoInvalidaException("Run ja esta parado."), request);
        ResponseEntity<ErrorResponse> interno = handler.handleIllegalState(
            new IllegalStateException("IA indisponivel"), request);

        assertEquals(HttpStatus.CONFLICT, transicao.getStatusCode());
        assertEquals("TRANSICAO_INVALIDA", transicao.getBody().getCode());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, interno.getStatusCode());
        assertEquals(1, healthTracker.getTotalErrosHoje());
    }
}
