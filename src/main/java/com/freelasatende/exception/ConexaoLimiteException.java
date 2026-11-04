package com.freelasatende.exception;

/**
 * Exceção quando o limite de conexões diárias é atingido.
 */
public class ConexaoLimiteException extends FreelasException {

    public ConexaoLimiteException(String message) {
        super("CONEXAO_LIMITE", message);
    }
}
