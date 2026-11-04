package com.freelasatende.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.freelasatende.model.dto.ConversaMensagemControleDTO;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.enums.NivelAutonomia;

import java.util.List;
import java.util.Map;

final class ControleOperacionalJsonHelper {

    private ControleOperacionalJsonHelper() {
    }

    static JsonNode lerPayload(ObjectMapper objectMapper, AcaoPendente acao) {
        if (acao.getPayloadJson() == null || acao.getPayloadJson().isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return objectMapper.readTree(acao.getPayloadJson());
        } catch (Exception e) {
            return MissingNode.getInstance();
        }
    }

    static String textPayload(JsonNode payload, String campo) {
        JsonNode valor = payload.path(campo);
        return valor.isMissingNode() || valor.isNull() ? null : valor.asText(null);
    }

    static Long longPayload(JsonNode payload, String campo) {
        JsonNode valor = payload.path(campo);
        return valor.isNumber() ? valor.asLong() : null;
    }

    static boolean booleanPayload(JsonNode payload, String campo) {
        JsonNode valor = payload.path(campo);
        return valor.isBoolean() && valor.asBoolean();
    }

    static List<ConversaMensagemControleDTO> mensagensPayload(ObjectMapper objectMapper, JsonNode payload, String campo) {
        JsonNode valor = payload.path(campo);
        if (!valor.isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(valor, new TypeReference<List<ConversaMensagemControleDTO>>() {});
    }

    static List<String> textosPayload(ObjectMapper objectMapper, JsonNode payload, String campo) {
        JsonNode valor = payload.path(campo);
        if (!valor.isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(valor, new TypeReference<List<String>>() {});
    }

    static NivelAutonomia nivelAutonomiaPayload(JsonNode payload) {
        String valor = textPayload(payload, "nivelAutonomia");
        if (valor == null || valor.isBlank()) {
            return NivelAutonomia.RASCUNHA;
        }
        try {
            return NivelAutonomia.valueOf(valor.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NivelAutonomia.RASCUNHA;
        }
    }

    static String json(ObjectMapper objectMapper, Object valor) {
        try {
            return objectMapper.writeValueAsString(valor == null ? List.of() : valor);
        } catch (Exception e) {
            return "[]";
        }
    }

    static List<String> listaJson(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    static Map<String, Object> mapJson(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    static String limitar(String texto, int limite) {
        if (texto == null) {
            return "";
        }
        if (texto.length() <= limite) {
            return texto;
        }
        return texto.substring(0, limite).trim();
    }
}
