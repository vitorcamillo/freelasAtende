package com.freelasatende.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class PerguntaClienteFormatter {

    private static final int LIMITE_PERGUNTAS_QUALIFICACAO = 3;

    private PerguntaClienteFormatter() {
    }

    static String montarPerguntaParaCliente(List<String> duvidas) {
        List<String> pontos = new ArrayList<>();
        if (duvidas != null) {
            LinkedHashSet<String> unicos = new LinkedHashSet<>();
            for (String duvida : duvidas) {
                String normalizada = normalizarDuvidaParaCliente(duvida);
                if (normalizada == null || normalizada.isBlank()) {
                    continue;
                }
                String chave = normalizada.trim().toLowerCase();
                if (!unicos.contains(chave)) {
                    unicos.add(chave);
                    pontos.add(normalizada);
                }
                if (pontos.size() >= LIMITE_PERGUNTAS_QUALIFICACAO) {
                    break;
                }
            }
        }

        if (pontos.isEmpty()) {
            return "Oi!\n\n"
                + "Li o projeto e queria entender melhor antes de assumir qualquer coisa. "
                + "Qual entrega precisa sair primeiro e o que já existe hoje para eu considerar?";
        }

        return montarPerguntaObjetiva(pontos);
    }

    private static String montarPerguntaObjetiva(List<String> pontos) {
        return "Oi!\n\n"
            + "Li o projeto e queria entender melhor antes de assumir qualquer coisa. "
            + montarPerguntasInline(pontos)
            + "\n\nCom isso eu consigo te responder com um caminho mais certeiro.";
    }

    private static String montarPerguntasInline(List<String> pontos) {
        if (pontos == null || pontos.isEmpty()) {
            return "";
        }
        if (pontos.size() == 1) {
            return garantirPergunta(pontos.get(0));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(garantirPergunta(pontos.get(0)));
        for (int i = 1; i < pontos.size(); i++) {
            sb.append(" Também queria confirmar: ")
                .append(Character.toLowerCase(pontos.get(i).charAt(0)))
                .append(pontos.get(i).substring(1));
            if (!pontos.get(i).endsWith("?")) {
                sb.append("?");
            }
        }
        return sb.toString();
    }

    private static String garantirPergunta(String texto) {
        if (texto == null || texto.isBlank()) {
            return "";
        }
        String ajustado = texto.trim();
        if (ajustado.endsWith("?")) {
            return ajustado;
        }
        return ajustado + "?";
    }

    private static String normalizarDuvidaParaCliente(String texto) {
        if (texto == null) {
            return "";
        }
        String normalizado = texto.trim()
            .replaceAll("^[-*•\\d.)\\s]+", "")
            .replaceAll("\\s+", " ");
        if (normalizado.isBlank()) {
            return "";
        }

        String lower = normalizado.toLowerCase();

        // Regra comercial: NUNCA pedir budget/investimento/valor do cliente.
        if (lower.contains("orçamento")
            || lower.contains("orcamento")
            || lower.contains("budget")
            || lower.contains("investimento")
            || lower.contains("faixa de investimento")
            || lower.contains("valor disponível")
            || lower.contains("valor disponivel")
            || lower.contains("valor reserv")
            || lower.contains("quanto pretende")
            || lower.contains("quanto você pretende")
            || lower.contains("quanto voce pretende")
            || lower.contains("quanto deseja investir")
            || lower.contains("valor/budget")
            || lower.contains("proposta numérica")
            || lower.contains("proposta numerica")
            || lower.contains("faixa de valor")
            || lower.contains("quanto está disposto")
            || lower.contains("quanto esta disposto")
            || lower.contains("valor que você")
            || lower.contains("valor que voce")) {
            return "Qual o escopo principal que você precisa nessa primeira entrega e o que fica fora dela";
        }

        if (lower.contains("direitos autorais") || lower.contains("replic")
            || lower.contains("copiar") || lower.contains("referência")
            || lower.contains("referencia")) {
            return "Esse site que você quer como base é seu ou de terceiros? Só pra eu entender o que posso aproveitar";
        }

        if (lower.contains("problema técnico específico")
            || lower.contains("impediu a publicação")
            || lower.contains("impediu a publicacao")
            || lower.contains("problema que causou")
            || lower.contains("falha que impediu")) {
            return "Tem algum site atual que eu precise considerar ou é do zero?";
        }

        if (lower.contains("integração com sistemas externos")
            || lower.contains("integracao com sistemas externos")
            || lower.contains("integração com sistemas")
            || lower.contains("integracao com sistemas")) {
            return "Precisa de alguma integração tipo pagamento, CRM ou analytics?";
        }

        if (lower.contains("requisitos funcionais")
            || lower.contains("especificações técnicas")
            || lower.contains("especificacoes tecnicas")) {
            return "Já tem uma ideia clara do que o produto precisa fazer ou precisa de ajuda nisso?";
        }

        if (lower.contains("prazo de entrega")
            || lower.contains("deadline")
            || lower.contains("data limite")) {
            return "Tem alguma urgência ou prazo que eu precise considerar?";
        }

        return Character.toUpperCase(normalizado.charAt(0)) + normalizado.substring(1);
    }
}
