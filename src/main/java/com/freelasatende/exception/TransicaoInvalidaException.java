package com.freelasatende.exception;

/**
 * Transicao de estado recusada por regra de negocio (ex.: pausar run do ML Lab que nunca
 * iniciou). E conflito com o estado atual, nao falha interna: o handler devolve 409.
 * Estende IllegalStateException para manter o contrato de quem ja capturava esse tipo.
 */
public class TransicaoInvalidaException extends IllegalStateException {

    public TransicaoInvalidaException(String message) {
        super(message);
    }
}
