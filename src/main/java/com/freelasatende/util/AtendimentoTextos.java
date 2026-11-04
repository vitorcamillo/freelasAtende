package com.freelasatende.util;

public final class AtendimentoTextos {

    public static final String ABORDAGEM_INICIAL = "Ja foi atendido?";

    private AtendimentoTextos() {
    }

    public static boolean isAbordagemInicial(String texto) {
        return ABORDAGEM_INICIAL.equals(texto);
    }

    public static String abordagemInicial() {
        return ABORDAGEM_INICIAL;
    }
}
