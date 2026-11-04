package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Formata o contexto de memoria injetado no prompt da IA conforme o uso.
 * Separado de {@link AvaliadorIaMemoriaHelper} para manter o guard de
 * tamanho de arquivo do projeto (max 500 linhas) e porque cada uso
 * (triagem de projeto, conversa, proposta) exige um recorte diferente.
 */
final class AvaliadorIaMemoriaPromptHelper {

    private static final int MAX_RECUSAS_NO_PROMPT = 8;
    private static final int MAX_REGRAS_CONVERSA_NO_PROMPT = 18;
    private static final int MAX_CHARS_REGRAS_CONVERSA = 6_000;

    private AvaliadorIaMemoriaPromptHelper() {
    }

    static String formatarContexto(JsonNode root, AvaliadorIaMemoriaHelper.ContextoUso uso) {
        StringBuilder sb = new StringBuilder();

        if (uso == AvaliadorIaMemoriaHelper.ContextoUso.TRIAGEM) {
            if (root.has("regras_consolidadas") && !root.path("regras_consolidadas").asText().isBlank()) {
                sb.append("REGRAS DE AVALIAÇÃO CONSOLIDADAS PELO SEU APRENDIZADO:\n");
                sb.append(root.path("regras_consolidadas").asText()).append("\n\n");
            }
        } else {
            appendRegrasDeConversa(sb, root.path("regras_ensinamento"), uso);
        }

        appendRecusasRecentes(sb, root.path("regras_ensinamento"));

        if (uso != AvaliadorIaMemoriaHelper.ContextoUso.TRIAGEM) {
            return sb.toString().trim();
        }

        sb.append("Histórico recente:\n");
        JsonNode historico = root.path("historico_recente");
        if (historico.isArray()) {
            for (JsonNode item : historico) {
                sb.append("- ").append(item.path("tipo").asText())
                  .append(": ").append(item.path("titulo").asText())
                  .append(" (").append(item.path("data").asText()).append(")\n");
            }
        }

        JsonNode stats = root.path("estatisticas");
        sb.append("\nEstatísticas:\n");
        sb.append("- Total avaliados: ").append(stats.path("total_projetos_avaliados").asInt()).append("\n");
        sb.append("- Aprovados: ").append(stats.path("total_aprovados").asInt()).append("\n");
        sb.append("- Recusados: ").append(stats.path("total_recusados").asInt()).append("\n");

        return sb.toString();
    }

    /**
     * Regras de tom/escopo aprendidas em aprovacoes de conversa e proposta.
     * A memoria consolidada mistura regras de triagem de projeto com regras de
     * conversa; injetar tudo em resposta de conversa poluia o prompt com regras
     * irrelevantes (escopo de projeto, duvidas de orcamento etc). Aqui filtramos
     * apenas o que foi aprendido em RESPONDER_CONVERSA / ENVIAR_PROPOSTA.
     */
    private static void appendRegrasDeConversa(StringBuilder sb, JsonNode ensinamentos,
                                               AvaliadorIaMemoriaHelper.ContextoUso uso) {
        if (!ensinamentos.isArray() || ensinamentos.isEmpty()) {
            return;
        }
        List<String> regras = new ArrayList<>();
        for (int i = ensinamentos.size() - 1; i >= 0; i--) {
            JsonNode item = ensinamentos.get(i);
            String tipo = item.path("tipo_acao").asText("");
            boolean relevante = uso == AvaliadorIaMemoriaHelper.ContextoUso.PROPOSTA
                ? ("RESPONDER_CONVERSA".equals(tipo) || "ENVIAR_PROPOSTA".equals(tipo))
                : "RESPONDER_CONVERSA".equals(tipo);
            if (!relevante) {
                continue;
            }
            String regra = item.path("regra").asText("").trim();
            if (regra.isBlank() || regras.contains(regra)) {
                continue;
            }
            String motivo = item.path("motivo").asText("").trim();
            regras.add(motivo.isBlank() ? "- " + regra : "- [" + motivo + "] " + regra);
        }
        if (regras.isEmpty()) {
            return;
        }
        int totalChars = 0;
        List<String> escolhidas = new ArrayList<>();
        for (String linha : regras) {
            if (escolhidas.size() >= MAX_REGRAS_CONVERSA_NO_PROMPT
                || totalChars + linha.length() + 1 > MAX_CHARS_REGRAS_CONVERSA) {
                break;
            }
            escolhidas.add(linha);
            totalChars += linha.length() + 1;
        }
        if (escolhidas.isEmpty()) {
            return;
        }
        sb.append("REGRAS APRENDIDAS COM SUAS APROVAÇÕES EM CONVERSAS:\n");
        for (String linha : escolhidas) {
            sb.append(linha).append("\n");
        }
        sb.append("\n");
    }

    /**
     * Reinjeta no prompt o que o humano recusou nas ultimas rodadas.
     * Sem isso o feedback de recusa era gravado mas nunca voltava para a IA,
     * e ela repetia o mesmo erro na geracao seguinte.
     */
    private static void appendRecusasRecentes(StringBuilder sb, JsonNode ensinamentos) {
        if (!ensinamentos.isArray() || ensinamentos.isEmpty()) {
            return;
        }
        List<String> recusas = new ArrayList<>();
        for (JsonNode item : ensinamentos) {
            if (item.path("aprovado").asBoolean(true)) {
                continue;
            }
            String motivo = item.path("motivo").asText("").trim();
            String regra = primeiraLinha(item.path("regra").asText(""));
            if (motivo.isBlank() && regra.isBlank()) {
                continue;
            }
            String linha = motivo.isBlank() ? regra : (regra.isBlank() ? motivo : motivo + " - " + regra);
            if (!recusas.contains(linha)) {
                recusas.add(linha);
            }
        }
        if (recusas.isEmpty()) {
            return;
        }
        // mantem apenas as mais recentes para nao inflar o prompt
        List<String> recentes = recusas.size() > MAX_RECUSAS_NO_PROMPT
            ? recusas.subList(recusas.size() - MAX_RECUSAS_NO_PROMPT, recusas.size())
            : recusas;

        sb.append("EVITE REPETIR ESTES ERROS JÁ RECUSADOS POR REVISÃO HUMANA:\n");
        for (String recusa : recentes) {
            sb.append("- ").append(recusa).append("\n");
        }
        sb.append("\n");
    }

    private static String primeiraLinha(String texto) {
        if (texto == null || texto.isBlank()) {
            return "";
        }
        String[] linhas = texto.strip().split("\\R", 2);
        String primeira = linhas[0].strip();
        return primeira.length() > 160 ? primeira.substring(0, 160).strip() : primeira;
    }
}
