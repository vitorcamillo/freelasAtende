package com.freelasatende.service.impl;

import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@SpringBootTest(properties = {
    "scheduling.enabled=false",
    "marketplaces.enabled=WORKANA"
})
@EnabledIfSystemProperty(named = "test", matches = ".*WorkanaRealReadIT.*")
@DisplayName("Workana real: leitura pelo adapter")
class WorkanaRealReadIT {

    @Autowired
    private MarketplaceAdapterRegistry registry;

    @Test
    @DisplayName("Valida discovery, inbox e mensagens sem enviar nada")
    void validaDiscoveryInboxEMensagensSemEnviar() {
        MarketplaceAdapter adapter = registry.para(PlataformaFreela.WORKANA);

        assertTrue(adapter.verificarLogin(), "Workana discovery/health deveria responder.");

        List<Projeto> projetos = adapter.buscarProjetosNovos();
        assertFalse(projetos.isEmpty(), "Workana deveria retornar projetos no discovery.");
        System.out.printf(
            "Workana discovery: projetos=%s primeiro=%s%n",
            projetos.size(),
            projetos.get(0).getTitulo()
        );

        List<ConversaResumo> conversas = adapter.listarConversasInbox();
        assertNotNull(conversas);
        System.out.printf("Workana inbox: conversas=%s%n", conversas.size());
        assumeFalse(conversas.isEmpty(), "Workana inbox sem conversas para testar mensagens.");

        for (ConversaResumo conversa : conversas) {
            List<MensagemConversa> mensagens = adapter.listarMensagensConversa(conversa.getIdConversa());
            assertNotNull(mensagens, "Mensagens nao deveriam ser nulas para conversa " + conversa.getIdConversa());
            System.out.printf(
                "Workana conversa %s | projeto=%s | mensagens=%s%n",
                conversa.getIdConversa(),
                conversa.getNomeProjeto(),
                mensagens.size()
            );
        }
    }
}
