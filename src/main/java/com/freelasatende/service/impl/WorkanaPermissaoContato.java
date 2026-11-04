package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Resultado da consulta a /workers/permissions/make_bid_action, o mesmo endpoint que o
 * botao "Faca uma pergunta" do site chama antes de abrir o formulario.
 *
 * Quando a conta esgota as conexoes da semana a Workana responde HTTP 403 com um JSON de
 * plan wall: {"extraClasses":"modal-plan-wall no-connects","title":"Poxa, voce nao tem mais
 * conexoes disponiveis", ...}. Consultar isso antes de tentar o envio troca uma falha opaca
 * ("HTTP 400: corpo vazio", tres camadas depois) por uma causa acionavel.
 */
record WorkanaPermissaoContato(boolean permitido, boolean semConexoes, String motivo) {

    private static final String MARCADOR_SEM_CONEXOES = "no-connects";

    static WorkanaPermissaoContato permitida() {
        return new WorkanaPermissaoContato(true, false, "");
    }

    /**
     * A ausencia de corpo util nao pode virar bloqueio: se a Workana mudar o contrato, o
     * fluxo segue e quem decide continua sendo a resposta do envio.
     */
    static WorkanaPermissaoContato negada(boolean semConexoes, String motivo) {
        return new WorkanaPermissaoContato(false, semConexoes, motivo == null ? "" : motivo);
    }

    static WorkanaPermissaoContato analisar(int statusCode, String corpo, ObjectMapper objectMapper) {
        if (statusCode < 400) {
            return permitida();
        }
        // 404/410 significam rota ausente (ou contrato mudou), nao negacao de permissao.
        // Bloquear aqui transformaria uma mudanca da Workana em parada silenciosa do envio.
        if (statusCode == 404 || statusCode == 410) {
            return permitida();
        }
        if (corpo == null || corpo.isBlank()) {
            return negada(false, "Workana negou a permissao de contato (HTTP " + statusCode + ")");
        }
        try {
            JsonNode node = objectMapper.readTree(corpo);
            if (!node.isObject()) {
                return negada(false, "Workana negou a permissao de contato (HTTP " + statusCode + ")");
            }
            String extraClasses = texto(node, "extraClasses");
            String titulo = texto(node, "title");
            String subtitulo = texto(node, "subtitle");
            boolean semConexoes = extraClasses.contains(MARCADOR_SEM_CONEXOES);
            String motivo = titulo.isBlank() ? subtitulo : titulo;
            if (motivo.isBlank()) {
                motivo = "Workana negou a permissao de contato (HTTP " + statusCode + ")";
            }
            return negada(semConexoes, motivo);
        } catch (Exception e) {
            return negada(false, "Workana negou a permissao de contato (HTTP " + statusCode + ")");
        }
    }

    private static String texto(JsonNode node, String campo) {
        JsonNode valor = node.get(campo);
        return valor == null || valor.isNull() ? "" : valor.asText("");
    }
}
