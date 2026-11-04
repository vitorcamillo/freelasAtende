package com.freelasatende.service.impl;

import com.freelasatende.config.OperacaoDefaults;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
public class TelegramMessageFormatter {

    private static final int LIMITE_MENSAGEM_TELEGRAM = OperacaoDefaults.LIMITE_MENSAGEM_TELEGRAM;

    public String codeBlock(String texto) {
        return "```\n" + cleanCodeText(limit(valueOrDash(texto), 900)) + "\n```";
    }

    public String text(String texto) {
        return cleanCodeText(valueOrDash(texto))
            .replace("\\", "/")
            .replace("*", "")
            .replace("_", " ")
            .replace("[", "(")
            .replace("]", ")");
    }

    public String limit(String texto, int limite) {
        if (texto == null || texto.isBlank()) {
            return "-";
        }
        if (texto.length() <= limite) {
            return texto;
        }
        return texto.substring(0, limite).trim() + "...";
    }

    public String limitNotification(String texto) {
        if (texto == null) {
            return "";
        }
        if (texto.length() <= LIMITE_MENSAGEM_TELEGRAM) {
            return texto;
        }
        String cortado = texto.substring(0, LIMITE_MENSAGEM_TELEGRAM);
        if (contar(cortado, "```") % 2 != 0) {
            cortado += "\n```";
        }
        return cortado
            + "\n\n[texto muito longo, cortado apenas por limite tecnico do Telegram]";
    }

    private int contar(String texto, String trecho) {
        int total = 0;
        int index = 0;
        while ((index = texto.indexOf(trecho, index)) >= 0) {
            total++;
            index += trecho.length();
        }
        return total;
    }

    private String cleanCodeText(String texto) {
        if (texto == null) {
            return "-";
        }
        return HtmlUtils.htmlUnescape(texto)
            .replace("```", "'''")
            .replace("`", "'");
    }

    private String valueOrDash(String texto) {
        return texto == null || texto.isBlank() ? "-" : texto;
    }
}
