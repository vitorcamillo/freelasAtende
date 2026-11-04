package com.freelasatende.service.impl;

import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import com.freelasatende.service.interfaceServico.MarketplaceCapabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoAtendimentoInboxSynchronizerTest {

    @Test
    @DisplayName("Conversa inacessivel entra em cooldown e nao repete 404 no ciclo seguinte")
    void conversaInacessivelEntraEmCooldown() {
        MarketplaceAdapter adapter = mock(MarketplaceAdapter.class);
        ConversaResumo conversa = ConversaResumo.builder()
            .idConversa(40222544L)
            .idProjeto(200L)
            .nomeProjeto("Projeto removido")
            .build();
        when(adapter.plataforma()).thenReturn(PlataformaFreela.WORKANA);
        when(adapter.capabilities()).thenReturn(new MarketplaceCapabilities(
            false,
            true,
            false,
            false,
            false,
            false,
            false
        ));
        when(adapter.listarConversasInbox()).thenReturn(List.of(conversa));

        AtomicInteger chamadas = new AtomicInteger();
        AutoAtendimentoInboxSynchronizer synchronizer = new AutoAtendimentoInboxSynchronizer(
            new MarketplaceAdapterRegistry(List.of(adapter)),
            (item, modo) -> {
                chamadas.incrementAndGet();
                throw new MarketplaceAdapterException(
                    PlataformaFreela.WORKANA,
                    MarketplaceAdapterErrorType.CONVERSA_INACESSIVEL,
                    "Workana conversa 40222544 inacessivel ou removida: HTTP 404"
                );
            }
        );

        synchronizer.sincronizar(ModoSistema.ENSINAMENTO, 10, List.of(PlataformaFreela.WORKANA));
        synchronizer.sincronizar(ModoSistema.ENSINAMENTO, 10, List.of(PlataformaFreela.WORKANA));

        assertEquals(1, chamadas.get());
    }

    @Test
    @DisplayName("Conversa inacessivel em cooldown nao consome limite da rodada")
    void conversaInacessivelEmCooldownNaoConsomeLimiteDaRodada() {
        MarketplaceAdapter adapter = mock(MarketplaceAdapter.class);
        ConversaResumo removida = ConversaResumo.builder()
            .idConversa(40222544L)
            .idProjeto(200L)
            .nomeProjeto("Projeto removido")
            .build();
        ConversaResumo valida = ConversaResumo.builder()
            .idConversa(40230000L)
            .idProjeto(201L)
            .nomeProjeto("Projeto valido")
            .build();
        AtomicReference<List<ConversaResumo>> inbox = new AtomicReference<>(List.of(removida));
        when(adapter.plataforma()).thenReturn(PlataformaFreela.WORKANA);
        when(adapter.capabilities()).thenReturn(new MarketplaceCapabilities(
            false,
            true,
            false,
            false,
            false,
            false,
            false
        ));
        when(adapter.listarConversasInbox()).thenAnswer(invocation -> inbox.get());

        AtomicInteger chamadas = new AtomicInteger();
        AutoAtendimentoInboxSynchronizer synchronizer = new AutoAtendimentoInboxSynchronizer(
            new MarketplaceAdapterRegistry(List.of(adapter)),
            (item, modo) -> {
                chamadas.incrementAndGet();
                if (item.getIdConversa().equals(removida.getIdConversa())) {
                    throw new MarketplaceAdapterException(
                        PlataformaFreela.WORKANA,
                        MarketplaceAdapterErrorType.CONVERSA_INACESSIVEL,
                        "Workana conversa 40222544 inacessivel ou removida: HTTP 404"
                    );
                }
            }
        );

        synchronizer.sincronizar(ModoSistema.ENSINAMENTO, 10, List.of(PlataformaFreela.WORKANA));
        inbox.set(List.of(removida, valida));
        synchronizer.sincronizar(ModoSistema.ENSINAMENTO, 1, List.of(PlataformaFreela.WORKANA));

        assertEquals(2, chamadas.get());
    }

    @Test
    @DisplayName("Falha temporaria dentro de conversa nao consome limite da rodada")
    void falhaTemporariaDentroDeConversaNaoConsomeLimiteDaRodada() {
        MarketplaceAdapter adapter = mock(MarketplaceAdapter.class);
        ConversaResumo comTimeout = ConversaResumo.builder()
            .idConversa(40425837L)
            .idProjeto(200L)
            .nomeProjeto("Conversa com timeout")
            .build();
        ConversaResumo valida = ConversaResumo.builder()
            .idConversa(40425838L)
            .idProjeto(201L)
            .nomeProjeto("Conversa valida")
            .build();
        when(adapter.plataforma()).thenReturn(PlataformaFreela.WORKANA);
        when(adapter.capabilities()).thenReturn(new MarketplaceCapabilities(
            false,
            true,
            false,
            false,
            false,
            false,
            false
        ));
        when(adapter.listarConversasInbox()).thenReturn(List.of(comTimeout, valida));

        AtomicInteger chamadas = new AtomicInteger();
        AutoAtendimentoInboxSynchronizer synchronizer = new AutoAtendimentoInboxSynchronizer(
            new MarketplaceAdapterRegistry(List.of(adapter)),
            (item, modo) -> {
                chamadas.incrementAndGet();
                if (item.getIdConversa().equals(comTimeout.getIdConversa())) {
                    throw new MarketplaceAdapterException(
                        PlataformaFreela.WORKANA,
                        MarketplaceAdapterErrorType.FALHA_REDE,
                        "Falha ao listar mensagens Workana"
                    );
                }
            }
        );

        synchronizer.sincronizar(ModoSistema.ENSINAMENTO, 1, List.of(PlataformaFreela.WORKANA));

        assertEquals(2, chamadas.get());
    }

    @Test
    @DisplayName("Sessao Workana invalida dentro da conversa propaga para o scheduler")
    void sessaoWorkanaInvalidaDentroDaConversaPropaga() {
        MarketplaceAdapter adapter = mock(MarketplaceAdapter.class);
        ConversaResumo conversa = ConversaResumo.builder()
            .idConversa(40237017L)
            .idProjeto(200L)
            .nomeProjeto("Projeto Workana")
            .build();
        when(adapter.plataforma()).thenReturn(PlataformaFreela.WORKANA);
        when(adapter.capabilities()).thenReturn(new MarketplaceCapabilities(
            false,
            true,
            false,
            false,
            false,
            false,
            false
        ));
        when(adapter.listarConversasInbox()).thenReturn(List.of(conversa));
        AutoAtendimentoInboxSynchronizer synchronizer = new AutoAtendimentoInboxSynchronizer(
            new MarketplaceAdapterRegistry(List.of(adapter)),
            (item, modo) -> {
                throw new MarketplaceAdapterException(
                    PlataformaFreela.WORKANA,
                    MarketplaceAdapterErrorType.FALHA_AUTENTICACAO,
                    "Workana mensagens retornou HTTP 403"
                );
            }
        );

        MarketplaceAdapterException exception = assertThrows(
            MarketplaceAdapterException.class,
            () -> synchronizer.sincronizar(ModoSistema.ENSINAMENTO, 10, List.of(PlataformaFreela.WORKANA))
        );

        assertEquals(MarketplaceAdapterErrorType.FALHA_AUTENTICACAO, exception.getType());
    }

    @Test
    @DisplayName("Falha temporaria ao listar inbox nao processa conversa nem propaga stack")
    void falhaTemporariaAoListarInboxNaoProcessaConversa() throws Exception {
        MarketplaceAdapter adapter = mock(MarketplaceAdapter.class);
        when(adapter.plataforma()).thenReturn(PlataformaFreela.FREELAS_99);
        when(adapter.capabilities()).thenReturn(new MarketplaceCapabilities(
            false,
            true,
            false,
            false,
            false,
            false,
            false
        ));
        when(adapter.listarConversasInbox()).thenThrow(new MarketplaceAdapterException(
            PlataformaFreela.FREELAS_99,
            MarketplaceAdapterErrorType.FALHA_REDE,
            "99Freelas inbox indisponivel nesta rodada: GET /services/user/carregarConversas retornou HTTP 521"
        ));
        AutoAtendimentoInboxSynchronizer.ConversaProcessor processor = mock(AutoAtendimentoInboxSynchronizer.ConversaProcessor.class);
        AutoAtendimentoInboxSynchronizer synchronizer = new AutoAtendimentoInboxSynchronizer(
            new MarketplaceAdapterRegistry(List.of(adapter)),
            processor
        );

        synchronizer.sincronizar(ModoSistema.ENSINAMENTO, 10, List.of(PlataformaFreela.FREELAS_99));

        verify(processor, never()).processar(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
