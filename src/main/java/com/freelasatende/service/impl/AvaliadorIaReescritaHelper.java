package com.freelasatende.service.impl;

import java.text.Normalizer;

final class AvaliadorIaReescritaHelper {

    private static final double SIMILARIDADE_MAXIMA_REESCRITA = 0.82d;

    private AvaliadorIaReescritaHelper() {
    }

    static void validarReescrita(String rascunhoAtual, String reescrita, String orientacaoUsuario) {
        String original = normalizar(rascunhoAtual);
        String nova = normalizar(reescrita);

        if (orientacaoTomRobotico(orientacaoUsuario)) {
            exigirTermoPreservado(original, nova, "prazo", "prazo");
            exigirTermoPreservado(original, nova, "valor", "valor");
            exigirTermoPreservado(original, nova, "consigo", "consigo", "posso");
        }

        if (!original.contains("anuncio") && nova.contains("anuncio")) {
            throw new IllegalStateException("IA mudou a intenção da reescrita e passou a falar do anuncio.");
        }
        if (nova.contains("deixar o anuncio assim") || nova.contains("texto do anuncio")) {
            throw new IllegalStateException("IA mudou a intenção da reescrita e sugeriu texto de anuncio.");
        }
        if (similaridade(original, nova) > SIMILARIDADE_MAXIMA_REESCRITA) {
            throw new IllegalStateException("IA devolveu reescrita quase igual ao rascunho atual.");
        }
        if (fechamentoPrecoceDeEscopo(nova)) {
            throw new IllegalStateException("IA encerrou a qualificacao cedo demais em vez de extrair mais contexto.");
        }
    }

    private static boolean orientacaoTomRobotico(String orientacaoUsuario) {
        String orientacao = normalizar(orientacaoUsuario);
        return orientacao.contains("robotico") || orientacao.contains("tom mais humano");
    }

    private static void exigirTermoPreservado(String original, String nova, String termoOriginal, String... alternativas) {
        if (!original.contains(termoOriginal)) {
            return;
        }
        for (String alternativa : alternativas) {
            if (nova.contains(alternativa)) {
                return;
            }
        }
        throw new IllegalStateException("IA mudou a intenção da reescrita e removeu termo essencial: " + termoOriginal);
    }

    private static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        String semAcento = Normalizer.normalize(texto, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");
        return semAcento.toLowerCase();
    }

    private static boolean fechamentoPrecoceDeEscopo(String texto) {
        return texto.contains("ja da para fechar")
            || texto.contains("ja da pra fechar")
            || texto.contains("base do escopo")
            || texto.contains("fechar a base")
            || texto.contains("avancar na proxima etapa")
            || texto.contains("monto a proposta")
            || texto.contains("passar uma proposta")
            || texto.contains("organizar prazo e valor");
    }

    private static double similaridade(String original, String nova) {
        String[] termosOriginais = termos(original);
        String[] termosNovos = termos(nova);
        if (termosOriginais.length == 0 || termosNovos.length == 0) {
            return 0d;
        }
        int comuns = 0;
        java.util.Set<String> novos = new java.util.HashSet<>(java.util.Arrays.asList(termosNovos));
        for (String termo : termosOriginais) {
            if (novos.contains(termo)) {
                comuns++;
            }
        }
        return comuns / (double) Math.max(termosOriginais.length, termosNovos.length);
    }

    private static String[] termos(String texto) {
        String limpo = texto == null ? "" : texto.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        return limpo.isBlank() ? new String[0] : limpo.split(" ");
    }
}
