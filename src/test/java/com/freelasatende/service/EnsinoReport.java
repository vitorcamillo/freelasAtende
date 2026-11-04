package com.freelasatende.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class EnsinoReport {

    private EnsinoReport() {
    }

    static void escrever(Path diretorio, String titulo, List<Row> rows) throws IOException {
        Files.createDirectories(diretorio);
        Path index = diretorio.resolve("index.html");
        Files.writeString(index, html(titulo, rows));
    }

    private static String html(String titulo, List<Row> rows) {
        int lidos = contar(rows, Row::lido);
        int perguntou = contar(rows, Row::perguntouAoDono);
        int ensinou = contar(rows, Row::ensinou);
        int respondeu = contar(rows, Row::respondeu);
        int bloqueou = contar(rows, Row::bloqueou);
        int vazou = contar(rows, Row::vazouContexto);
        int pendentes = contar(rows, Row::pendente);

        StringBuilder sb = new StringBuilder();
        sb.append("""
            <!doctype html>
            <html lang="pt-BR">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>""").append(escape(titulo)).append("""
            </title>
              <style>
                :root { color-scheme: light; font-family: Inter, Arial, sans-serif; }
                body { margin: 0; background: #f6f7f9; color: #19202a; }
                main { max-width: 1180px; margin: 0 auto; padding: 28px; }
                h1 { font-size: 26px; margin: 0 0 18px; }
                h2 { font-size: 18px; margin: 0 0 12px; }
                .summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(135px, 1fr)); gap: 10px; margin-bottom: 18px; }
                .metric { background: #fff; border: 1px solid #d9dee7; border-radius: 8px; padding: 12px; }
                .metric strong { display: block; font-size: 24px; }
                .metric span { color: #596579; font-size: 12px; text-transform: uppercase; letter-spacing: .04em; }
                .scenario { background: #fff; border: 1px solid #d9dee7; border-radius: 8px; padding: 16px; margin: 12px 0; }
                .flags { display: flex; flex-wrap: wrap; gap: 6px; margin: 8px 0 12px; }
                .flag { border-radius: 999px; padding: 4px 9px; font-size: 12px; border: 1px solid #c9d1dd; background: #f8fafc; }
                .ok { border-color: #86c58d; background: #effaf0; }
                .warn { border-color: #e0b85d; background: #fff8e6; }
                .bad { border-color: #df7b7b; background: #fff0f0; }
                dl { display: grid; grid-template-columns: minmax(130px, 210px) 1fr; gap: 8px 12px; margin: 0; }
                dt { color: #596579; font-weight: 700; }
                dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
                code { background: #eef1f5; padding: 2px 4px; border-radius: 4px; }
              </style>
            </head>
            <body>
            <main>
            """);
        sb.append("<h1>").append(escape(titulo)).append("</h1>\n");
        sb.append("<section class=\"summary\">")
            .append(metric("lido", lidos))
            .append(metric("perguntou ao dono", perguntou))
            .append(metric("ensinou", ensinou))
            .append(metric("respondeu", respondeu))
            .append(metric("bloqueou", bloqueou))
            .append(metric("vazou contexto", vazou))
            .append(metric("pendente", pendentes))
            .append("</section>\n");

        for (Row row : rows) {
            sb.append("<article class=\"scenario\">\n")
                .append("<h2>").append(escape(row.cenario())).append("</h2>\n")
                .append("<div class=\"flags\">")
                .append(flag("lido", row.lido(), false))
                .append(flag("perguntou", row.perguntouAoDono(), false))
                .append(flag("ensinou", row.ensinou(), false))
                .append(flag("respondeu", row.respondeu(), false))
                .append(flag("bloqueou", row.bloqueou(), false))
                .append(flag("vazou contexto", row.vazouContexto(), true))
                .append(flag("pendente", row.pendente(), false))
                .append("</div>\n")
                .append("<dl>")
                .append(campo("Status", row.status()))
                .append(campo("Mensagem lida", row.mensagemLida()))
                .append(campo("Historico", row.historico()))
                .append(campo("Analise", row.analise()))
                .append(campo("Pergunta ao dono", row.perguntaAoDono()))
                .append(campo("Ensino", row.ensino()))
                .append(campo("Rascunho", row.rascunho()))
                .append(campo("Observacao", row.observacao()))
                .append("</dl>\n")
                .append("</article>\n");
        }

        sb.append("</main></body></html>\n");
        return sb.toString();
    }

    private static int contar(List<Row> rows, FlagExtractor extractor) {
        return (int) rows.stream().filter(extractor::value).count();
    }

    private static String metric(String label, int value) {
        return "<div class=\"metric\"><strong>" + value + "</strong><span>" + escape(label) + "</span></div>";
    }

    private static String flag(String label, boolean active, boolean inverted) {
        String classe;
        if (active && inverted) {
            classe = "bad";
        } else if (active) {
            classe = "ok";
        } else {
            classe = inverted ? "ok" : "warn";
        }
        return "<span class=\"flag " + classe + "\">" + escape(label) + ": " + (active ? "sim" : "nao") + "</span>";
    }

    private static String campo(String label, String value) {
        return "<dt>" + escape(label) + "</dt><dd>" + escape(value) + "</dd>";
    }

    private static String escape(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private interface FlagExtractor {
        boolean value(Row row);
    }

    record Row(
        String cenario,
        boolean lido,
        boolean perguntouAoDono,
        boolean ensinou,
        boolean respondeu,
        boolean bloqueou,
        boolean vazouContexto,
        boolean pendente,
        String status,
        String mensagemLida,
        String historico,
        String analise,
        String perguntaAoDono,
        String ensino,
        String rascunho,
        String observacao
    ) {
    }
}
