package com.freelasatende.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;

final class AutoAtendimentoPayloadHelper {

    private AutoAtendimentoPayloadHelper() {
    }

    static String atualizarMensagemPayload(ObjectMapper objectMapper, String payloadJson, String novoTexto)
        throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(payloadJson);
        if (!(node instanceof ObjectNode objectNode)) {
            throw new IllegalStateException("Payload da ação não é um objeto JSON");
        }
        objectNode.put("mensagem", novoTexto);
        return objectMapper.writeValueAsString(objectNode);
    }

    static String anexarNotaEnsinoPayload(ObjectMapper objectMapper, String payloadJson, String orientacao)
        throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(payloadJson);
        if (node instanceof ObjectNode objectNode) {
            ArrayNode notas = objectNode.withArray("notasEnsino");
            notas.addObject()
                .put("data", LocalDateTime.now().toString())
                .put("texto", orientacao);
            return objectMapper.writeValueAsString(objectNode);
        }
        return payloadJson;
    }

    static String anexarRespostaDuvidaIaPayload(
        ObjectMapper objectMapper,
        String payloadJson,
        String pergunta,
        String resposta,
        String regra
    ) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(payloadJson);
        if (node instanceof ObjectNode objectNode) {
            ArrayNode respostas = objectNode.withArray("respostasDuvidasIa");
            respostas.addObject()
                .put("data", LocalDateTime.now().toString())
                .put("pergunta", pergunta)
                .put("resposta", resposta)
                .put("regraConfirmada", regra != null ? regra : "");
            return objectMapper.writeValueAsString(objectNode);
        }
        return payloadJson;
    }

    static String registrarTelegramMessageIdPayload(ObjectMapper objectMapper, String payloadJson, Long telegramMessageId)
        throws JsonProcessingException {
        ObjectNode payload = lerPayloadObjetoSeguro(objectMapper, payloadJson);
        payload.put("telegramMessageId", telegramMessageId);
        return objectMapper.writeValueAsString(payload);
    }

    static JsonNode lerPayloadSeguro(ObjectMapper objectMapper, String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(payloadJson);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    static Long extrairTelegramMessageIdPayloadSeguro(ObjectMapper objectMapper, String payloadJson) {
        JsonNode node = lerPayloadSeguro(objectMapper, payloadJson);
        JsonNode id = node.path("telegramMessageId");
        return id.isNumber() ? id.asLong() : null;
    }

    static String extrairNomeClientePayloadSeguro(ObjectMapper objectMapper, String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return "-";
        }
        try {
            JsonNode node = objectMapper.readTree(payloadJson);
            String nome = node.path("nomeCliente").asText("");
            return nome == null || nome.isBlank() ? "-" : nome;
        } catch (Exception e) {
            return "-";
        }
    }

    static String extrairMensagemPayloadSeguro(ObjectMapper objectMapper, String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(payloadJson);
            JsonNode mensagem = node.get("mensagem");
            return mensagem != null && !mensagem.isNull() ? mensagem.asText("") : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static ObjectNode lerPayloadObjetoSeguro(ObjectMapper objectMapper, String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(payloadJson);
            return node != null && node.isObject() ? (ObjectNode) node : objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}
