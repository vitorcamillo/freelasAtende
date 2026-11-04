package com.freelasatende.model.enums;

/**
 * Modos de operação do sistema de atendimento.
 * Define o nível de autonomia do sistema em relação às decisões.
 */
public enum ModoSistema {

    /**
     * Sistema liberado para agir com autonomia dentro das travas de escrita.
     * Pode enviar abordagem inicial curta quando real.write.enabled=true.
     */
    LIVRE,

    /**
     * Sistema opera com revisão quando houver dúvida.
     * Pode enviar abordagem inicial curta quando real.write.enabled=true.
     */
    REGULAR,

    /**
     * Sistema pergunta tudo ao usuário para fins de aprendizado.
     * Trava envio direto e mantém mensagens pendentes para revisão/ensino.
     */
    ENSINAMENTO
}
