package com.freelasatende.service.impl;

import java.time.LocalDateTime;

/**
 * Reserva (reivindicacao) de um projeto pela thread atual. O token (data de processamento
 * gravada no banco pela reivindicacao) vive SO aqui, nunca na entidade: com Open
 * EntityManager in View a entidade pode estar gerenciada e um flush regravaria o token por
 * cima do de outra instancia. Enquanto aberta, toda gravacao de projeto, acao ou interacao
 * deste projeto passa pelo fencing de GuardaDePosseAspect, e cada envio externo confirma a
 * posse antes (AutoAtendimentoActionHooks#confirmarDonoAntesDoEnvio).
 */
final class ReservaDeProjeto {

    private record Reserva(Long projetoId, LocalDateTime token) {
    }

    private static final ThreadLocal<Reserva> ATUAL = new ThreadLocal<>();

    private ReservaDeProjeto() {
    }

    static void abrir(Long projetoId, LocalDateTime token) {
        ATUAL.set(new Reserva(projetoId, token));
    }

    static void atualizarToken(LocalDateTime novo) {
        Reserva reserva = ATUAL.get();
        if (reserva != null) {
            ATUAL.set(new Reserva(reserva.projetoId(), novo));
        }
    }

    static void fechar() {
        ATUAL.remove();
    }

    /** Token da reserva deste projeto nesta thread, ou null. */
    static LocalDateTime tokenAtual(Long projetoId) {
        Reserva reserva = ATUAL.get();
        return reserva != null && projetoId != null && reserva.projetoId().equals(projetoId) ? reserva.token() : null;
    }
}
