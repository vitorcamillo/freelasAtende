package com.freelasatende.scheduler;

import com.freelasatende.model.dto.AcaoControleDTO;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.service.interfaceServico.AutoAtendimentoService;
import com.freelasatende.service.interfaceServico.ControleOperacionalService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import com.freelasatende.service.interfaceServico.MarketplaceCapabilities;
import com.freelasatende.service.interfaceServico.ScraperService;
import com.freelasatende.service.interfaceServico.TelegramService;
import com.freelasatende.service.impl.LoginAssistidoAutoTrigger;
import com.freelasatende.service.impl.MarketplaceAdapterErrorType;
import com.freelasatende.service.impl.MarketplaceAdapterException;
import com.freelasatende.service.impl.MarketplaceAdapterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PollingSchedulerDiarioOperacionalTest {

    @Mock
    private ScraperService scraperService;

    @Mock
    private AutoAtendimentoService autoAtendimentoService;

    @Mock
    private TelegramService telegramService;

    @Mock
    private GerenciadorConexoesService gerenciadorConexoes;

    @Mock
    private ControleOperacionalService controleOperacionalService;

    @Mock
    private LoginAssistidoAutoTrigger loginAssistidoAutoTrigger;

    @Mock
    private InteracaoRepository interacaoRepository;

    @Mock
    private MarketplaceAdapter adapterFalho;

    @Mock
    private MarketplaceAdapter adapterSucesso;

    @Test
    @DisplayName("Faz o discovery de todos os marketplaces antes e intercala o processamento")
    void discoveryDeTodosAntesEProcessamentoIntercalado() {
        Projeto w1 = Projeto.builder().id(11L).titulo("w1").build();
        Projeto w2 = Projeto.builder().id(12L).titulo("w2").build();
        Projeto w3 = Projeto.builder().id(13L).titulo("w3").build();
        Projeto t1 = Projeto.builder().id(21L).titulo("t1").build();
        when(gerenciadorConexoes.temConexoesDisponiveis()).thenReturn(true);
        when(gerenciadorConexoes.precisaRenovarSessao()).thenReturn(false);
        when(adapterFalho.plataforma()).thenReturn(PlataformaFreela.WORKANA);
        when(adapterFalho.verificarLogin()).thenReturn(true);
        // WORKANA vem depois de TESTE na ordem do registry: a fila pequena e a
        // segunda, e so entra cedo se o processamento for intercalado.
        when(adapterFalho.buscarProjetosNovos()).thenReturn(List.of(t1));
        when(adapterSucesso.plataforma()).thenReturn(PlataformaFreela.TESTE);
        when(adapterSucesso.verificarLogin()).thenReturn(true);
        when(adapterSucesso.buscarProjetosNovos()).thenReturn(List.of(w1, w2, w3));
        PollingScheduler scheduler = criarScheduler(
            new MarketplaceAdapterRegistry(List.of(adapterFalho, adapterSucesso))
        );

        scheduler.verificarProjetos();

        ArgumentCaptor<Projeto> processados = ArgumentCaptor.forClass(Projeto.class);
        verify(autoAtendimentoService, times(4)).processarProjeto(processados.capture(), eq(ModoSistema.ENSINAMENTO));
        List<String> ordem = processados.getAllValues().stream().map(Projeto::getTitulo).toList();
        // As duas descobertas acontecem antes de qualquer processamento, e o
        // unico projeto do marketplace pequeno entra entre os do grande.
        org.mockito.InOrder descoberta = org.mockito.Mockito.inOrder(adapterFalho, autoAtendimentoService);
        descoberta.verify(adapterFalho).buscarProjetosNovos();
        descoberta.verify(autoAtendimentoService, times(4)).processarProjeto(any(), any());
        org.mockito.InOrder descoberta2 = org.mockito.Mockito.inOrder(adapterSucesso, autoAtendimentoService);
        descoberta2.verify(adapterSucesso).buscarProjetosNovos();
        descoberta2.verify(autoAtendimentoService, times(4)).processarProjeto(any(), any());
        assertTrue(ordem.indexOf("t1") <= 1, "t1 deveria sair na primeira rodada: " + ordem);
        assertTrue(ordem.indexOf("w1") < ordem.indexOf("w2") && ordem.indexOf("w2") < ordem.indexOf("w3"), ordem.toString());
    }

    @Test
    @DisplayName("Falha ao processar projeto de um marketplace nao interrompe os outros")
    void falhaEmUmMarketplaceNaoInterrompeOsOutros() {
        Projeto w1 = Projeto.builder().id(11L).titulo("w1").build();
        Projeto w2 = Projeto.builder().id(12L).titulo("w2").build();
        Projeto t1 = Projeto.builder().id(21L).titulo("t1").build();
        Projeto t2 = Projeto.builder().id(22L).titulo("t2").build();
        when(gerenciadorConexoes.temConexoesDisponiveis()).thenReturn(true);
        when(gerenciadorConexoes.precisaRenovarSessao()).thenReturn(false);
        when(adapterFalho.plataforma()).thenReturn(PlataformaFreela.WORKANA);
        when(adapterFalho.verificarLogin()).thenReturn(true);
        when(adapterFalho.buscarProjetosNovos()).thenReturn(List.of(w1, w2));
        when(adapterSucesso.plataforma()).thenReturn(PlataformaFreela.TESTE);
        when(adapterSucesso.verificarLogin()).thenReturn(true);
        when(adapterSucesso.buscarProjetosNovos()).thenReturn(List.of(t1, t2));
        org.mockito.Mockito.lenient().doThrow(new IllegalStateException("falha no projeto w1"))
            .when(autoAtendimentoService).processarProjeto(w1, ModoSistema.ENSINAMENTO);
        PollingScheduler scheduler = criarScheduler(
            new MarketplaceAdapterRegistry(List.of(adapterFalho, adapterSucesso))
        );

        scheduler.verificarProjetos();

        verify(autoAtendimentoService, never()).processarProjeto(w2, ModoSistema.ENSINAMENTO);
        verify(autoAtendimentoService).processarProjeto(t1, ModoSistema.ENSINAMENTO);
        verify(autoAtendimentoService).processarProjeto(t2, ModoSistema.ENSINAMENTO);
    }

    @Test
    @DisplayName("Notifica diário operacional quando surgem pendências novas")
    void notificaDiarioOperacionalQuandoSurgemPendenciasNovas() {
        MarketplaceAdapterRegistry registry = MarketplaceAdapterRegistry.compat(scraperService);
        when(scraperService.verificarLogin()).thenReturn(true);
        PollingScheduler scheduler = criarScheduler(registry);
        Projeto projeto = Projeto.builder().id(101L).titulo("Landing").build();
        when(gerenciadorConexoes.temConexoesDisponiveis()).thenReturn(true);
        when(gerenciadorConexoes.precisaRenovarSessao()).thenReturn(false);
        when(scraperService.buscarProjetosNovos()).thenReturn(List.of(projeto));
        when(autoAtendimentoService.contarAcoesPendentes()).thenReturn(4L, 6L);

        scheduler.verificarProjetos();
        rodarCicloDeInbox(scheduler, registry);

        verify(autoAtendimentoService).processarProjeto(projeto, ModoSistema.ENSINAMENTO);
        verify(autoAtendimentoService).sincronizarInbox(
            eq(ModoSistema.ENSINAMENTO),
            eq(5),
            eq(List.of(PlataformaFreela.FREELAS_99))
        );
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(telegramService).enviarNotificacao(captor.capture());
        String mensagem = captor.getValue();
        assertTrue(mensagem.contains("Diário do junior"));
        assertTrue(mensagem.contains("Li 1 projeto(s)"));
        assertTrue(mensagem.contains("Pendências novas: 2"));
        assertTrue(mensagem.contains("Escrita ao cliente: BLOQUEADA"));
    }

    @Test
    @DisplayName("Não notifica diário operacional sem pendência nova")
    void naoNotificaDiarioOperacionalSemPendenciaNova() {
        PollingScheduler scheduler = criarScheduler();
        when(gerenciadorConexoes.temConexoesDisponiveis()).thenReturn(true);
        when(gerenciadorConexoes.precisaRenovarSessao()).thenReturn(false);
        when(scraperService.buscarProjetosNovos()).thenReturn(List.of());
        when(autoAtendimentoService.contarAcoesPendentes()).thenReturn(4L, 4L);

        scheduler.verificarProjetos();

        verify(telegramService, never()).enviarNotificacao(any());
    }

    @Test
    @DisplayName("Diário operacional é enviado no máximo uma vez por dia")
    void diarioOperacionalEnviadoNoMaximoUmaVezPorDia() {
        PollingScheduler scheduler = criarScheduler();
        Projeto primeiro = Projeto.builder().id(101L).titulo("Landing").build();
        Projeto segundo = Projeto.builder().id(102L).titulo("Sistema").build();
        when(gerenciadorConexoes.temConexoesDisponiveis()).thenReturn(true);
        when(gerenciadorConexoes.precisaRenovarSessao()).thenReturn(false);
        when(scraperService.buscarProjetosNovos()).thenReturn(List.of(primeiro), List.of(segundo));
        when(autoAtendimentoService.contarAcoesPendentes()).thenReturn(4L, 6L, 6L, 8L);

        scheduler.verificarProjetos();
        scheduler.verificarProjetos();

        verify(telegramService, times(1)).enviarNotificacao(org.mockito.ArgumentMatchers.contains("Diário do junior"));
    }

    @Test
    @DisplayName("Nao envia check-in de ensino duplicado quando pendencia antiga ja tem card")
    void naoEnviaCheckinEnsinoDuplicadoQuandoPendenciaAntigaJaTemCard() {
        PollingScheduler scheduler = criarScheduler();

        when(gerenciadorConexoes.temConexoesDisponiveis()).thenReturn(true);
        when(gerenciadorConexoes.precisaRenovarSessao()).thenReturn(false);
        when(scraperService.buscarProjetosNovos()).thenReturn(List.of());
        when(autoAtendimentoService.contarAcoesPendentes()).thenReturn(4L, 4L);

        scheduler.verificarProjetos();

        verify(telegramService, never()).enviarAcaoPendenteComBotoes(any(), eq(17L));
        verify(telegramService, never()).enviarNotificacao(org.mockito.ArgumentMatchers.contains("Pergunta do junior"));
    }

    @Test
    @DisplayName("Falha de uma plataforma não bloqueia outra plataforma ativa")
    void falhaDeUmaPlataformaNaoBloqueiaOutraAtiva() {
        Projeto projeto = Projeto.builder()
            .plataforma(PlataformaFreela.TESTE)
            .id(1_000_000_000_077L)
            .idExterno(77L)
            .titulo("Sistema interno")
            .build();
        when(gerenciadorConexoes.temConexoesDisponiveis()).thenReturn(true);
        when(gerenciadorConexoes.precisaRenovarSessao()).thenReturn(false);
        when(adapterFalho.plataforma()).thenReturn(PlataformaFreela.WORKANA);
        when(adapterFalho.verificarLogin()).thenReturn(false);
        when(adapterSucesso.plataforma()).thenReturn(PlataformaFreela.TESTE);
        when(adapterSucesso.capabilities()).thenReturn(new MarketplaceCapabilities(true, true, false, false, false, false, false));
        when(adapterSucesso.verificarLogin()).thenReturn(true);
        when(adapterSucesso.buscarProjetosNovos()).thenReturn(List.of(projeto), List.of());
        when(autoAtendimentoService.contarAcoesPendentes()).thenReturn(0L, 0L);
        MarketplaceAdapterRegistry registry =
            new MarketplaceAdapterRegistry(List.of(adapterFalho, adapterSucesso));
        PollingScheduler scheduler = criarScheduler(registry);

        scheduler.verificarProjetos();
        rodarCicloDeInbox(scheduler, registry);
        limparCooldownSessao(scheduler);
        scheduler.verificarProjetos();
        rodarCicloDeInbox(scheduler, registry);

        verify(loginAssistidoAutoTrigger, never()).dispararSeSessaoInvalida("polling:WORKANA");
        verify(telegramService).enviarNotificacao(org.mockito.ArgumentMatchers.contains("cookies manuais"));
        verify(autoAtendimentoService).processarProjeto(projeto, ModoSistema.ENSINAMENTO);
        verify(autoAtendimentoService, org.mockito.Mockito.times(2)).sincronizarInbox(
            eq(ModoSistema.ENSINAMENTO),
            eq(5),
            eq(List.of(PlataformaFreela.TESTE))
        );
    }

    @Test
    @DisplayName("Sessao Workana invalida pede cookies manuais sem travar outras plataformas")
    void sessaoWorkanaInvalidaNoPollingNaoBloqueiaOutraPlataforma() {
        Projeto projeto = Projeto.builder()
            .plataforma(PlataformaFreela.TESTE)
            .id(1_000_000_000_088L)
            .idExterno(88L)
            .titulo("Sistema interno")
            .build();
        when(gerenciadorConexoes.temConexoesDisponiveis()).thenReturn(true);
        when(gerenciadorConexoes.precisaRenovarSessao()).thenReturn(false);
        when(adapterFalho.plataforma()).thenReturn(PlataformaFreela.WORKANA);
        when(adapterFalho.verificarLogin()).thenReturn(true);
        when(adapterFalho.buscarProjetosNovos()).thenThrow(new MarketplaceAdapterException(
            PlataformaFreela.WORKANA,
            MarketplaceAdapterErrorType.FALHA_AUTENTICACAO,
            "Workana discovery retornou HTTP 403"
        ));
        when(adapterSucesso.plataforma()).thenReturn(PlataformaFreela.TESTE);
        when(adapterSucesso.verificarLogin()).thenReturn(true);
        when(adapterSucesso.buscarProjetosNovos()).thenReturn(List.of(projeto));
        when(autoAtendimentoService.contarAcoesPendentes()).thenReturn(0L, 0L);
        PollingScheduler scheduler = criarScheduler(
            new MarketplaceAdapterRegistry(List.of(adapterFalho, adapterSucesso))
        );

        scheduler.verificarProjetos();
        limparCooldownSessao(scheduler);
        scheduler.verificarProjetos();

        verify(loginAssistidoAutoTrigger, never()).dispararSeSessaoInvalida("polling:WORKANA");
        verify(autoAtendimentoService, org.mockito.Mockito.times(2)).processarProjeto(projeto, ModoSistema.ENSINAMENTO);
        verify(telegramService).enviarNotificacao(org.mockito.ArgumentMatchers.contains("cookies manuais"));
    }

    @Test
    @DisplayName("Sessao Workana invalida na inbox pede cookies manuais")
    void sessaoWorkanaInvalidaNaInboxNaoDisparaAlertaGenerico() {
        when(gerenciadorConexoes.temConexoesDisponiveis()).thenReturn(true);
        when(gerenciadorConexoes.precisaRenovarSessao()).thenReturn(false);
        when(adapterFalho.plataforma()).thenReturn(PlataformaFreela.WORKANA);
        when(adapterFalho.capabilities()).thenReturn(new MarketplaceCapabilities(true, true, false, false, false, false, true));
        when(adapterFalho.verificarLogin()).thenReturn(true);
        when(adapterFalho.buscarProjetosNovos()).thenReturn(List.of());
        when(autoAtendimentoService.contarAcoesPendentes()).thenReturn(0L);
        doThrow(new MarketplaceAdapterException(
            PlataformaFreela.WORKANA,
            MarketplaceAdapterErrorType.FALHA_AUTENTICACAO,
            "Workana inbox retornou HTTP 403"
        )).when(autoAtendimentoService).sincronizarInbox(any(), anyInt(), any());
        MarketplaceAdapterRegistry registry =
            new MarketplaceAdapterRegistry(List.of(adapterFalho));
        PollingScheduler scheduler = criarScheduler(registry);

        scheduler.verificarProjetos();
        rodarCicloDeInbox(scheduler, registry);
        limparCooldownSessao(scheduler);
        scheduler.verificarProjetos();
        rodarCicloDeInbox(scheduler, registry);

        verify(loginAssistidoAutoTrigger, never()).dispararSeSessaoInvalida("inbox:WORKANA");
        verify(telegramService).enviarNotificacao(org.mockito.ArgumentMatchers.contains("cookies manuais"));
    }

    @Test
    @DisplayName("Scheduler aplica limite de inbox por plataforma")
    void schedulerAplicaLimiteDeInboxPorPlataforma() {
        when(gerenciadorConexoes.temConexoesDisponiveis()).thenReturn(true);
        when(gerenciadorConexoes.precisaRenovarSessao()).thenReturn(false);
        when(adapterFalho.plataforma()).thenReturn(PlataformaFreela.FREELAS_99);
        when(adapterFalho.capabilities()).thenReturn(new MarketplaceCapabilities(true, true, true, true, false, false, true));
        when(adapterFalho.verificarLogin()).thenReturn(true);
        when(adapterFalho.buscarProjetosNovos()).thenReturn(List.of());
        when(adapterSucesso.plataforma()).thenReturn(PlataformaFreela.WORKANA);
        when(adapterSucesso.capabilities()).thenReturn(new MarketplaceCapabilities(true, true, false, false, false, false, true));
        when(adapterSucesso.verificarLogin()).thenReturn(true);
        when(adapterSucesso.buscarProjetosNovos()).thenReturn(List.of());
        when(autoAtendimentoService.contarAcoesPendentes()).thenReturn(0L, 0L);
        MarketplaceAdapterRegistry registry =
            new MarketplaceAdapterRegistry(List.of(adapterFalho, adapterSucesso));
        PollingScheduler scheduler = criarScheduler(registry);

        scheduler.verificarProjetos();
        rodarCicloDeInbox(scheduler, registry);

        verify(adapterFalho).verificarLogin();
        verify(autoAtendimentoService).sincronizarInbox(
            eq(ModoSistema.ENSINAMENTO),
            eq(5),
            eq(List.of(PlataformaFreela.FREELAS_99))
        );
        verify(autoAtendimentoService).sincronizarInbox(
            eq(ModoSistema.ENSINAMENTO),
            eq(10),
            eq(List.of(PlataformaFreela.WORKANA))
        );
    }

    private void limparCooldownSessao(PollingScheduler scheduler) {
        ((java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils
            .getField(scheduler, "proximaTentativaSessaoPorPlataforma")).clear();
    }

    private PollingScheduler criarScheduler() {
        when(scraperService.verificarLogin()).thenReturn(true);
        return criarScheduler(MarketplaceAdapterRegistry.compat(scraperService));
    }

    private PollingScheduler criarScheduler(MarketplaceAdapterRegistry marketplaceAdapterRegistry) {
        PollingScheduler scheduler = new PollingScheduler(
            marketplaceAdapterRegistry,
            autoAtendimentoService,
            telegramService,
            gerenciadorConexoes,
            controleOperacionalService,
            loginAssistidoAutoTrigger,
            interacaoRepository,
            new AlertaSessaoManual(telegramService)
        );
        ReflectionTestUtils.setField(
            scheduler,
            "arquivoDiarioOperacional",
            Path.of("target", "diario-operacional-test-" + System.nanoTime() + ".txt").toString()
        );
        ReflectionTestUtils.setField(
            scheduler,
            "arquivoCheckinEnsino",
            Path.of("target", "checkin-ensino-test-" + System.nanoTime() + ".txt").toString()
        );
        return scheduler;
    }

    /**
     * A inbox saiu do ciclo de discovery e passou a ter agenda propria; os testes
     * disparam os dois ciclos como o runtime faz.
     */
    private void rodarCicloDeInbox(
        PollingScheduler scheduler,
        MarketplaceAdapterRegistry marketplaceAdapterRegistry
    ) {
        InboxScheduler inboxScheduler = new InboxScheduler(marketplaceAdapterRegistry, scheduler);
        ReflectionTestUtils.setField(inboxScheduler, "schedulingEnabled", true);
        inboxScheduler.sincronizarInboxes();
    }
}
