package com.freelasatende.scheduler;

import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.service.interfaceServico.AutoAtendimentoService;
import com.freelasatende.service.interfaceServico.ControleOperacionalService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import com.freelasatende.service.interfaceServico.MarketplaceCapabilities;
import com.freelasatende.service.interfaceServico.TelegramService;
import com.freelasatende.service.impl.LoginAssistidoAutoTrigger;
import com.freelasatende.service.impl.MarketplaceAdapterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InboxSchedulerTest {

    @Mock private AutoAtendimentoService autoAtendimentoService;
    @Mock private TelegramService telegramService;
    @Mock private GerenciadorConexoesService gerenciadorConexoes;
    @Mock private ControleOperacionalService controleOperacionalService;
    @Mock private LoginAssistidoAutoTrigger loginAssistidoAutoTrigger;
    @Mock private InteracaoRepository interacaoRepository;
    @Mock private MarketplaceAdapter adapter;

    @Test
    @DisplayName("Le a inbox sem depender do ciclo de discovery")
    void leInboxSemRodarDiscovery() {
        when(adapter.plataforma()).thenReturn(PlataformaFreela.FREELAS_99);
        when(adapter.capabilities())
            .thenReturn(new MarketplaceCapabilities(true, true, false, false, false, false, false));
        MarketplaceAdapterRegistry registry = new MarketplaceAdapterRegistry(List.of(adapter));

        criarInboxScheduler(registry, criarPollingScheduler(registry)).sincronizarInboxes();

        verify(autoAtendimentoService).sincronizarInbox(
            eq(ModoSistema.ENSINAMENTO),
            eq(5),
            eq(List.of(PlataformaFreela.FREELAS_99))
        );
        // O discovery e caro (centenas de projetos x avaliacao da IA) e nao pode
        // atrasar a leitura da conversa do cliente.
        verify(adapter, never()).buscarProjetosNovos();
    }

    @Test
    @DisplayName("Ignora plataforma sem capacidade de inbox")
    void ignoraPlataformaSemInbox() {
        when(adapter.plataforma()).thenReturn(PlataformaFreela.FREELANCER);
        when(adapter.capabilities())
            .thenReturn(new MarketplaceCapabilities(true, false, false, false, false, false, false));
        MarketplaceAdapterRegistry registry = new MarketplaceAdapterRegistry(List.of(adapter));

        criarInboxScheduler(registry, criarPollingScheduler(registry)).sincronizarInboxes();

        verify(autoAtendimentoService, never()).sincronizarInbox(any(), anyInt(), any());
    }

    private PollingScheduler criarPollingScheduler(MarketplaceAdapterRegistry registry) {
        return new PollingScheduler(
            registry,
            autoAtendimentoService,
            telegramService,
            gerenciadorConexoes,
            controleOperacionalService,
            loginAssistidoAutoTrigger,
            interacaoRepository,
            new AlertaSessaoManual(telegramService)
        );
    }

    private InboxScheduler criarInboxScheduler(
        MarketplaceAdapterRegistry registry,
        PollingScheduler pollingScheduler
    ) {
        InboxScheduler inboxScheduler = new InboxScheduler(registry, pollingScheduler);
        ReflectionTestUtils.setField(inboxScheduler, "schedulingEnabled", true);
        return inboxScheduler;
    }
}
