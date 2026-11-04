package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.freelasatende.config.UpworkConfig;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.enums.PlataformaFreela;

import java.util.ArrayList;
import java.util.List;

final class UpworkInboxMapper {

    private UpworkInboxMapper() {}

    static List<ConversaResumo> listarConversas(JsonNode root, UpworkConfig config) {
        List<ConversaResumo> conversas = new ArrayList<>();
        JsonNode items = root.path("rooms");
        if (!items.isArray()) {
            items = root.path("conversations");
        }
        if (!items.isArray()) {
            items = root.path("data");
        }
        for (JsonNode item : items) {
            String idStr = item.path("roomId").asText(item.path("id").asText(""));
            String key = item.path("key").asText("");
            JsonNode latest = item.has("latestStory") ? item.path("latestStory") : item.path("lastMessage");
            String preview = texto(latest);
            long timestamp = timestamp(latest);
            long id = idStr.isEmpty() ? timestamp : idEstavel(idStr);
            String subject = item.path("roomName").asText(item.path("subject").asText(""));
            if (subject.isBlank()) {
                subject = "Conversa Upwork";
            }
            Long projetoId = idEstavel(key.isBlank() ? idStr : key);
            ConversaResumo c = new ConversaResumo();
            c.setPlataforma(PlataformaFreela.UPWORK);
            c.setIdConversa(id);
            c.setIdProjeto(projetoId);
            c.setNomeProjeto(subject);
            c.setUltimaMensagem(preview);
            c.setDataUltimaMensagemMillis(timestamp > 0 ? timestamp : System.currentTimeMillis());
            c.setVisualizada(item.path("numUnread").asInt(0) == 0);
            c.setReadOnly(false);
            conversas.add(c);
        }
        return conversas;
    }

    static List<MensagemConversa> listarMensagens(JsonNode root, Long conversaId) {
        List<MensagemConversa> mensagens = new ArrayList<>();
        JsonNode items = root.path("stories");
        if (!items.isArray()) {
            items = root.path("messages");
        }
        if (!items.isArray()) {
            items = root.path("data");
        }
        for (JsonNode item : items) {
            String texto = texto(item);
            String autor = item.path("user").path("name").asText(item.path("author").path("name").asText(""));
            long timestamp = timestamp(item);
            String autorRole = item.path("author").path("role").asText("");
            boolean minha = item.path("isCurrentUser").asBoolean(false)
                || "freelancer".equalsIgnoreCase(autorRole);
            MensagemConversa m = MensagemConversa.builder()
                .plataforma(PlataformaFreela.UPWORK)
                .id(idEstavel(item.path("storyId").asText(item.path("id").asText(String.valueOf(timestamp)))))
                .idConversa(conversaId)
                .nomePessoa(autor)
                .texto(texto)
                .dataCriacaoMillis(timestamp)
                .enviadaPorMim(minha)
                .visualizada(true)
                .excluida(false)
                .sistema(false)
                .build();
            mensagens.add(m);
        }
        return mensagens;
    }

    private static String texto(JsonNode item) {
        String value = item.path("message").asText("");
        return value.isBlank() ? item.path("text").asText("") : value;
    }

    private static long timestamp(JsonNode item) {
        long value = item.path("created").asLong(0);
        if (value == 0) value = item.path("createdAt").asLong(0);
        return value > 0 && value < 10_000_000_000L ? value * 1000 : value;
    }

    static long idEstavel(String id) {
        if (id == null) return 0;
        try {
            return Long.parseLong(id.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return (long) id.hashCode();
        }
    }
}
