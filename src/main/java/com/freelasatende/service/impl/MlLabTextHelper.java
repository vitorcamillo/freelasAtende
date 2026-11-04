package com.freelasatende.service.impl;

import java.util.Locale;

final class MlLabTextHelper {

    private MlLabTextHelper() {
    }

    static boolean contem(String text, String... termos) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String termo : termos) {
            if (lower.contains(termo)) {
                return true;
            }
        }
        return false;
    }

    static String categoriaCenario(String cenario) {
        if (contem(cenario, "wordpress", "site", "landing")) {
            return "web/site";
        }
        if (contem(cenario, "automacao", "api", "integracao")) {
            return "automacao";
        }
        if (contem(cenario, "legado", "complexo", "sistema")) {
            return "legado complexo";
        }
        if (contem(cenario, "senha", "pagamento", "risco")) {
            return "risco operacional";
        }
        return "atendimento geral";
    }

    static double arredondar(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    static String limitar(String text, int max) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "...";
    }
}
