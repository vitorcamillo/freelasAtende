package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FreelancerErrorClassifierTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("403 permission denied do destinatario torna a conversa inacessivel")
    void permissionDeniedDoDestinatarioTornaConversaInacessivel() {
        String corpo = """
            {"status":"error","message":"You are no longer able to message this user",
             "errorcode":"AuthorizationExceptionCodes.PERMISSIONSONDENIED",
             "requestid":"id"}
            """;

        assertEquals(
            MarketplaceAdapterErrorType.CONVERSA_INACESSIVEL,
            FreelancerErrorClassifier.tipoErroMensagem(403, corpo, objectMapper)
        );
    }

    @Test
    @DisplayName("403 generico continua sendo falha de autenticacao")
    void forbiddenGenericoContinuaFalhaAutenticacao() {
        assertEquals(
            MarketplaceAdapterErrorType.FALHA_AUTENTICACAO,
            FreelancerErrorClassifier.tipoErroMensagem(403, "{\"message\":\"Forbidden\"}", objectMapper)
        );
    }

    @Test
    @DisplayName("404 e 410 encerram conversa; demais falhas continuam falha de envio")
    void classificaDemaisStatus() {
        assertEquals(MarketplaceAdapterErrorType.CONVERSA_INACESSIVEL,
            FreelancerErrorClassifier.tipoErroMensagem(404, "", objectMapper));
        assertEquals(MarketplaceAdapterErrorType.CONVERSA_INACESSIVEL,
            FreelancerErrorClassifier.tipoErroMensagem(410, "", objectMapper));
        assertEquals(MarketplaceAdapterErrorType.FALHA_ENVIO,
            FreelancerErrorClassifier.tipoErroMensagem(429, "", objectMapper));
    }
}
