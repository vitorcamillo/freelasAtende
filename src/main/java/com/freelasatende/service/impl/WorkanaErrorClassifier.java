package com.freelasatende.service.impl;

import java.net.http.HttpResponse;
import java.text.Normalizer;
import java.util.Locale;

import static com.freelasatende.service.impl.WorkanaClientSupport.resumirResposta;

final class WorkanaErrorClassifier {

    private WorkanaErrorClassifier() {
    }

    static MarketplaceAdapterErrorType tipoErroEnvio(HttpResponse<String> response) {
        if (response.statusCode() == 401) {
            return MarketplaceAdapterErrorType.FALHA_AUTENTICACAO;
        }
        if (response.statusCode() == 403) {
            String resumo = normalizarTextoWorkana(resumirResposta(response.body()));
            if (indicaLimiteConexoesWorkana(resumo)) {
                return MarketplaceAdapterErrorType.LIMITE_CONEXOES;
            }
            if (resumo.contains("acesso negado")
                || resumo.contains("nao possui autorizacao")) {
                return MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL;
            }
            return MarketplaceAdapterErrorType.FALHA_AUTENTICACAO;
        }
        if (response.statusCode() == 404 || response.statusCode() == 410) {
            return MarketplaceAdapterErrorType.CONVERSA_INACESSIVEL;
        }
        return MarketplaceAdapterErrorType.FALHA_ENVIO;
    }

    /**
     * A Workana responde a pagina "Acesso Negado" tanto com 403 quanto com 200, dependendo
     * da rota. Quando ela chega com 200, o fluxo seguia adiante, nao encontrava formulario,
     * caia no fallback de URL montada e so falhava no POST seguinte com um "HTTP 400: corpo
     * vazio" que nao diz nada sobre a causa real.
     */
    static boolean indicaAcessoNegado(String corpoHtml) {
        String resumo = normalizarTextoWorkana(resumirResposta(corpoHtml));
        return resumo.contains("acesso negado")
            || resumo.contains("nao possui autorizacao")
            || resumo.contains("nao possui autorizacao para acessar");
    }

    /**
     * Traduz a negacao da Workana para uma causa acionavel: quando o corpo cita limite de
     * conexoes/plano, o operador precisa liberar conexoes; caso contrario a acao de iniciar
     * contato esta indisponivel para a conta.
     */
    static MarketplaceAdapterErrorType tipoAcessoNegado(String corpoHtml) {
        String resumo = normalizarTextoWorkana(resumirResposta(corpoHtml));
        return indicaLimiteConexoesWorkana(resumo)
            ? MarketplaceAdapterErrorType.LIMITE_CONEXOES
            : MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL;
    }

    private static boolean indicaLimiteConexoesWorkana(String textoNormalizado) {
        // Padrões ampliados para capturar variações de mensagem de limite Workana
        return textoNormalizado.contains("nao tem mais conexoes disponiveis")
            || textoNormalizado.contains("nao tem mais conexoes")
            || textoNormalizado.contains("limite semanal")
            || textoNormalizado.contains("limite de contatos")
            || textoNormalizado.contains("conexoes possiveis da semana")
            || textoNormalizado.contains("contatos semanais disponiveis")
            || textoNormalizado.contains("melhore seu plano")
            || textoNormalizado.contains("plano de beneficios")
            || textoNormalizado.contains("aumente seu plano")
            || textoNormalizado.contains("beneficios workana");
    }

    private static String normalizarTextoWorkana(String texto) {
        if (texto == null || texto.isBlank()) {
            return "";
        }
        String semAcentos = Normalizer.normalize(texto, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");
        return semAcentos.toLowerCase(Locale.ROOT);
    }
}
