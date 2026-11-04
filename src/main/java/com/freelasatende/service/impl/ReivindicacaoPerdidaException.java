package com.freelasatende.service.impl;

/**
 * Outra instancia tomou a reivindicacao do projeto (prazo vencido): a gravacao fenced foi
 * recusada e esta execucao deve parar sem nenhum efeito.
 */
class ReivindicacaoPerdidaException extends RuntimeException {

    ReivindicacaoPerdidaException(Long projetoId) {
        super("Reivindicacao do projeto " + projetoId + " perdida para outra execucao");
    }

    /** True se a excecao ou alguma causa e esta (ela chega embrulhada pela avaliacao). */
    static boolean naCadeia(Throwable erro) {
        for (Throwable atual = erro; atual != null; atual = atual.getCause() == atual ? null : atual.getCause()) {
            if (atual instanceof ReivindicacaoPerdidaException) {
                return true;
            }
        }
        return false;
    }
}
