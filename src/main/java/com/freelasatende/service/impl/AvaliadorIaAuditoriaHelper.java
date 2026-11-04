package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.dto.QualidadeRascunhoDTO;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class AvaliadorIaAuditoriaHelper {

    private AvaliadorIaAuditoriaHelper() {
    }

    static QualidadeRascunhoDTO parseAuditoriaRascunho(
        ObjectMapper objectMapper,
        String resposta,
        QualidadeRascunhoDTO base
    ) throws Exception {
        String json = extrairJsonObjeto(resposta);
        JsonNode node = objectMapper.readTree(json);

        boolean escalaZeroADez = usaEscalaZeroADez(node);
        int naturalidade = scoreBeneficio(node, "naturalidade", base.getScoreNaturalidade(), escalaZeroADez);
        int clareza = scoreBeneficio(node, "clareza", base.getScoreClareza(), escalaZeroADez);
        int risco = Math.max(scoreRisco(node, "risco", base.getScoreRisco(), escalaZeroADez), base.getScoreRisco());
        int duplicidade = Math.max(scoreRisco(node, "duplicidade", base.getScoreDuplicidade(), escalaZeroADez), base.getScoreDuplicidade());
        int confianca = scoreBeneficio(node, "confianca", base.getScoreConfianca(), escalaZeroADez);
        String decisao = decisaoAuditoria(node.path("decisao").asText(base.getDecisaoRecomendada()), naturalidade, clareza, risco, duplicidade, confianca);
        String comentario = node.path("comentario").asText(base.getComentario());

        return QualidadeRascunhoDTO.builder()
            .scoreNaturalidade(naturalidade)
            .scoreClareza(clareza)
            .scoreRisco(risco)
            .scoreDuplicidade(duplicidade)
            .scoreConfianca(confianca)
            .nivelConfianca(nivelAuditoria(decisao, confianca, risco))
            .decisaoRecomendada(decisao)
            .comentario(comentario == null || comentario.isBlank() ? base.getComentario() : comentario)
            .sinais(unirSinais(base.getSinais(), node.path("sinais")))
            .build();
    }

    static QualidadeRascunhoDTO combinarAuditoria(QualidadeRascunhoDTO base, QualidadeRascunhoDTO auditoria) {
        int naturalidade = Math.min(base.getScoreNaturalidade(), auditoria.getScoreNaturalidade());
        int clareza = Math.min(base.getScoreClareza(), auditoria.getScoreClareza());
        int risco = Math.max(base.getScoreRisco(), auditoria.getScoreRisco());
        int duplicidade = Math.max(base.getScoreDuplicidade(), auditoria.getScoreDuplicidade());
        int confianca = Math.min(base.getScoreConfianca(), auditoria.getScoreConfianca());
        String decisao = decisaoCombinada(base, auditoria);

        return QualidadeRascunhoDTO.builder()
            .scoreNaturalidade(naturalidade)
            .scoreClareza(clareza)
            .scoreRisco(risco)
            .scoreDuplicidade(duplicidade)
            .scoreConfianca(confianca)
            .nivelConfianca(nivelAuditoria(decisao, confianca, risco))
            .decisaoRecomendada(decisao)
            .comentario(auditoria.getComentario() != null ? auditoria.getComentario() : base.getComentario())
            .sinais(unirSinais(base.getSinais(), auditoria.getSinais()))
            .build();
    }

    private static String decisaoCombinada(QualidadeRascunhoDTO base, QualidadeRascunhoDTO auditoria) {
        if (base.bloqueante()) {
            return "BLOQUEAR";
        }
        if (auditoria.bloqueante() && auditoriaTemRiscoClaro(auditoria)) {
            return "BLOQUEAR";
        }
        if (auditoria.bloqueante()) {
            return "REESCREVER";
        }
        return piorDecisao(base.getDecisaoRecomendada(), auditoria.getDecisaoRecomendada());
    }

    private static boolean auditoriaTemRiscoClaro(QualidadeRascunhoDTO auditoria) {
        String comentario = normalizarAuditoria(auditoria.getComentario());
        if (temTermoRiscoAuditoria(comentario)) {
            return true;
        }

        List<String> sinais = auditoria.getSinais();
        boolean sinalRisco = sinais != null && sinais.stream()
            .map(AvaliadorIaAuditoriaHelper::normalizarAuditoria)
            .anyMatch(AvaliadorIaAuditoriaHelper::temTermoRiscoAuditoria);
        if (sinalRisco) {
            return true;
        }

        boolean scoreRiscoAlto = auditoria.getScoreRisco() != null && auditoria.getScoreRisco() >= 70;
        boolean scoreDuplicidadeAlto = auditoria.getScoreDuplicidade() != null && auditoria.getScoreDuplicidade() >= 80;
        if ((scoreRiscoAlto || scoreDuplicidadeAlto) && !pareceComentarioPositivo(comentario)) {
            return true;
        }

        return false;
    }

    private static boolean temTermoRiscoAuditoria(String texto) {
        if (texto == null || texto.isBlank()) {
            return false;
        }
        String comBordas = " " + texto + " ";
        return texto.contains("fora da plataforma")
            || texto.contains("contato fora")
            || texto.contains("whatsapp")
            || texto.contains("telefone")
            || texto.contains("email")
            || texto.contains("pagamento")
            || texto.contains("sistema")
            || comBordas.contains(" ia ")
            || texto.contains("bot")
            || texto.contains("automatic")
            || texto.contains("duplic")
            || texto.contains("teste")
            || texto.contains("validacao");
    }

    private static boolean pareceComentarioPositivo(String comentario) {
        if (comentario == null || comentario.isBlank()) {
            return false;
        }
        return comentario.contains("natural")
            || comentario.contains("humano")
            || comentario.contains("claro")
            || comentario.contains("clara")
            || comentario.contains("curto")
            || comentario.contains("curta")
            || comentario.contains("direto")
            || comentario.contains("direta")
            || comentario.contains("adequado")
            || comentario.contains("relevante")
            || comentario.contains("fluido")
            || comentario.contains("profissional");
    }

    private static String normalizarAuditoria(String texto) {
        if (texto == null) {
            return "";
        }
        return Normalizer.normalize(texto, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase()
            .replaceAll("[^a-z0-9]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static String extrairJsonObjeto(String resposta) {
        if (resposta == null) {
            throw new IllegalArgumentException("Auditoria IA vazia");
        }
        int inicio = resposta.indexOf('{');
        int fim = resposta.lastIndexOf('}');
        if (inicio < 0 || fim <= inicio) {
            throw new IllegalArgumentException("Auditoria IA nao retornou JSON");
        }
        return resposta.substring(inicio, fim + 1);
    }

    private static boolean usaEscalaZeroADez(JsonNode node) {
        List<Double> beneficios = List.of(
            valorNumerico(node, "naturalidade"),
            valorNumerico(node, "clareza"),
            valorNumerico(node, "confianca")
        ).stream().filter(v -> v >= 0).toList();

        return !beneficios.isEmpty()
            && beneficios.stream().allMatch(v -> v <= 10)
            && beneficios.stream().anyMatch(v -> v > 5);
    }

    private static double valorNumerico(JsonNode node, String campo) {
        return node.path(campo).isNumber() ? node.path(campo).asDouble() : -1;
    }

    private static int scoreBeneficio(JsonNode node, String campo, Integer fallback, boolean escalaZeroADez) {
        if (!node.path(campo).isNumber()) {
            return fallback != null ? fallback : 0;
        }
        double valor = node.path(campo).asDouble();
        if (valor > 0 && valor <= 1) {
            return fallback != null ? fallback : (int) Math.round(valor * 100);
        }
        if (valor > 1 && valor <= 5) {
            return clampScore((int) Math.round(valor * 20));
        }
        if (escalaZeroADez && valor > 5 && valor <= 10) {
            return clampScore((int) Math.round(valor * 10));
        }
        return clampScore((int) Math.round(valor));
    }

    private static int scoreRisco(JsonNode node, String campo, Integer fallback, boolean escalaZeroADez) {
        if (!node.path(campo).isNumber()) {
            return fallback != null ? fallback : 0;
        }
        double valor = node.path(campo).asDouble();
        if (valor > 1 && valor <= 5) {
            return clampScore((int) Math.round(valor * 20));
        }
        if (escalaZeroADez && valor > 5 && valor <= 10) {
            return clampScore((int) Math.round(valor * 10));
        }
        return clampScore((int) Math.round(valor));
    }

    private static int clampScore(int valor) {
        return Math.max(0, Math.min(100, valor));
    }

    private static String decisaoAuditoria(
        String decisao,
        int naturalidade,
        int clareza,
        int risco,
        int duplicidade,
        int confianca
    ) {
        String normalizada = decisao != null ? decisao.trim().toUpperCase() : "";
        if (risco >= 70 || duplicidade >= 80 || naturalidade < 40 || clareza < 35) {
            return "BLOQUEAR";
        }
        if ("BLOQUEAR".equals(normalizada) || "REESCREVER".equals(normalizada) || "APROVAR".equals(normalizada)) {
            return normalizada;
        }
        if (confianca < 78 || risco >= 30 || naturalidade < 75 || clareza < 75) {
            return "REESCREVER";
        }
        return "APROVAR";
    }

    private static String piorDecisao(String primeira, String segunda) {
        if ("BLOQUEAR".equalsIgnoreCase(primeira) || "BLOQUEAR".equalsIgnoreCase(segunda)) {
            return "BLOQUEAR";
        }
        if ("REESCREVER".equalsIgnoreCase(primeira) || "REESCREVER".equalsIgnoreCase(segunda)) {
            return "REESCREVER";
        }
        return "APROVAR";
    }

    private static String nivelAuditoria(String decisao, int confianca, int risco) {
        if ("BLOQUEAR".equals(decisao)) {
            return "BLOQUEADA";
        }
        if (confianca >= 82 && risco < 25) {
            return "ALTA";
        }
        return "MEDIA";
    }

    private static List<String> unirSinais(List<String> primeiros, JsonNode sinaisNode) {
        List<String> sinais = new ArrayList<>();
        if (primeiros != null) {
            sinais.addAll(primeiros);
        }
        if (sinaisNode != null && sinaisNode.isArray()) {
            for (JsonNode sinal : sinaisNode) {
                String valor = sinal.asText("");
                if (!valor.isBlank()) {
                    sinais.add(valor.trim());
                }
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(sinais));
    }

    private static List<String> unirSinais(List<String> primeiros, List<String> segundos) {
        List<String> sinais = new ArrayList<>();
        if (primeiros != null) {
            sinais.addAll(primeiros);
        }
        if (segundos != null) {
            sinais.addAll(segundos);
        }
        return new ArrayList<>(new LinkedHashSet<>(sinais));
    }
}
