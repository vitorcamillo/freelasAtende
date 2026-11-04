package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusProjeto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

final class UpworkNuxtJobParser {

    private static final String JOBS_MARKER = "feedBestMatch:{jobs:";
    private static final ObjectMapper JSON = new ObjectMapper();

    private UpworkNuxtJobParser() {
    }

    static List<Projeto> parse(String html, String urlBase) {
        String jobs = extrairArrayJobs(html);
        if (jobs.isBlank()) {
            return List.of();
        }
        List<Projeto> projetos = new ArrayList<>();
        int cursor = 0;
        while ((cursor = jobs.indexOf("{uid:\"", cursor)) >= 0) {
            int proximo = jobs.indexOf("{uid:\"", cursor + 1);
            int limite = proximo >= 0 ? proximo : jobs.length();
            String titulo = campoLiteral(jobs, cursor, limite, "title");
            String ciphertext = campoLiteral(jobs, cursor, limite, "ciphertext");
            if (titulo.isBlank() || ciphertext.isBlank()) {
                cursor++;
                continue;
            }
            long idExterno = Integer.toUnsignedLong(ciphertext.hashCode());
            projetos.add(Projeto.builder()
                .plataforma(PlataformaFreela.UPWORK)
                .id(PlataformaFreela.UPWORK.idInterno(idExterno))
                .idExterno(idExterno)
                .titulo(titulo)
                .descricao(campoLiteral(jobs, cursor, limite, "description"))
                .urlProjeto(urlBase + "/jobs/" + ciphertext + "/")
                .dataCriacao(LocalDateTime.now())
                .status(StatusProjeto.NOVO)
                .foiProcessado(false)
                .build());
            cursor = limite;
        }
        return projetos;
    }

    private static String campoLiteral(String source, int inicio, int limite, String campo) {
        String marcador = campo + ":\"";
        int valorInicio = source.indexOf(marcador, inicio);
        if (valorInicio < 0 || valorInicio >= limite) {
            return "";
        }
        valorInicio += marcador.length();
        boolean escape = false;
        for (int i = valorInicio; i < limite; i++) {
            char atual = source.charAt(i);
            if (escape) {
                escape = false;
            } else if (atual == '\\') {
                escape = true;
            } else if (atual == '"') {
                return desescapar(source.substring(valorInicio, i));
            }
        }
        return "";
    }

    private static String extrairArrayJobs(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        int marker = html.indexOf(JOBS_MARKER);
        if (marker < 0) {
            return "";
        }
        int inicio = html.indexOf('[', marker + JOBS_MARKER.length());
        if (inicio < 0) {
            return "";
        }
        int profundidade = 0;
        char aspas = 0;
        boolean escape = false;
        for (int i = inicio; i < html.length(); i++) {
            char atual = html.charAt(i);
            if (aspas != 0) {
                if (escape) {
                    escape = false;
                } else if (atual == '\\') {
                    escape = true;
                } else if (atual == aspas) {
                    aspas = 0;
                }
                continue;
            }
            if (atual == '\'' || atual == '"') {
                aspas = atual;
            } else if (atual == '[') {
                profundidade++;
            } else if (atual == ']' && --profundidade == 0) {
                return html.substring(inicio, i + 1);
            }
        }
        return "";
    }

    private static String desescapar(String valor) {
        try {
            return JSON.readValue("\"" + valor + "\"", String.class);
        } catch (Exception ignored) {
            return valor;
        }
    }
}
