package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.TipoAcaoPendente;

final class AcaoPendenteNotificationFormatter {

    private AcaoPendenteNotificationFormatter() {
    }

    static String montarMensagem(
        AcaoPendente acao,
        String textoCliente,
        String nomeCliente,
        JsonNode payload,
        TelegramMessageFormatter telegramMessageFormatter
    ) {
        String tipoAcao = switch (acao.getTipo()) {
            case ENVIAR_PROPOSTA -> "PROPOSTA";
            case ENVIAR_PERGUNTA -> "PERGUNTA";
            case RESPONDER_CONVERSA -> "RESPOSTA";
            case CANCELAR_PROPOSTA -> "CANCELAMENTO";
        };
        return "Pendencia #" + acao.getId() + " | " + tipoAcao + "\n\n"
            + telegramMessageFormatter.text(PlataformaFreela.normalizar(acao.getPlataforma()).label()) + "\n"
            + telegramMessageFormatter.text(acao.getTituloProjeto()) + "\n"
            + telegramMessageFormatter.text(nomeCliente) + "\n"
            + "\nLeitura rapida:\n"
            + telegramMessageFormatter.codeBlock(leituraRapida(acao, payload)) + "\n\n"
            + rotuloMensagemCliente(acao.getTipo()) + ":\n"
            + telegramMessageFormatter.codeBlock(textoCliente.isBlank()
                ? "[sem texto ao cliente]\nReescreva ou ensine antes de aprovar qualquer envio."
                : textoCliente);
    }

    private static String leituraRapida(AcaoPendente acao, JsonNode payload) {
        StringBuilder sb = new StringBuilder();
        String gatilho = payload.path("mensagemGatilhoTexto").asText("");
        if (!gatilho.isBlank()) {
            sb.append("Gatilho: ").append(limitarTexto(gatilho, 220)).append("\n");
        }
        String avisoQualidade = avisoQualidade(payload);
        if (!avisoQualidade.isBlank()) {
            sb.append("Aviso: ").append(limitarTexto(avisoQualidade, 260)).append("\n");
        }
        JsonNode duvidas = payload.path("duvidas");
        if (duvidas.isArray() && !duvidas.isEmpty()) {
            sb.append("Duvida: ").append(limitarTexto(duvidas.get(0).asText(""), 220)).append("\n");
        }
        if ((gatilho.isBlank()) && !(duvidas.isArray() && !duvidas.isEmpty())
            && acao.getResumo() != null && !acao.getResumo().isBlank()) {
            sb.append("Motivo: ").append(limitarTexto(acao.getResumo(), 220)).append("\n");
        }
        return sb.isEmpty() ? "Sem leitura rapida registrada." : sb.toString().trim();
    }

    private static String avisoQualidade(JsonNode payload) {
        JsonNode riscos = payload.path("riscosDecisao");
        if (riscos.isArray()) {
            for (JsonNode risco : riscos) {
                String texto = risco.asText("");
                if (texto.startsWith("guardiao_qualidade: ")) {
                    return texto.substring("guardiao_qualidade: ".length());
                }
            }
        }
        return "";
    }

    private static String rotuloMensagemCliente(TipoAcaoPendente tipo) {
        return switch (tipo) {
            case ENVIAR_PERGUNTA -> "Pergunta que sera enviada se aprovar";
            case RESPONDER_CONVERSA -> "Resposta ao cliente";
            case ENVIAR_PROPOSTA -> "Mensagem da proposta que sera enviada se aprovar";
            case CANCELAR_PROPOSTA -> "Mensagem de cancelamento que sera enviada se aprovar";
        };
    }

    private static String limitarTexto(String texto, int limite) {
        if (texto == null || texto.isBlank()) {
            return "-";
        }
        if (texto.length() <= limite) {
            return texto;
        }
        return texto.substring(0, limite).trim() + "...";
    }
}
