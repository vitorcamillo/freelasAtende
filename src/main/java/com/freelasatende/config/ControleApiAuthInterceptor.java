package com.freelasatende.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

@Component
public class ControleApiAuthInterceptor implements HandlerInterceptor {

    private static final Set<String> LOCAL_HOSTS = Set.of(
        "127.0.0.1",
        "0:0:0:0:0:0:0:1",
        "::1",
        "localhost"
    );

    /**
     * Unica rota que aceita token por query param: o EventSource do painel
     * (SSE) nao envia headers, entao X-Control-Token/Bearer nao chegam nela.
     */
    private static final String ROTA_SSE_LOGS = "/api/dashboard/logs/stream";

    private final String apiToken;

    public ControleApiAuthInterceptor(@Value("${controle.api-token:}") String apiToken) {
        this.apiToken = apiToken != null ? apiToken.trim() : "";
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (isLocalRequest(request) || tokenValido(request)) {
            return true;
        }

        response.sendError(HttpServletResponse.SC_FORBIDDEN, "Controle operacional requer origem local ou token valido");
        return false;
    }

    private boolean tokenValido(HttpServletRequest request) {
        if (apiToken.isBlank()) {
            return false;
        }
        String headerToken = request.getHeader("X-Control-Token");
        if (tokenConfere(headerToken)) {
            return true;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")
            && tokenConfere(authorization.substring("Bearer ".length()).trim())) {
            return true;
        }
        // Query param ?token= vale SOMENTE na rota SSE de logs; comparacao em
        // tempo constante pelo mesmo tokenConfere dos headers.
        return ROTA_SSE_LOGS.equals(request.getRequestURI())
            && tokenConfere(request.getParameter("token"));
    }

    private boolean tokenConfere(String valor) {
        return valor != null
            && MessageDigest.isEqual(
                apiToken.getBytes(StandardCharsets.UTF_8),
                valor.getBytes(StandardCharsets.UTF_8)
            );
    }

    /**
     * A origem local nasce SEMPRE do remoteAddr (par TCP, que o cliente nao
     * controla). X-Forwarded-For e X-Real-IP sao controlados pelo cliente e
     * nunca concedem origem local; so podem negar, quando revelam que o
     * cliente real e remoto (ex.: proxy que acrescenta o IP real a cadeia).
     */
    private boolean isLocalRequest(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        if (ip == null || !LOCAL_HOSTS.contains(ip.trim())) {
            return false;
        }
        return !revelaOrigemRemota(request.getHeader("X-Forwarded-For"))
            && !revelaOrigemRemota(request.getHeader("X-Real-IP"));
    }

    private boolean revelaOrigemRemota(String cabecalho) {
        if (cabecalho == null || cabecalho.isBlank()) {
            return false;
        }
        for (String parte : cabecalho.split(",")) {
            String ip = parte.trim();
            if (!ip.isEmpty() && !LOCAL_HOSTS.contains(ip)) {
                return true;
            }
        }
        return false;
    }
}
