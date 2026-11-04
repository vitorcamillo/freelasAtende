package com.freelasatende.service.impl;

/**
 * O limite diario LOCAL de conexoes (contador da Configuracao) recusou a reserva antes do
 * envio. Diferente do limite externo do marketplace (LIMITE_CONEXOES do Workana): renova
 * no proximo dia, entao o projeto/acao deve aguardar e ser tentado de novo.
 */
class LimiteDiarioLocalException extends RuntimeException {

    LimiteDiarioLocalException(String message) {
        super(message);
    }

    /** True se a excecao ou alguma causa (ela chega embrulhada pela execucao da acao) e esta. */
    static boolean naCadeia(Throwable erro) {
        for (Throwable atual = erro; atual != null; atual = atual.getCause() == atual ? null : atual.getCause()) {
            if (atual instanceof LimiteDiarioLocalException) {
                return true;
            }
        }
        return false;
    }
}
