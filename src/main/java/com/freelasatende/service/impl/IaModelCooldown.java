package com.freelasatende.service.impl;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado de cooldown por modelo para a cadeia de fallback de IA; thread-safe (scheduler concorrente).
 */
@Slf4j
final class IaModelCooldown {

    private static final int FALHAS_PARA_COOLDOWN = 2;
    private static final Duration COOLDOWN_MODELO = Duration.ofMinutes(5);

    // O scheduler roda concorrente; estado de cooldown por modelo precisa ser
    // thread-safe. O cooldown evita pagar a latencia de chamadas que ja se
    // sabe que vao falhar (producao: openrouter/free -> 422 repetido,
    // glm-5.3-flash -> 429 por minutos seguidos).
    private final ConcurrentHashMap<String, Integer> falhasConsecutivas = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> cooldownAte = new ConcurrentHashMap<>();

    boolean modeloEmCooldown(String modelo) {
        Instant ate = cooldownAte.get(modelo);
        return ate != null && Instant.now().isBefore(ate);
    }

    /**
     * Falha retryable consecutiva entra no contador do modelo; ao atingir
     * FALHAS_PARA_COOLDOWN, o modelo entra em cooldown e o contador zera:
     * expirado o cooldown, sao precisas FALHAS_PARA_COOLDOWN falhas NOVAS
     * para reentrar (as falhas antigas ja nao sao "consecutivas").
     */
    void registrarFalhaRetryable(String modelo) {
        int falhas = falhasConsecutivas.merge(modelo, 1, Integer::sum);
        if (falhas < FALHAS_PARA_COOLDOWN) {
            return;
        }
        cooldownAte.put(modelo, Instant.now().plus(COOLDOWN_MODELO));
        falhasConsecutivas.remove(modelo);
        log.info("Modelo {} em cooldown de {}min apos {} falhas consecutivas",
            modelo, COOLDOWN_MODELO.toMinutes(), falhas);
    }

    /** Sucesso limpa contador e cooldown do modelo. */
    void registrarSucesso(String modelo) {
        falhasConsecutivas.remove(modelo);
        cooldownAte.remove(modelo);
    }
}
