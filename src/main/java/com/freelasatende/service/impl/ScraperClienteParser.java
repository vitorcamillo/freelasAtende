package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.entity.Cliente;
import com.freelasatende.util.FreelasApiUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
class ScraperClienteParser {

    private final ObjectMapper objectMapper;

    Cliente parsearCliente(String json) {
        try {
            JsonNode root = FreelasApiUtils.decodeUrlEncodedJson(objectMapper, json);
            JsonNode result = extrairResultCliente(root);
            if (result == null || result.isMissingNode() || result.isNull()) {
                log.warn("Resposta de cliente sem campo result válido");
                return null;
            }

            return Cliente.builder()
                .id(extrairLong(result, "idPessoa", "id", "pessoaId"))
                .nome(extrairTexto(result, "nome", "nomePessoa", "name"))
                .mediaNota(extrairDouble(result, "mediaNota", "notaMedia", "ratingMedio"))
                .projetosConcluidos(extrairInteiro(result, "projetosConcluidos", "qtdProjetosConcluidos", "totalProjetosConcluidos"))
                .projetosPublicados(extrairInteiro(result, "projetosPublicados", "qtdProjetosPublicados", "totalProjetosPublicados"))
                .projetosEmAndamento(extrairInteiro(result, "projetosEmAndamento", "qtdProjetosEmAndamento", "totalProjetosEmAndamento"))
                .projetosCancelados(extrairInteiro(result, "projetosCancelados", "qtdProjetosCancelados", "totalProjetosCancelados"))
                .recomendacoes(extrairInteiro(result, "recomendacoes", "qtdRecomendacoes", "totalRecomendacoes"))
                .identidadeVerificada(extrairBoolean(result, "identidadeVerificada", "verificado", "perfilVerificado"))
                .build();

        } catch (Exception e) {
            log.error("Erro ao parsear cliente: {}", e.getMessage());
        }
        return null;
    }

    private JsonNode extrairResultCliente(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return null;
        }
        JsonNode result = root.get("result");
        if (result != null && result.isObject()) {
            return result;
        }
        if (result != null && result.isTextual()) {
            String texto = result.asText();
            if (texto != null && !texto.isBlank() && texto.trim().startsWith("{")) {
                try {
                    return objectMapper.readTree(texto);
                } catch (Exception ignore) {
                    log.debug("Campo result textual não pôde ser convertido em JSON");
                }
            }
        }
        if (root.isObject() && root.has("mediaNota")) {
            return root;
        }
        JsonNode data = root.get("data");
        if (data != null && data.isObject()) {
            JsonNode nestedResult = data.get("result");
            if (nestedResult != null && nestedResult.isObject()) {
                return nestedResult;
            }
        }
        return result;
    }

    private String extrairTexto(JsonNode node, String... campos) {
        for (String campo : campos) {
            JsonNode atual = node.get(campo);
            if (atual != null && !atual.isNull()) {
                String valor = atual.asText(null);
                if (valor != null && !valor.isBlank()) {
                    return valor;
                }
            }
        }
        return null;
    }

    private Integer extrairInteiro(JsonNode node, String... campos) {
        for (String campo : campos) {
            JsonNode atual = node.get(campo);
            if (atual == null || atual.isNull()) {
                continue;
            }
            if (atual.isInt() || atual.isLong()) {
                return atual.asInt();
            }
            if (atual.isTextual()) {
                String somenteDigitos = atual.asText().replaceAll("[^0-9-]", "");
                if (!somenteDigitos.isBlank()) {
                    return Integer.parseInt(somenteDigitos);
                }
            }
        }
        return null;
    }

    private Double extrairDouble(JsonNode node, String... campos) {
        for (String campo : campos) {
            JsonNode atual = node.get(campo);
            if (atual == null || atual.isNull()) {
                continue;
            }
            if (atual.isNumber()) {
                return atual.asDouble();
            }
            if (atual.isTextual()) {
                String normalizado = atual.asText().replace(",", ".").replaceAll("[^0-9.-]", "");
                if (!normalizado.isBlank()) {
                    return Double.parseDouble(normalizado);
                }
            }
        }
        return null;
    }

    private Long extrairLong(JsonNode node, String... campos) {
        for (String campo : campos) {
            JsonNode atual = node.get(campo);
            if (atual == null || atual.isNull()) {
                continue;
            }
            if (atual.isIntegralNumber()) {
                return atual.asLong();
            }
            if (atual.isTextual()) {
                String somenteDigitos = atual.asText().replaceAll("[^0-9-]", "");
                if (!somenteDigitos.isBlank()) {
                    return Long.parseLong(somenteDigitos);
                }
            }
        }
        return null;
    }

    private boolean extrairBoolean(JsonNode node, String... campos) {
        for (String campo : campos) {
            JsonNode atual = node.get(campo);
            if (atual == null || atual.isNull()) {
                continue;
            }
            if (atual.isBoolean()) {
                return atual.asBoolean();
            }
            if (atual.isTextual()) {
                String valor = atual.asText().trim().toLowerCase();
                if ("true".equals(valor) || "1".equals(valor) || "sim".equals(valor)) {
                    return true;
                }
                if ("false".equals(valor) || "0".equals(valor) || "nao".equals(valor) || "não".equals(valor)) {
                    return false;
                }
            }
        }
        return false;
    }
}
