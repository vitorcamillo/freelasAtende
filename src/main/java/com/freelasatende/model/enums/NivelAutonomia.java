package com.freelasatende.model.enums;

/**
 * Estagio operacional da IA para uma acao pendente.
 * A autonomia cresce por regras confirmadas, nao por chute.
 */
public enum NivelAutonomia {
    OBSERVA,
    RASCUNHA,
    PERGUNTA_DONO,
    PERGUNTA_CLIENTE,
    AGE_COM_GUARDIAO,
    AUTONOMA_CATEGORIA
}
