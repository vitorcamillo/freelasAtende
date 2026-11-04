package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.freelasatende.model.entity.AcaoPendente;

final class ContextoAcaoIaFormatter {

    private ContextoAcaoIaFormatter() {
    }

    static String montar(AcaoPendente acao, JsonNode payload, String orientacao) {
        StringBuilder contexto = new StringBuilder();
        contexto.append("Ação #").append(acao.getId())
            .append(" | tipo: ").append(acao.getTipo())
            .append(" | projeto: ").append(valorTexto(acao.getTituloProjeto()))
            .append(" | projetoId: ").append(acao.getProjetoId() != null ? acao.getProjetoId() : "-")
            .append(" | conversaId: ").append(acao.getConversaId() != null ? acao.getConversaId() : "-")
            .append("\nResumo interno: ").append(valorTexto(acao.getResumo()))
            .append("\nObservações humanas anteriores: ").append(valorTexto(acao.getObservacaoUsuario()))
            .append("\nOrientação humana nova: ").append(valorTexto(orientacao));

        appendCampoPayload(contexto, payload, "contextoProjeto", "\n\nContexto do projeto lido:\n");
        appendCampoPayload(contexto, payload, "enviosAnteriores", "\n\nO que eu ja enviei neste projeto:\n");
        appendCampoPayload(contexto, payload, "historicoConversa", "\n\nHistórico limpo da conversa:\n");
        appendCampoPayload(contexto, payload, "mensagemGatilhoTexto", "\n\nMensagem do cliente que disparou a resposta:\n");
        appendListaPayload(contexto, payload, "duvidas", "\n\nDúvidas originais da IA:\n");
        appendMensagensPayload(contexto, payload, "ultimasMensagensCliente", "\n\nÚltimas falas do cliente:\n");
        appendMensagensPayload(contexto, payload, "ultimasMensagensMinhas", "\n\nMinhas falas detectadas:\n");
        appendListaPayload(contexto, payload, "mensagensIgnoradasResumo", "\n\nMensagens ignoradas:\n");
        appendNotasEnsinoPayload(contexto, payload);
        return contexto.toString();
    }

    private static void appendCampoPayload(StringBuilder sb, JsonNode payload, String campo, String titulo) {
        String valor = payload.path(campo).asText("");
        if (valor == null || valor.isBlank()) {
            return;
        }
        sb.append(titulo).append(valor);
    }

    private static void appendListaPayload(StringBuilder sb, JsonNode payload, String campo, String titulo) {
        JsonNode lista = payload.path(campo);
        if (!lista.isArray() || lista.isEmpty()) {
            return;
        }
        sb.append(titulo);
        for (JsonNode item : lista) {
            String texto = item.asText("");
            if (!texto.isBlank()) {
                sb.append("- ").append(texto).append("\n");
            }
        }
    }

    private static void appendMensagensPayload(StringBuilder sb, JsonNode payload, String campo, String titulo) {
        JsonNode lista = payload.path(campo);
        if (!lista.isArray() || lista.isEmpty()) {
            return;
        }
        sb.append(titulo);
        for (JsonNode item : lista) {
            String autor = item.path("autor").asText("-");
            String nome = item.path("nome").asText("-");
            String texto = item.path("texto").asText("");
            if (!texto.isBlank()) {
                sb.append("- ").append(autor).append(" | ").append(nome).append(": ").append(texto).append("\n");
            }
        }
    }

    private static void appendNotasEnsinoPayload(StringBuilder sb, JsonNode payload) {
        JsonNode lista = payload.path("notasEnsino");
        if (!lista.isArray() || lista.isEmpty()) {
            return;
        }
        sb.append("\n\nNotas de ensino já salvas:\n");
        for (JsonNode item : lista) {
            String texto = item.path("texto").asText("");
            if (!texto.isBlank()) {
                sb.append("- ").append(texto).append("\n");
            }
        }
    }

    private static String valorTexto(String texto) {
        return texto == null || texto.isBlank() ? "-" : texto;
    }
}
