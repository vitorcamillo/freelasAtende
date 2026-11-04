package com.freelasatende.service.impl;

import java.util.ArrayList;
import java.util.List;

final class TelegramMessageSplitter {

    private TelegramMessageSplitter() {
    }

    static List<String> dividirMensagemChat(String texto, int limite) {
        if (texto == null || texto.isBlank()) {
            return List.of("-");
        }

        String normalizado = texto.replace("\r\n", "\n").replace("\r", "\n").trim();
        if (normalizado.length() <= limite) {
            return List.of(normalizado);
        }

        List<String> partes = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        boolean blocoCodigoAberto = false;

        for (String linha : normalizado.split("\n", -1)) {
            String linhaComQuebra = linha + "\n";
            if (atual.length() + linhaComQuebra.length() > limite && !atual.isEmpty()) {
                fecharParte(partes, atual, blocoCodigoAberto);
            }

            if (linhaComQuebra.length() > limite) {
                for (String pedaco : quebrarLinhaLonga(linhaComQuebra, limite - 16)) {
                    if (atual.length() + pedaco.length() > limite && !atual.isEmpty()) {
                        fecharParte(partes, atual, blocoCodigoAberto);
                    }
                    atual.append(pedaco);
                }
            } else {
                atual.append(linhaComQuebra);
            }

            if (contarOcorrencias(linha, "```") % 2 == 1) {
                blocoCodigoAberto = !blocoCodigoAberto;
            }
        }

        if (!atual.isEmpty()) {
            partes.add(removerQuebrasFinais(atual.toString()));
        }
        return partes;
    }

    private static void fecharParte(List<String> partes, StringBuilder atual, boolean blocoCodigoAberto) {
        if (blocoCodigoAberto) {
            atual.append("```\n");
        }
        partes.add(removerQuebrasFinais(atual.toString()));
        atual.setLength(0);
        if (blocoCodigoAberto) {
            atual.append("```\n");
        }
    }

    private static List<String> quebrarLinhaLonga(String texto, int tamanho) {
        List<String> partes = new ArrayList<>();
        for (int i = 0; i < texto.length(); i += tamanho) {
            partes.add(texto.substring(i, Math.min(texto.length(), i + tamanho)));
        }
        return partes;
    }

    private static int contarOcorrencias(String texto, String padrao) {
        int total = 0;
        int indice = 0;
        while ((indice = texto.indexOf(padrao, indice)) >= 0) {
            total++;
            indice += padrao.length();
        }
        return total;
    }

    private static String removerQuebrasFinais(String texto) {
        return texto.replaceFirst("\\s+$", "");
    }
}
