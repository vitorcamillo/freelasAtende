package com.freelasatende.exception;

/**
 * Exceção para falhas na comunicação com a IA (timeout, resposta inválida, etc).
 */
public class IaException extends FreelasException {

    public IaException(String message) {
        super("IA_ERROR", message);
    }

    public IaException(String message, Throwable cause) {
        super("IA_ERROR", message, cause);
    }
}
