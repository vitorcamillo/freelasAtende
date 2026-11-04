package com.freelasatende.service;

import com.freelasatende.model.entity.Projeto;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.IaProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
    "scheduling.enabled=false",
    "ia.provider=openai",
    "ia.api-key=",
    "ia.fallback-when-missing-key=true"
})
@ActiveProfiles("test")
@DisplayName("AvaliadorIaService: sem API key nao gera texto hardcoded")
class AvaliadorIaMissingKeyFallbackTest {

    @Autowired
    private AvaliadorIaService avaliadorIaService;

    @MockBean
    private IaProvider iaProvider;

    @Test
    @DisplayName("Resposta de conversa sem API key falha sem chamar provider remoto")
    void respostaConversaSemApiKeyFalhaSemChamarProviderRemoto() throws Exception {
        assertThrows(IllegalStateException.class, () -> avaliadorIaService.sugerirRespostaConversa(
            Projeto.builder().id(740696L).titulo("Integracao Telegram").build(),
            "Cliente pediu desconto.",
            "Consegue fazer um preco melhor?"
        ));

        verify(iaProvider, never()).enviar(anyString(), anyInt());
    }

    @Test
    @DisplayName("Gatilho ainda nao sem API key falha sem template local")
    void gatilhoAindaNaoSemApiKeyFalhaSemTemplateLocal() throws Exception {
        assertThrows(IllegalStateException.class, () -> avaliadorIaService.sugerirRespostaConversa(
            Projeto.builder().id(740697L).titulo("Servidor Metin2").build(),
            "Freelancer: ja foi atendido?\nCliente: Ainda não",
            "Ainda não"
        ));

        verify(iaProvider, never()).enviar(anyString(), anyInt());
    }

    @Test
    @DisplayName("Reescrita com orientação sem API key falha sem chamar provider remoto")
    void reescritaComOrientacaoSemApiKeyFalhaSemChamarProviderRemoto() throws Exception {
        String rascunho = "Vou revisar e te retorno por aqui.";

        assertThrows(IllegalStateException.class, () -> avaliadorIaService.reescreverRascunhoComOrientacao(
            "Mensagem do cliente que disparou a resposta:\nAinda não\n",
            rascunho,
            "Nao prometa desconto sem orcamento alvo."
        ));

        verify(iaProvider, never()).enviar(anyString(), anyInt());
    }
}
