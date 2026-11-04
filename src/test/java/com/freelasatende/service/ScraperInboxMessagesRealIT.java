package com.freelasatende.service;

import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import com.freelasatende.service.interfaceServico.ScraperService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest(properties = "scheduling.enabled=false")
@EnabledIfSystemProperty(named = "test", matches = ".*ScraperInboxMessagesRealIT.*")
@DisplayName("99Freelas real: leitura de mensagens da inbox")
class ScraperInboxMessagesRealIT {

    @Autowired
    private ScraperService scraperService;

    @Autowired
    private AutenticacaoService autenticacaoService;

    @BeforeEach
    void setup() {
        autenticacaoService.carregarCookies();
        assumeTrue(scraperService.verificarLogin(), "Sessao real do 99Freelas invalida.");
    }

    @Test
    @DisplayName("Lista mensagens das primeiras conversas sem marcar como visualizada")
    void listaMensagensDasPrimeirasConversasSemVisualizar() {
        List<ConversaResumo> conversas = scraperService.listarConversasInbox();
        assertNotNull(conversas);
        assumeFalse(conversas.isEmpty(), "Inbox sem conversas para testar leitura de mensagens.");

        int limite = Math.min(5, conversas.size());
        for (ConversaResumo conversa : conversas.subList(0, limite)) {
            Long conversaId = conversa.getIdConversa();
            List<MensagemConversa> mensagens = scraperService.listarMensagensConversa(conversaId, false);
            assertNotNull(mensagens, "Mensagens nao deveriam ser nulas para conversa " + conversaId);
            System.out.printf(
                "Conversa %s | projeto=%s | mensagens=%s%n",
                conversaId,
                conversa.getNomeProjeto(),
                mensagens.size()
            );
        }
    }
}
