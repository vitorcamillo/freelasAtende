package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;

/** Classifica erros da API do Freelancer sem depender apenas do status HTTP. */
final class FreelancerErrorClassifier {

    private FreelancerErrorClassifier() {
    }

    static MarketplaceAdapterErrorType tipoErroMensagem(
        int statusCode,
        String corpo,
        ObjectMapper objectMapper
    ) {
        if (statusCode == 404 || statusCode == 410) {
            return MarketplaceAdapterErrorType.CONVERSA_INACESSIVEL;
        }
        if (statusCode == 401) {
            return MarketplaceAdapterErrorType.FALHA_AUTENTICACAO;
        }
        if (statusCode == 403 && destinatarioNaoAceitaMensagem(corpo, objectMapper)) {
            return MarketplaceAdapterErrorType.CONVERSA_INACESSIVEL;
        }
        if (statusCode == 403) {
            return MarketplaceAdapterErrorType.FALHA_AUTENTICACAO;
        }
        return MarketplaceAdapterErrorType.FALHA_ENVIO;
    }

    private static boolean destinatarioNaoAceitaMensagem(String corpo, ObjectMapper objectMapper) {
        if (corpo == null || corpo.isBlank()) {
            return false;
        }
        String texto = corpo.toLowerCase(Locale.ROOT);
        try {
            JsonNode node = objectMapper.readTree(corpo);
            texto = String.join(" ",
                node.path("message").asText(""),
                node.path("error_code").asText(""),
                node.path("errorcode").asText(""),
                node.path("status").asText("")
            ).toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            // Resposta nao JSON: os marcadores textuais abaixo continuam seguros.
        }
        return texto.contains("no longer able to message this user")
            || texto.contains("permissiondenied")
            || texto.contains("permission denied");
    }
}
