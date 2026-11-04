package com.freelasatende.service.impl;

import com.freelasatende.config.IaConfig;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class AvaliadorIaRuntimeHelper {

    private AvaliadorIaRuntimeHelper() {
    }

    static Proposta sugerirProposta(Projeto projeto) {
        BigDecimal media = projeto.getMediaProposta();
        BigDecimal orcamento = projeto.getOrcamento();

        BigDecimal valorSugerido = null;
        if (media != null && media.compareTo(BigDecimal.ZERO) > 0) {
            valorSugerido = media.multiply(BigDecimal.valueOf(1.1));
        } else if (orcamento != null) {
            valorSugerido = orcamento;
        }

        BigDecimal valorMinimo = projeto.getValorMinimo();
        if (valorSugerido == null) {
            valorSugerido = valorMinimo != null ? valorMinimo : BigDecimal.valueOf(500);
        }
        if (valorMinimo != null && valorSugerido.compareTo(valorMinimo) < 0) {
            valorSugerido = valorMinimo;
        }

        return Proposta.builder()
            .projetoId(projeto.getId())
            .valorProposto(valorSugerido)
            .valorMinimo(valorMinimo)
            .mediaMercado(media)
            .build();
    }

    static List<String> extrairDuvidas(String resposta) {
        List<String> duvidas = new ArrayList<>();
        if (resposta == null || resposta.isBlank()) {
            return duvidas;
        }
        if (resposta.trim().toUpperCase().contains("SEM_DUVIDAS")
            || resposta.trim().toUpperCase().contains("SEM DÚVIDAS")) {
            return duvidas;
        }
        for (String linha : resposta.split("\n")) {
            String duvida = limparLinhaIA(linha);
            if (duvida.isEmpty()) continue;
            if (ehRespostaNegativaCurta(duvida)) continue;
            if (duvida.length() < 5) continue;
            if (duvida.matches("(?i)^(sim|nao|ok|sim,?|de acordo|não tenho)$")) continue;
            if (duvidas.stream().anyMatch(d -> d.equalsIgnoreCase(duvida))) continue;
            duvidas.add(duvida);
            if (duvidas.size() >= 1) {
                break;
            }
        }
        return duvidas;
    }

    static boolean textoPropostaInvalido(String texto) {
        if (texto == null || texto.isBlank()) {
            return true;
        }
        String limpo = texto.trim().toLowerCase()
            .replaceAll("^[-*•\\s]+", "")
            .replaceAll("\\s+", " ");
        if (limpo.equals("nao") || limpo.equals("não") || limpo.equals("sim") || limpo.equals("ok")) {
            return true;
        }
        if (limpo.length() < 30) {
            return true;
        }
        return !limpo.contains("r$") && !limpo.contains("valor");
    }

    private static final Pattern CARACTERES_CJK = Pattern.compile("[一-鿿㐀-䶿]");

    static boolean contemCaracteresCjk(String texto) {
        return texto != null && CARACTERES_CJK.matcher(texto).find();
    }

    static boolean deveUsarFallbackPorApiKeyAusente(IaConfig iaConfig) {
        if (!iaConfig.isFallbackWhenMissingKey()) {
            return false;
        }
        String apiKey = iaConfig.getApiKey() != null ? iaConfig.getApiKey().trim() : "";
        if (!apiKey.isBlank()) {
            return false;
        }
        String provider = iaConfig.getProvider() != null
            ? iaConfig.getProvider().trim().toLowerCase(Locale.ROOT)
            : "";
        return provider.equals("openai");
    }

    static boolean ehRespostaNegativaCurta(String texto) {
        if (texto == null) {
            return true;
        }
        String normalizado = texto
            .trim()
            .toLowerCase()
            .replaceAll("^[-*•\\s]+", "")
            .replaceAll("\\s+", " ");
        return normalizado.equals("nao")
            || normalizado.equals("não")
            || normalizado.equals("nenhuma")
            || normalizado.equals("sem duvidas")
            || normalizado.equals("sem dúvidas");
    }

    private static String limparLinhaIA(String linha) {
        if (linha == null) {
            return "";
        }
        return linha
            .replaceAll("^\\s*[-*•\\d.)]+\\s*", "")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
