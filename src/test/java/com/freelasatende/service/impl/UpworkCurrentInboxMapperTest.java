package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.UpworkConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UpworkCurrentInboxMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapeiaRoomsDaApiV3() throws Exception {
        var root = objectMapper.readTree("""
            {"rooms":[{"roomId":"room-abc","roomName":"Architecture review",
              "numUnread":2,"latestStory":{"storyId":"story-9","message":"Can we discuss?","created":1787679000000}}]}
            """);

        var conversas = UpworkInboxMapper.listarConversas(root, new UpworkConfig());

        assertEquals(1, conversas.size());
        assertEquals("Architecture review", conversas.get(0).getNomeProjeto());
        assertEquals("Can we discuss?", conversas.get(0).getUltimaMensagem());
        assertFalse(conversas.get(0).getVisualizada());
        assertFalse(conversas.get(0).getReadOnly());
    }

    @Test
    void mapeiaStoriesSimplified() throws Exception {
        var root = objectMapper.readTree("""
            {"stories":[{"storyId":"story-9","message":"Can we discuss?",
              "created":1787679000000,"user":{"name":"Client"},"isCurrentUser":false}]}
            """);

        var mensagens = UpworkInboxMapper.listarMensagens(root, 77L);

        assertEquals(1, mensagens.size());
        assertEquals("Can we discuss?", mensagens.get(0).getTexto());
        assertEquals("Client", mensagens.get(0).getNomePessoa());
        assertFalse(mensagens.get(0).getEnviadaPorMim());
    }
}
