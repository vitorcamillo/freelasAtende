package com.freelasatende.service.impl;

import com.freelasatende.config.IaConfig;
import com.freelasatende.model.dto.QualidadeRascunhoDTO;
import com.freelasatende.service.interfaceServico.IaProvider;
import com.freelasatende.service.interfaceServico.QualidadeRascunhoService;
import com.freelasatende.util.IaSafetyPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cobre o descarte de raciocinio interno em gerarRespostaValida: prosa de
 * pensamento gera nova tentativa, bloco think e removido e raciocinio
 * persistente estoura IllegalStateException.
 */
@DisplayName("AvaliadorIaRespostaConversaHelper: descarte de raciocinio interno")
class AvaliadorIaRespostaConversaHelperRaciocinioTest {

    private static final class FilaProvider implements IaProvider {
        private final Queue<String> respostas;
        private final List<String> prompts = new ArrayList<>();

        FilaProvider(List<String> respostas) {
            this.respostas = new ArrayDeque<>(respostas);
        }

        @Override
        public String enviar(String prompt, int maxTokens) {
            prompts.add(prompt);
            return respostas.poll();
        }

        @Override
        public String getNome() {
            return "fila-teste";
        }

        @Override
        public String getModelo() {
            return "modelo-teste";
        }

        @Override
        public boolean estaDisponivel() {
            return true;
        }

        List<String> getPrompts() {
            return prompts;
        }
    }

    private QualidadeRascunhoService qualidadeAprovando() {
        QualidadeRascunhoService qualidade = mock(QualidadeRascunhoService.class);
        QualidadeRascunhoDTO aprovado = QualidadeRascunhoDTO.builder()
            .decisaoRecomendada("APROVAR")
            .build();
        when(qualidade.avaliar(anyString())).thenReturn(aprovado);
        when(qualidade.avaliar(anyString(), anyString(), anyString())).thenReturn(aprovado);
        return qualidade;
    }

    private IaConfig configUmaTentativaRetry() {
        IaConfig config = new IaConfig();
        config.setMaxRetries(1);
        return config;
    }

    @Test
    @DisplayName("Prosa de raciocinio descarta tentativa e retorna a proxima resposta")
    void prosaDeRaciocinioGeraNovaTentativa() throws Exception {
        FilaProvider provider = new FilaProvider(List.of(
            "Okay, the client is asking about price. We need to reply in Portuguese.",
            "Oi! Consigo fazer o app de assinatura pelo celular. Voce ja tem o layout pronto?"
        ));

        String resultado = AvaliadorIaRespostaConversaHelper.gerarRespostaValida(
            provider,
            configUmaTentativaRetry(),
            new IaSafetyPolicy(),
            qualidadeAprovando(),
            null,
            "prompt base"
        );

        assertEquals(
            "Oi! Consigo fazer o app de assinatura pelo celular. Voce ja tem o layout pronto?",
            resultado
        );
        assertEquals(2, provider.getPrompts().size());
        assertTrue(provider.getPrompts().get(1).contains("A tentativa anterior foi descartada"));
    }

    @Test
    @DisplayName("Bloco think fechado e removido e a mensagem final e retornada")
    void blocoThinkFechadoRetornaMensagemFinal() throws Exception {
        FilaProvider provider = new FilaProvider(List.of(
            "<think>analisando o pedido</think>Oi! Consigo fazer o app pelo celular. Ja tem layout?"
        ));

        String resultado = AvaliadorIaRespostaConversaHelper.gerarRespostaValida(
            provider,
            configUmaTentativaRetry(),
            new IaSafetyPolicy(),
            qualidadeAprovando(),
            null,
            "prompt base"
        );

        assertEquals("Oi! Consigo fazer o app pelo celular. Ja tem layout?", resultado);
        assertEquals(1, provider.getPrompts().size());
    }

    @Test
    @DisplayName("Raciocinio persistente estoura IllegalStateException")
    void raciocinioPersistenteEstouraExcecao() {
        FilaProvider provider = new FilaProvider(List.of(
            "We need to reply to the user.",
            "We need to reply to the user.",
            "We need to reply to the user.",
            "We need to reply to the user.",
            "We need to reply to the user."
        ));

        IllegalStateException erro = assertThrows(
            IllegalStateException.class,
            () -> AvaliadorIaRespostaConversaHelper.gerarRespostaValida(
                provider,
                configUmaTentativaRetry(),
                new IaSafetyPolicy(),
                qualidadeAprovando(),
                null,
                "prompt base"
            )
        );
        assertTrue(
            erro.getMessage().startsWith("IA gerou resposta de conversa com raciocinio interno"),
            "Mensagem inesperada: " + erro.getMessage()
        );
        assertEquals(5, provider.getPrompts().size());
    }
}
