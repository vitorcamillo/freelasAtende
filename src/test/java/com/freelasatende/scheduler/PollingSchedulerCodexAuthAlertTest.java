package com.freelasatende.scheduler;

import com.freelasatende.model.dto.ConexoesDisponiveis;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.Cliente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.service.impl.MarketplaceAdapterErrorType;
import com.freelasatende.service.impl.MarketplaceAdapterException;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import com.freelasatende.service.interfaceServico.MarketplaceCapabilities;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.service.impl.LoginAssistidoAutoTrigger;
import com.freelasatende.service.impl.MarketplaceAdapterRegistry;
import com.freelasatende.service.interfaceServico.AutoAtendimentoService;
import com.freelasatende.service.interfaceServico.ControleOperacionalService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.ScraperService;
import com.freelasatende.service.interfaceServico.TelegramService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PollingSchedulerCodexAuthAlertTest {

    @Mock private ScraperService scraperService;
    @Mock private AutoAtendimentoService autoAtendimentoService;
    @Mock private TelegramService telegramService;
    @Mock private GerenciadorConexoesService gerenciadorConexoesService;
    @Mock private ControleOperacionalService controleOperacionalService;
    @Mock private LoginAssistidoAutoTrigger loginAssistidoAutoTrigger;
    @Mock private InteracaoRepository interacaoRepository;

    private PollingScheduler scheduler;
    private MarketplaceAdapterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = MarketplaceAdapterRegistry.compat(scraperService);
        scheduler = new PollingScheduler(
            registry,
            autoAtendimentoService,
            telegramService,
            gerenciadorConexoesService,
            controleOperacionalService,
            loginAssistidoAutoTrigger,
            interacaoRepository,
            new AlertaSessaoManual(telegramService)
        );
    }

    /** A inbox roda em agenda propria; o teste dispara o ciclo como o runtime faz. */
    private void rodarCicloDeInbox(MarketplaceAdapterRegistry registryDoTeste) {
        InboxScheduler inboxScheduler = new InboxScheduler(registryDoTeste, scheduler);
        org.springframework.test.util.ReflectionTestUtils
            .setField(inboxScheduler, "schedulingEnabled", true);
        inboxScheduler.sincronizarInboxes();
    }

    @Test
    @DisplayName("Notifica uma vez quando autenticacao do Codex expira")
    void notificaCodexAuthExpiradaSemDuplicarDentroDoCooldown() {
        when(gerenciadorConexoesService.temConexoesDisponiveis()).thenReturn(true);
        when(gerenciadorConexoesService.precisaRenovarSessao()).thenReturn(false);
        when(scraperService.verificarLogin()).thenReturn(true);
        when(scraperService.buscarProjetosNovos()).thenReturn(List.of(Projeto.builder().id(1L).build()));
        when(autoAtendimentoService.contarAcoesPendentes()).thenReturn(0L);
        doThrow(new IllegalStateException("CODEX_AUTH_INVALIDA: autenticacao expirada"))
            .when(autoAtendimentoService).processarProjeto(any(), any());

        scheduler.verificarProjetos();
        scheduler.verificarProjetos();

        verify(telegramService, times(1)).enviarNotificacao(contains("auth.json"));
    }

    @Test
    @DisplayName("Sessao invalida em endpoint funcional aciona cooldown sem alerta generico")
    void sessaoInvalidaNaoDisparaAlertaGenericoDeErrosConsecutivos() {
        when(gerenciadorConexoesService.temConexoesDisponiveis()).thenReturn(true);
        when(gerenciadorConexoesService.precisaRenovarSessao()).thenReturn(false);
        when(scraperService.verificarLogin()).thenReturn(true);
        when(scraperService.buscarProjetosNovos()).thenThrow(new IllegalStateException("GET /projects retornou HTTP 403"));

        scheduler.verificarProjetos();
        scheduler.verificarProjetos();
        scheduler.verificarProjetos();

        verify(scraperService, times(1)).verificarLogin();
        verify(scraperService, times(1)).buscarProjetosNovos();
        verify(loginAssistidoAutoTrigger, times(1)).dispararSeSessaoInvalida("polling:99FREELAS");
        verify(telegramService, never()).enviarNotificacao(contains("erros consecutivos"));
        verify(autoAtendimentoService, never()).sincronizarInbox(any(), anyInt(), any());
    }

    @Test
    @DisplayName("Sessao 99Freelas invalida no precheck aciona login assistido")
    void sessao99FreelasInvalidaNoPrecheckAcionaLoginAssistido() {
        when(gerenciadorConexoesService.temConexoesDisponiveis()).thenReturn(true);
        when(gerenciadorConexoesService.precisaRenovarSessao()).thenReturn(false);
        when(scraperService.verificarLogin()).thenReturn(false);

        scheduler.verificarProjetos();

        verify(scraperService, times(1)).verificarLogin();
        verify(scraperService, never()).buscarProjetosNovos();
        verify(loginAssistidoAutoTrigger).dispararSeSessaoInvalida("polling:99FREELAS");
        verify(telegramService, never()).enviarNotificacao(contains("erros consecutivos"));
    }

    @Test
    @DisplayName("Inbox 99Freelas com sessao invalida aciona login assistido sem alerta generico")
    void inboxComSessaoInvalidaAcionaLoginAssistidoSemAlertaGenerico() {
        when(gerenciadorConexoesService.temConexoesDisponiveis()).thenReturn(true);
        when(gerenciadorConexoesService.precisaRenovarSessao()).thenReturn(false);
        when(scraperService.verificarLogin()).thenReturn(true);
        when(scraperService.buscarProjetosNovos()).thenReturn(List.of());
        when(autoAtendimentoService.contarAcoesPendentes()).thenReturn(0L);
        doThrow(new IllegalStateException(
            "Sessao 99Freelas invalida ao listar inbox: GET /services/user/carregarConversas retornou HTTP 403"
        )).when(autoAtendimentoService).sincronizarInbox(any(), anyInt(), any());

        scheduler.verificarProjetos();
        rodarCicloDeInbox(registry);

        verify(loginAssistidoAutoTrigger).dispararSeSessaoInvalida("inbox:99FREELAS");
        verify(telegramService, never()).enviarNotificacao(contains("erros consecutivos"));
    }

    @Test
    @DisplayName("Sessao BR invalida aciona login automatico, sem pedir cookies manuais no Telegram")
    void sessaoBrInvalidaAcionaLoginAutomaticoSemAlertaManual() {
        MarketplaceAdapter adapterBr = fakeAdapterBr();
        MarketplaceAdapterRegistry registryBr = new MarketplaceAdapterRegistry(List.of(adapterBr));
        scheduler = new PollingScheduler(
            registryBr,
            autoAtendimentoService,
            telegramService,
            gerenciadorConexoesService,
            controleOperacionalService,
            loginAssistidoAutoTrigger,
            interacaoRepository,
            new AlertaSessaoManual(telegramService)
        );
        when(gerenciadorConexoesService.temConexoesDisponiveis()).thenReturn(true);
        when(gerenciadorConexoesService.precisaRenovarSessao()).thenReturn(false);
        when(autoAtendimentoService.contarAcoesPendentes()).thenReturn(0L);

        // O .com.br passou a ter login automatico: /login + POST /conecte-se nao
        // tem captcha e o Chrome real resolve o desafio do Cloudflare. Entao a
        // sessao invalida deve DISPARAR renovacao, e nao pedir cookie ao usuario.
        doThrow(new MarketplaceAdapterException(
            PlataformaFreela.FREELANCER_BR,
            MarketplaceAdapterErrorType.FALHA_AUTENTICACAO,
            "Sessao Freelancer.com.br invalida: Cloudflare bloqueou (cf_clearance/sessao expirados)"
        )).when(autoAtendimentoService).sincronizarInbox(any(), anyInt(), any());

        scheduler.verificarProjetos();
        rodarCicloDeInbox(registryBr);
        limparCooldown();
        scheduler.verificarProjetos();
        rodarCicloDeInbox(registryBr);
        limparCooldown();

        verify(loginAssistidoAutoTrigger, atLeastOnce())
            .dispararSeSessaoInvalida("inbox:FREELANCER_BR");
        verify(telegramService, never())
            .enviarNotificacao(contains("Sessao Freelancer.com.br expirada"));
    }

    private void limparCooldown() {
        ((java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils
            .getField(scheduler, "proximaTentativaSessaoPorPlataforma")).clear();
    }

    private MarketplaceAdapter fakeAdapterBr() {
        return new MarketplaceAdapter() {
            @Override public PlataformaFreela plataforma() { return PlataformaFreela.FREELANCER_BR; }
            @Override public MarketplaceCapabilities capabilities() { return MarketplaceCapabilities.completa(); }
            @Override public List<Projeto> buscarProjetosNovos() { return List.of(); }
            @Override public Projeto buscarDetalhesProjeto(Long id, String slug) { return null; }
            @Override public Cliente buscarCliente(Long id) { return null; }
            @Override public boolean enviarPergunta(Long projetoId, String mensagem) { return false; }
            @Override public Proposta enviarProposta(Projeto projeto, Proposta proposta) { return null; }
            @Override public boolean cancelarProposta(Long projetoId) { return false; }
            @Override public List<ConversaResumo> listarConversasInbox() { return List.of(); }
            @Override public List<MensagemConversa> listarMensagensConversa(Long conversaId) { return List.of(); }
            @Override public List<MensagemConversa> listarMensagensConversa(Long conversaId, boolean marcar) { return List.of(); }
            @Override public MensagemConversa enviarMensagemConversa(Long conversaId, String texto) { return null; }
            @Override public ConexoesDisponiveis obterConexoesDisponiveis() { return null; }
            @Override public boolean confirmarMfaSessao(Long sessaoId, String codigo) { return false; }
            @Override public boolean verificarLogin() { return true; }
        };
    }
    @Test
    @DisplayName("Inicializa no modo e auto configurados para operacao supervisionada")
    void iniciaComModoEAutoConfigurados() {
        ReflectionTestUtils.setField(scheduler, "modoInicial", ModoSistema.LIVRE);
        ReflectionTestUtils.setField(scheduler, "autoAtivoInicial", true);

        scheduler.init();

        assertEquals(ModoSistema.LIVRE, scheduler.getModoAtual());
        assertTrue(scheduler.isAutoAtivo());
    }

}
