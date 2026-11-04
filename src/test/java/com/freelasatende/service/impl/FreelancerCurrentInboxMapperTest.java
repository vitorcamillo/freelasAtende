package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.FreelancerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FreelancerCurrentInboxMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapeiaThreadsDaApiDeMensagens() throws Exception {
        var root = objectMapper.readTree("""
            {"result":{"threads":[{"id":81,"context":{"id":40670568,"type":"project"},
              "message":{"id":91,"message":"Hello Vitor","time_created":1787679000,"from_user":123},
              "is_read":false}],"users":{"123":{"username":"client-one"}}}}
            """);

        var conversas = FreelancerInboxMapper.listarConversas(root, new FreelancerConfig());

        assertEquals(1, conversas.size());
        assertEquals(81L, conversas.get(0).getIdConversa());
        assertEquals(40670568L, conversas.get(0).getIdProjeto());
        assertEquals("Hello Vitor", conversas.get(0).getUltimaMensagem());
        assertFalse(conversas.get(0).getVisualizada());
        assertFalse(conversas.get(0).getReadOnly());
    }

    @Test
    void mapeiaMensagensDaApiAtual() throws Exception {
        var root = objectMapper.readTree("""
            {"result":{"messages":[{"id":91,"thread_id":81,"message":"Hello Vitor",
              "time_created":1787679000,"from_user":123}],"users":{"123":{"username":"client-one"}}}}
            """);

        var mensagens = FreelancerInboxMapper.listarMensagens(root, 81L);

        assertEquals(1, mensagens.size());
        assertEquals("Hello Vitor", mensagens.get(0).getTexto());
        assertEquals("client-one", mensagens.get(0).getNomePessoa());
        assertEquals(1787679000000L, mensagens.get(0).getDataCriacaoMillis());
    }
}
