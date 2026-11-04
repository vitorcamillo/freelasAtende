package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.dto.InboxAuditoriaDTO;
import com.freelasatende.model.dto.InboxConversaAuditoriaDTO;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
final class InboxAuditoriaReportWriter {

    static final Path RELATORIO_INBOX_DIR = Path.of("target", "copiloto-seguro-inbox");
    static final Path RELATORIO_INBOX_HTML = RELATORIO_INBOX_DIR.resolve("index.html");
    static final Path RELATORIO_INBOX_JSON = RELATORIO_INBOX_DIR.resolve("auditoria.json");
    private static final Path RELATORIO_INBOX_RESUMO = RELATORIO_INBOX_DIR.resolve("resumo.json");

    private final ObjectMapper objectMapper;

    InboxAuditoriaReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    static String relatorioHtmlPath() {
        return RELATORIO_INBOX_HTML.toString();
    }

    static String relatorioJsonPath() {
        return RELATORIO_INBOX_JSON.toString();
    }

    void escrever(InboxAuditoriaDTO auditoria) {
        try {
            Files.createDirectories(RELATORIO_INBOX_DIR);
            Files.writeString(
                RELATORIO_INBOX_JSON,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(auditoria),
                StandardCharsets.UTF_8
            );
            Files.writeString(
                RELATORIO_INBOX_RESUMO,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resumoRelatorio(auditoria)),
                StandardCharsets.UTF_8
            );
            Files.writeString(RELATORIO_INBOX_HTML, htmlRelatorio(auditoria), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Falha ao gerar relatorio local da auditoria de inbox: {}", e.getMessage());
        }
    }

    private Map<String, Object> resumoRelatorio(InboxAuditoriaDTO auditoria) {
        Map<String, Object> resumo = new LinkedHashMap<>();
        resumo.put("auditoriaId", auditoria.getAuditoriaId());
        resumo.put("data", auditoria.getData());
        resumo.put("persistida", auditoria.isPersistida());
        resumo.put("totalConversas", auditoria.getTotalConversas());
        resumo.put("totalMensagens", auditoria.getTotalMensagens());
        resumo.put("totalAcionaveis", auditoria.getTotalAcionaveis());
        resumo.put("totalPendenciasExistentes", auditoria.getTotalPendenciasExistentes());
        resumo.put("zeroEnvioReal", !auditoria.isEscritaRealHabilitada());
        resumo.put("painelCenarios", auditoria.getPainelCenarios());
        return resumo;
    }

    private String htmlRelatorio(InboxAuditoriaDTO auditoria) {
        StringBuilder html = new StringBuilder();
        html.append("""
            <!DOCTYPE html>
            <html lang="pt-BR">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Auditoria Inbox - Copiloto Seguro</title>
              <style>
                body{font-family:Arial,sans-serif;background:#081018;color:#e5edf7;margin:0;padding:24px}
                h1,h2{margin:0 0 10px} .muted{color:#93a4b7}
                .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:10px;margin:16px 0}
                .card{border:1px solid #26384d;border-radius:8px;background:#111d29;padding:12px}
                .card strong{display:block;font-size:24px;margin-top:4px}.ok{color:#31c27c}.warn{color:#f6c453}.bad{color:#f87171}
                .row{border-top:1px solid #26384d;padding:14px 0}.tag{display:inline-block;border:1px solid #31445a;border-radius:999px;padding:3px 8px;margin:0 4px 6px 0;color:#cbd5e1}
                pre{white-space:pre-wrap;background:#0b1722;border:1px solid #26384d;border-radius:8px;padding:10px}
              </style>
            </head>
            <body>
            """);
        html.append("<h1>Auditoria Inbox - Copiloto Seguro</h1>");
        html.append("<p class=\"muted\">Gerado em ").append(escapeHtml(String.valueOf(auditoria.getData()))).append("</p>");
        html.append("<div class=\"grid\">");
        card(html, "Conversas lidas", auditoria.getTotalConversas(), "ok");
        card(html, "Mensagens lidas", auditoria.getTotalMensagens(), "ok");
        card(html, "Acionaveis", auditoria.getTotalAcionaveis(), "warn");
        card(html, "Pendentes", auditoria.getTotalPendenciasExistentes(), "warn");
        card(html, "Envio real", auditoria.isEscritaRealHabilitada() ? "habilitado" : "travado", auditoria.isEscritaRealHabilitada() ? "bad" : "ok");
        html.append("</div>");
        html.append("<h2>Painel por cenario</h2><div class=\"grid\">");
        Map<String, Object> painel = auditoria.getPainelCenarios() == null ? Map.of() : auditoria.getPainelCenarios();
        painel.forEach((chave, valor) -> card(html, chave, valor, "warn"));
        html.append("</div>");
        html.append("<h2>Conversas</h2>");
        for (InboxConversaAuditoriaDTO conversa : auditoria.getConversas()) {
            html.append("<section class=\"row\">");
            html.append("<span class=\"tag\">").append(escapeHtml(conversa.getStatus())).append("</span>");
            html.append("<span class=\"tag\">conversa #").append(escapeHtml(conversa.getConversaId())).append("</span>");
            html.append("<span class=\"tag\">").append(escapeHtml(conversa.getTotalMensagens())).append(" msgs</span>");
            html.append("<h3>").append(escapeHtml(conversa.getTituloProjeto())).append("</h3>");
            html.append("<p>").append(escapeHtml(conversa.getAnaliseOperacional())).append("</p>");
            blocoHtml(html, "Mensagem lida", conversa.getUltimaMensagemTexto());
            blocoHtml(html, "Decisao IA", conversa.getDecisaoIa());
            blocoHtml(html, "Pergunta ao dono", conversa.getPerguntaParaUsuario());
            blocoHtml(html, "Resposta pendente", conversa.getRascunhoPendente());
            blocoHtml(html, "Qualidade", conversa.getComentarioQualidade());
            if (conversa.getDuvidasInternas() != null && !conversa.getDuvidasInternas().isEmpty()) {
                blocoHtml(html, "Duvidas internas", String.join("\n", conversa.getDuvidasInternas()));
            }
            html.append("</section>");
        }
        html.append("</body></html>");
        return html.toString();
    }

    private void card(StringBuilder html, String titulo, Object valor, String classe) {
        html.append("<div class=\"card\"><span class=\"muted\">")
            .append(escapeHtml(titulo))
            .append("</span><strong class=\"")
            .append(classe)
            .append("\">")
            .append(escapeHtml(valor))
            .append("</strong></div>");
    }

    private void blocoHtml(StringBuilder html, String titulo, String valor) {
        if (valor == null || valor.isBlank()) {
            return;
        }
        html.append("<p class=\"muted\">").append(escapeHtml(titulo)).append("</p><pre>")
            .append(escapeHtml(valor))
            .append("</pre>");
    }

    private String escapeHtml(Object valor) {
        String texto = String.valueOf(valor == null ? "" : valor);
        return texto
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#039;");
    }
}
