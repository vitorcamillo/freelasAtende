package com.freelasatende.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Rastreador de saúde do sistema em tempo real.
 * Mantém métricas reais de uptime, erros e status de subsistemas.
 */
@Slf4j
@Service
public class SystemHealthTracker {

    private static final int MAX_ERROS_RECENTES = 50;

    private final Instant startupTime = Instant.now();

    private final AtomicInteger errosScraperHoje = new AtomicInteger(0);
    private final AtomicInteger errosIaHoje = new AtomicInteger(0);
    private final AtomicInteger errosTelegramHoje = new AtomicInteger(0);
    private final AtomicInteger errosOutrosHoje = new AtomicInteger(0);

    private final AtomicReference<String> ultimoErroScraper = new AtomicReference<>(null);
    private final AtomicReference<String> ultimoErroIa = new AtomicReference<>(null);
    private final AtomicReference<String> ultimoErroTelegram = new AtomicReference<>(null);

    private final AtomicReference<Instant> ultimoSucessoScraper = new AtomicReference<>(null);
    private final AtomicReference<Instant> ultimoSucessoIa = new AtomicReference<>(null);
    private final AtomicReference<Instant> ultimoSucessoTelegram = new AtomicReference<>(null);

    private final Deque<ErroRecente> errosRecentes = new ConcurrentLinkedDeque<>();

    /**
     * Registra um erro no subsistema especificado.
     */
    public void registrarErro(String subsistema, String errorId, String mensagem) {
        ErroRecente erro = new ErroRecente(errorId, subsistema, mensagem, LocalDateTime.now());
        errosRecentes.addFirst(erro);
        while (errosRecentes.size() > MAX_ERROS_RECENTES) {
            errosRecentes.pollLast();
        }

        switch (subsistema.toUpperCase()) {
            case "SCRAPER" -> {
                errosScraperHoje.incrementAndGet();
                ultimoErroScraper.set(mensagem);
            }
            case "IA" -> {
                errosIaHoje.incrementAndGet();
                ultimoErroIa.set(mensagem);
            }
            case "TELEGRAM" -> {
                errosTelegramHoje.incrementAndGet();
                ultimoErroTelegram.set(mensagem);
            }
            default -> errosOutrosHoje.incrementAndGet();
        }

        log.warn("[HEALTH] Erro em {}: {} (errorId: {})", subsistema, mensagem, errorId);
    }

    /**
     * Registra sucesso de operação no subsistema.
     */
    public void registrarSucesso(String subsistema) {
        Instant agora = Instant.now();
        switch (subsistema.toUpperCase()) {
            case "SCRAPER" -> ultimoSucessoScraper.set(agora);
            case "IA" -> ultimoSucessoIa.set(agora);
            case "TELEGRAM" -> ultimoSucessoTelegram.set(agora);
        }
    }

    /**
     * Retorna o uptime do sistema em segundos.
     */
    public long getUptimeSeconds() {
        return Duration.between(startupTime, Instant.now()).getSeconds();
    }

    /**
     * Retorna o total de erros do dia.
     */
    public int getTotalErrosHoje() {
        return errosScraperHoje.get() + errosIaHoje.get()
            + errosTelegramHoje.get() + errosOutrosHoje.get();
    }

    /**
     * Retorna o status calculado de um subsistema.
     * ONLINE: último sucesso nos últimos 5 minutos
     * DEGRADED: último sucesso entre 5 e 15 minutos
     * OFFLINE: sem sucesso ou >15 minutos
     */
    public String getStatusSubsistema(String subsistema) {
        AtomicReference<Instant> ultimoSucesso = switch (subsistema.toUpperCase()) {
            case "SCRAPER" -> ultimoSucessoScraper;
            case "IA" -> ultimoSucessoIa;
            case "TELEGRAM" -> ultimoSucessoTelegram;
            default -> new AtomicReference<>(null);
        };

        Instant ultimo = ultimoSucesso.get();
        if (ultimo == null) {
            return "UNKNOWN";
        }

        long segundosSemSucesso = Duration.between(ultimo, Instant.now()).getSeconds();
        if (segundosSemSucesso < 300) {
            return "ONLINE";
        } else if (segundosSemSucesso < 900) {
            return "DEGRADED";
        } else {
            return "OFFLINE";
        }
    }

    /**
     * Retorna status completo de todos os subsistemas.
     */
    public Map<String, Object> getHealthCompleto() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("uptimeSeconds", getUptimeSeconds());
        health.put("uptimeFormatado", formatarUptime(getUptimeSeconds()));
        health.put("totalErrosHoje", getTotalErrosHoje());

        Map<String, Object> subsistemas = new LinkedHashMap<>();
        for (String sub : List.of("SCRAPER", "IA", "TELEGRAM")) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("status", getStatusSubsistema(sub));
            info.put("errosHoje", getErrosHojePorSubsistema(sub));
            info.put("ultimoErro", getUltimoErro(sub));
            subsistemas.put(sub.toLowerCase(), info);
        }
        health.put("subsistemas", subsistemas);

        return health;
    }

    /**
     * Retorna lista de erros recentes para o dashboard.
     */
    public List<Map<String, String>> getErrosRecentes(int limite) {
        return errosRecentes.stream()
            .limit(limite)
            .map(e -> {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("errorId", e.errorId());
                m.put("subsistema", e.subsistema());
                m.put("mensagem", e.mensagem());
                m.put("timestamp", e.timestamp().toString());
                return m;
            })
            .toList();
    }

    private int getErrosHojePorSubsistema(String sub) {
        return switch (sub.toUpperCase()) {
            case "SCRAPER" -> errosScraperHoje.get();
            case "IA" -> errosIaHoje.get();
            case "TELEGRAM" -> errosTelegramHoje.get();
            default -> errosOutrosHoje.get();
        };
    }

    private String getUltimoErro(String sub) {
        return switch (sub.toUpperCase()) {
            case "SCRAPER" -> ultimoErroScraper.get();
            case "IA" -> ultimoErroIa.get();
            case "TELEGRAM" -> ultimoErroTelegram.get();
            default -> null;
        };
    }

    private String formatarUptime(long totalSegundos) {
        long dias = totalSegundos / 86400;
        long horas = (totalSegundos % 86400) / 3600;
        long minutos = (totalSegundos % 3600) / 60;
        if (dias > 0) {
            return String.format("%dd %dh %dm", dias, horas, minutos);
        }
        if (horas > 0) {
            return String.format("%dh %dm", horas, minutos);
        }
        return String.format("%dm", minutos);
    }

    /**
     * Reseta contadores diários (chamado pelo scheduler à meia-noite).
     */
    public void resetarContadoresDiarios() {
        errosScraperHoje.set(0);
        errosIaHoje.set(0);
        errosTelegramHoje.set(0);
        errosOutrosHoje.set(0);
        log.info("[HEALTH] Contadores diários resetados");
    }

    private record ErroRecente(String errorId, String subsistema, String mensagem, LocalDateTime timestamp) {}
}
