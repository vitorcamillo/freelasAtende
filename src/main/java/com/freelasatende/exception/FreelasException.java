package com.freelasatende.exception;

/**
 * Exceção base do sistema FreelasAtende.
 * Todas as exceções customizadas herdam desta classe.
 */
public class FreelasException extends RuntimeException {

    private final String code;

    public FreelasException(String code, String message) {
        super(message);
        this.code = code;
    }

    public FreelasException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
