package com.freelasatende.service.impl;

import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FreelancerBrInboxMapperTest {

    @Test
    void mapeiaListaRealDaInbox() {
        String html = """
            <div class="message-card" data-conversation-id="conv-25" data-timestamp="1787640574.458327">
              <div class="time-block"><time class="time">03:49</time></div>
              <div class="contact-block"><h4><a>Fabiano Lima</a></h4><span class="count">1</span></div>
              <div><a href="/account/inbox/conv-25-composite" class="card-link">
                <h4 class="card-title">Interesse no projeto `Mobile App`</h4>
                <p class="message-text">Oi, Empregador</p>
              </a></div>
            </div>
            """;

        Map<Long, String> chaves = new HashMap<>();
        List<ConversaResumo> conversas = FreelancerBrInboxMapper.listarConversas(html, chaves);

        assertEquals(1, conversas.size());
        assertEquals("Fabiano Lima", conversas.get(0).getNomeCliente());
        assertEquals("Mobile App", conversas.get(0).getNomeProjeto());
        assertFalse(conversas.get(0).getVisualizada());
        assertFalse(conversas.get(0).getReadOnly());
        assertEquals("conv-25-composite", chaves.get(conversas.get(0).getIdConversa()));
    }

    @Test
    void mapeiaMensagensEnviadasERecebidas() {
        String html = """
            <div class="messenger-page-wrap" data-user-id="eu" data-composite-id="conv-composite">
              <div data-message-id="m1" class="message-block user"><p class="text">Minha mensagem</p></div>
              <div data-message-id="m2" class="message-block client"><p class="text">Resposta do cliente</p></div>
            </div>
            """;

        long conversaId = FreelancerBrInboxMapper.idEstavel("conv-composite");
        List<MensagemConversa> mensagens = FreelancerBrInboxMapper.listarMensagens(html, conversaId);

        assertEquals(2, mensagens.size());
        assertTrue(mensagens.get(0).getEnviadaPorMim());
        assertFalse(mensagens.get(1).getEnviadaPorMim());
        assertEquals("Resposta do cliente", mensagens.get(1).getTexto());
    }
}
