package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.freelasatende.config.FreelancerConfig;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.enums.PlataformaFreela;

import java.util.ArrayList;
import java.util.List;

final class FreelancerInboxMapper {

    private FreelancerInboxMapper() {}

    static List<ConversaResumo> listarConversas(JsonNode root, FreelancerConfig config) {
        List<ConversaResumo> conversas = new ArrayList<>();
        JsonNode result = root.path("result");
        JsonNode users = result.path("users");
        if (result.isObject()) {
            result = result.path("threads");
        }
        if (!result.isArray()) {
            return conversas;
        }
        for (JsonNode bid : result) {
            long id = bid.path("id").asLong();
            if (id == 0) continue;
            JsonNode project = bid.has("project") ? bid.path("project") : bid.path("context");
            long projectId = project.path("id").asLong();
            String title = project.path("title").asText(bid.path("thread_name").asText(""));
            JsonNode latest = bid.has("message") ? bid.path("message") : bid.path("latest_message");
            String preview = latest.path("message").asText(bid.path("bidder_comment").asText(""));
            if (preview.isBlank()) {
                preview = bid.path("description").asText("");
            }
            ConversaResumo c = new ConversaResumo();
            c.setPlataforma(PlataformaFreela.FREELANCER);
            c.setIdConversa(id);
            c.setIdProjeto(projectId);
            c.setNomeProjeto(title.isBlank() ? "Conversa Freelancer" : title);
            c.setUltimaMensagem(preview.length() > 200 ? preview.substring(0, 200) : preview);
            long timestamp = latest.path("time_created").asLong(bid.path("time_created").asLong(0));
            c.setDataUltimaMensagemMillis(timestamp * 1000);
            c.setVisualizada(bid.path("is_read").asBoolean(bid.path("message_unread_count").asInt(0) == 0));
            c.setReadOnly(false);
            conversas.add(c);
        }
        return conversas;
    }

    static List<MensagemConversa> listarMensagens(JsonNode root, long projetoIdExterno) {
        List<MensagemConversa> mensagens = new ArrayList<>();
        JsonNode result = root.path("result");
        JsonNode users = result.path("users");
        if (result.isObject()) {
            result = result.path("messages");
        }
        if (!result.isArray()) {
            result = root.path("messages");
        }
        if (!result.isArray()) {
            return mensagens;
        }
        for (JsonNode msg : result) {
            long id = msg.path("id").asLong();
            String texto = msg.path("message").asText("");
            if (texto.isBlank()) {
                texto = msg.path("text").asText("");
            }
            long autorId = msg.path("from_user").asLong(0);
            String autor = users.path(String.valueOf(autorId)).path("username").asText("");
            if (autor.isBlank()) {
                autor = msg.path("from_user").path("username").asText("");
            }
            if (autor.isBlank()) {
                autor = msg.path("author").asText("");
            }
            long timestamp = msg.path("time_submitted").asLong();
            if (timestamp == 0) {
                timestamp = msg.path("created_at").asLong();
            }
            if (timestamp == 0) {
                timestamp = msg.path("time_created").asLong();
            }
            MensagemConversa m = MensagemConversa.builder()
                .plataforma(PlataformaFreela.FREELANCER)
                .id(id == 0 ? System.nanoTime() : id)
                .idConversa(projetoIdExterno)
                .idPessoa(autorId == 0 ? null : autorId)
                .nomePessoa(autor)
                .texto(texto)
                .dataCriacaoMillis(timestamp * 1000)
                .enviadaPorMim(false)
                .visualizada(true)
                .excluida(false)
                .sistema(false)
                .build();
            mensagens.add(m);
        }
        return mensagens;
    }

}
