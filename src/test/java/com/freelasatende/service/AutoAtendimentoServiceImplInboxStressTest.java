package com.freelasatende.service;

import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.StatusAcaoPendente;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoAtendimentoServiceImplInboxStressTest extends AutoAtendimentoServiceImplTestSupport {

    @Test
    @DisplayName("Stress: várias conversas com fatos conflitantes mantêm contexto isolado")
    void stressVariasConversasComFatosConflitantesMantemContextoIsolado() {
        int totalConversas = 12;
        List<ConversaResumo> conversas = new java.util.ArrayList<>();
        for (int i = 0; i < totalConversas; i++) {
            long conversaId = 9500L + i;
            conversas.add(ConversaResumo.builder()
                .idConversa(conversaId)
                .idProjeto(9600L + i)
                .nomeProjeto("Projeto isolado " + i)
                .nomeCliente("Cliente isolado " + i)
                .idCliente(800L + i)
                .idFreelancer(269409L)
                .build());
        }
        when(scraperService.listarConversasInbox()).thenReturn(conversas);
        when(scraperService.listarMensagensConversa(anyLong()))
            .thenAnswer(invocation -> {
                Long conversaId = invocation.getArgument(0);
                int indice = (int) (conversaId - 9500L);
                String codigo = String.format("CTX-ISO-%02d", indice);
                return List.of(
                    MensagemConversa.builder()
                        .id(conversaId * 10)
                        .idConversa(conversaId)
                        .idPessoa(269409L)
                        .nomePessoa("Vitor Camillo")
                        .texto("Minha resposta anterior da conversa " + codigo)
                        .dataCriacaoMillis(1000L)
                        .enviadaPorMim(true)
                        .build(),
                    MensagemConversa.builder()
                        .id(conversaId * 10 + 1)
                        .idConversa(conversaId)
                        .idPessoa(800L + indice)
                        .nomePessoa("Cliente isolado " + indice)
                        .texto("Contexto exclusivo " + codigo + ": prazo e escopo não podem sair desta conversa.")
                        .dataCriacaoMillis(2000L)
                        .enviadaPorMim(false)
                        .build()
                );
            });

        autoAtendimentoService.sincronizarInbox(ModoSistema.ENSINAMENTO);

        ArgumentCaptor<Projeto> projetoCaptor = ArgumentCaptor.forClass(Projeto.class);
        ArgumentCaptor<String> historicoCaptor = ArgumentCaptor.forClass(String.class);
        verify(avaliadorIaService, times(totalConversas)).sugerirRespostaConversa(
            projetoCaptor.capture(),
            historicoCaptor.capture(),
            anyString()
        );
        for (int i = 0; i < projetoCaptor.getAllValues().size(); i++) {
            Long projetoId = projetoCaptor.getAllValues().get(i).getId();
            int indice = (int) (projetoId - 9600L);
            String historico = historicoCaptor.getAllValues().get(i);
            String codigoEsperado = String.format("CTX-ISO-%02d", indice);
            assertTrue(historico.contains(codigoEsperado), "Historico sem codigo proprio: " + codigoEsperado);
            for (int outro = 0; outro < totalConversas; outro++) {
                if (outro != indice) {
                    assertFalse(
                        historico.contains(String.format("CTX-ISO-%02d", outro)),
                        "Historico do projeto " + projetoId + " recebeu contexto de "
                            + String.format("CTX-ISO-%02d", outro)
                    );
                }
            }
        }
        assertEquals(totalConversas, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
    }
}
