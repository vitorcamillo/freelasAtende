package com.freelasatende.service.impl;

import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.enums.PlataformaFreela;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class FreelancerBrInboxMapper {

    private FreelancerBrInboxMapper() {
    }

    static List<ConversaResumo> listarConversas(String html, Map<Long, String> chaves) {
        Document doc = Jsoup.parse(html == null ? "" : html);
        List<ConversaResumo> resultado = new ArrayList<>();
        for (Element card : doc.select(".message-card[data-conversation-id]")) {
            Element link = card.selectFirst("a[href*=/account/inbox/]");
            if (link == null) continue;
            String composite = link.attr("href").replaceFirst("^.*/account/inbox/", "");
            if (composite.isBlank()) continue;
            long id = idEstavel(composite);
            chaves.put(id, composite);
            String assunto = texto(card.selectFirst(".message-text"));
            String titulo = texto(link.selectFirst(".card-title"));
            resultado.add(ConversaResumo.builder()
                .plataforma(PlataformaFreela.FREELANCER_BR)
                .idConversa(id)
                .nomeProjeto(extrairProjeto(titulo))
                .nomeCliente(texto(card.selectFirst(".contact-block .card-title, .contact-block h4")))
                .ultimaMensagem(assunto)
                .dataUltimaMensagemMillis(segundosParaMillis(card.attr("data-timestamp")))
                .visualizada(card.selectFirst(".count") == null)
                .readOnly(false)
                .build());
        }
        return resultado;
    }

    static List<MensagemConversa> listarMensagens(String html, long conversaId) {
        Document doc = Jsoup.parse(html == null ? "" : html);
        List<MensagemConversa> resultado = new ArrayList<>();
        for (Element bloco : doc.select(".message-block[data-message-id]")) {
            String texto = texto(bloco.selectFirst(".message .text, .text"));
            if (texto.isBlank()) continue;
            resultado.add(MensagemConversa.builder()
                .plataforma(PlataformaFreela.FREELANCER_BR)
                .id(idEstavel(bloco.attr("data-message-id")))
                .idConversa(conversaId)
                .texto(texto)
                .enviadaPorMim(bloco.hasClass("user"))
                .visualizada(true)
                .excluida(false)
                .sistema(bloco.hasClass("system"))
                .build());
        }
        return resultado;
    }

    static long idEstavel(String valor) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.UTF_8));
            long id = 0;
            for (int i = 0; i < Long.BYTES; i++) id = (id << 8) | (hash[i] & 0xffL);
            return id & Long.MAX_VALUE;
        } catch (Exception e) {
            return Integer.toUnsignedLong(valor.hashCode());
        }
    }

    private static String extrairProjeto(String titulo) {
        int inicio = titulo.indexOf('`');
        int fim = titulo.lastIndexOf('`');
        return inicio >= 0 && fim > inicio ? titulo.substring(inicio + 1, fim) : titulo;
    }

    private static long segundosParaMillis(String valor) {
        try {
            return (long) (Double.parseDouble(valor) * 1000);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String texto(Element element) {
        return element == null ? "" : element.text().replaceAll("\\s+", " ").trim();
    }
}
