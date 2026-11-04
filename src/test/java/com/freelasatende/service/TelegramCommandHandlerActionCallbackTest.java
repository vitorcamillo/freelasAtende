package com.freelasatende.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.TelegramConfig;
import com.freelasatende.model.dto.AcaoControleDTO;
import com.freelasatende.model.dto.ControleAcaoResultadoDTO;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.EnsinoFocoDTO;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.scheduler.PollingScheduler;
import com.freelasatende.service.impl.MarketplaceGateway;
import com.freelasatende.service.impl.MfaCodeInbox;
import com.freelasatende.service.impl.TelegramCommandHandler;
import com.freelasatende.service.impl.TelegramServiceImpl;
import com.freelasatende.service.interfaceServico.AutoAtendimentoService;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.ControleOperacionalService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.HistoricoService;
import com.freelasatende.service.interfaceServico.IaProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.freelasatende.service.TelegramCommandHandlerFixtures.acaoControle;
import static com.freelasatende.service.TelegramCommandHandlerFixtures.updateComCallback;
import static com.freelasatende.service.TelegramCommandHandlerFixtures.updateComMensagem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
    "scheduling.enabled=false",
    "telegram.operator-chat.ia-enabled=false"
})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelegramCommandHandlerActionCallbackTest {

    private static final Path ARQUIVO_OFFSET_TESTE = Path.of("target", "telegram-command-handler-action-offset.txt");

    @MockBean
    private TelegramServiceImpl telegramService;
    @Autowired
    private TelegramConfig telegramConfig;
    @Autowired
    private PollingScheduler pollingScheduler;
    @Autowired
    private AutoAtendimentoService autoAtendimentoService;
    @Autowired
    private GerenciadorConexoesService gerenciadorConexoes;
    @Autowired
    private HistoricoService historicoService;
    @Autowired
    private AutenticacaoService autenticacaoService;
    @MockBean
    private IaProvider iaProvider;
    @Autowired
    private AvaliadorIaService avaliadorIaService;
    @MockBean
    private ControleOperacionalService controleOperacionalService;
    @MockBean
    private MarketplaceGateway marketplaceGateway;
    @Autowired
    private ObjectMapper objectMapper;

    private TelegramCommandHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        Files.deleteIfExists(ARQUIVO_OFFSET_TESTE);
        reset(telegramService, controleOperacionalService, marketplaceGateway, iaProvider);
        when(telegramService.obterUpdates(anyLong()))
            .thenReturn(objectMapper.readTree("{\"ok\":true,\"result\":[]}"));
        when(iaProvider.getNome()).thenReturn("Ollama");
        when(iaProvider.getModelo()).thenReturn("test-local");
        when(iaProvider.estaDisponivel()).thenReturn(true);

        handler = new TelegramCommandHandler(
            telegramService,
            telegramConfig,
            pollingScheduler,
            autoAtendimentoService,
            gerenciadorConexoes,
            historicoService,
            autenticacaoService,
            iaProvider,
            avaliadorIaService,
            controleOperacionalService,
            marketplaceGateway,
            new MfaCodeInbox()
        );
        ReflectionTestUtils.setField(handler, "arquivoOffsetTelegram", ARQUIVO_OFFSET_TESTE.toString());
    }

    @Test
    @DisplayName("Deve diagnosticar autoria de conversa sem criar rascunho")
    void deveDiagnosticarAutoriaDeConversaSemCriarRascunho() {
        ConversaResumo conversa = ConversaResumo.builder()
            .idConversa(77L)
            .idProjeto(88L)
            .nomeProjeto("Rastreador")
            .idCliente(1613360L)
            .nomeCliente("Cliente")
            .idFreelancer(269409L)
            .nomeFreelancer("Vitor Camillo")
            .build();
        MensagemConversa minha = MensagemConversa.builder()
            .id(1L)
            .idPessoa(269409L)
            .nomePessoa("Vitor Camillo")
            .texto("Ja foi atendido ?")
            .enviadaPorMim(true)
            .dataCriacaoMillis(2000L)
            .build();
        MensagemConversa cliente = MensagemConversa.builder()
            .id(2L)
            .idPessoa(1613360L)
            .nomePessoa("Cliente")
            .texto("Qual seria o prazo?")
            .enviadaPorMim(false)
            .dataCriacaoMillis(1000L)
            .build();
        MensagemConversa apagada = MensagemConversa.builder()
            .id(3L)
            .nomePessoa("Cliente")
            .texto(null)
            .excluida(true)
            .enviadaPorMim(false)
            .dataCriacaoMillis(3000L)
            .build();
        when(marketplaceGateway.buscarConversaInbox(77L)).thenReturn(java.util.Optional.of(conversa));
        when(marketplaceGateway.listarMensagensConversa(77L)).thenReturn(List.of(cliente, minha, apagada));

        handler.processarComando("/conversa 77");

        verify(telegramService).enviarNotificacao(contains("Diagnóstico da conversa 77"));
        verify(telegramService).enviarNotificacao(contains("EU | Vitor Camillo"));
        verify(telegramService).enviarNotificacao(contains("CLIENTE | Cliente"));
        verify(telegramService).enviarNotificacao(contains("IGNORADA"));
        verify(controleOperacionalService, never()).aprovarAcao(anyLong(), anyString());
    }

    @Test
    @DisplayName("Comando reescrever não altera ação fora dos botões")
    void comandoReescreverNaoAlteraAcaoForaDosBotoes() {
        handler.processarComando("/reescrever 12 Oi, consegue confirmar o prazo ideal?");

        verify(controleOperacionalService, never()).reescreverAcao(anyLong(), anyString(), anyString());
        verify(telegramService).enviarNotificacao(contains("botões da pendência"));
    }

    @Test
    @DisplayName("Callback ensinar aplica próxima mensagem como contexto da ação")
    void callbackEnsinarAplicaProximaMensagemComoContextoDaAcao() throws Exception {
        AcaoControleDTO acao = acaoControle(12L);
        when(telegramService.obterUpdates(anyLong()))
            .thenReturn(updateComCallback("cb-ensinar", "acao:ensinar:12", telegramConfig.getChatId()))
            .thenReturn(updateComMensagem("pergunta só se a hospedagem já está liberada", telegramConfig.getChatId()));
        when(controleOperacionalService.registrarContextoEnsino(12L, "pergunta só se a hospedagem já está liberada"))
            .thenReturn(ControleAcaoResultadoDTO.builder()
                .sucesso(true)
                .mensagem("Contexto salvo e rascunho atualizado.")
                .acao(acao)
                .build());

        handler.verificarComandos();
        handler.verificarComandos();

        verify(telegramService).responderCallback("cb-ensinar", "Ensino ativado");
        verify(telegramService).enviarEntradaAcaoComBotoes(contains("Mande agora o contexto"), eq(12L));
        verify(controleOperacionalService).registrarContextoEnsino(12L, "pergunta só se a hospedagem já está liberada");
        verify(telegramService).enviarAcaoPendenteComBotoes(contains("Contexto salvo"), eq(12L));
    }

    @Test
    @DisplayName("Callback focar define ação sem capturar conversa normal como ensino")
    void callbackFocarDefineAcaoSemCapturarConversaNormalComoEnsino() throws Exception {
        when(telegramService.obterUpdates(anyLong()))
            .thenReturn(updateComCallback("cb-foco", "acao:foco:12", telegramConfig.getChatId()));
        when(controleOperacionalService.definirFocoEnsino(12L)).thenReturn(EnsinoFocoDTO.builder()
            .ativo(true)
            .acaoId(12L)
            .projetoId(99L)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .tituloProjeto("Landing page")
            .textoClientePreview("Oi, consegue confirmar o prazo ideal?")
            .status("FOCADO")
            .mensagem("Foco de ensino definido.")
            .build());

        handler.verificarComandos();

        verify(controleOperacionalService).definirFocoEnsino(12L);
        verify(telegramService).responderCallback("cb-foco", "Foco atualizado");
        verify(telegramService).enviarNotificacao(contains("Ação em foco"));
        verify(telegramService).enviarNotificacao(contains("Tipo: ENVIAR\\_PERGUNTA"));
        verify(telegramService).enviarNotificacao(contains("use os botões da pendência"));
        verify(telegramService, never()).enviarNotificacao(contains("guarda como regra"));
    }

    @Test
    @DisplayName("Callback aprovar envia motivo estruturado")
    void callbackAprovarEnviaMotivoEstruturado() throws Exception {
        when(telegramService.obterUpdates(anyLong()))
            .thenReturn(updateComCallback("cb-2", "acao:aprovar:12:BOM_TOM", telegramConfig.getChatId()));
        when(controleOperacionalService.aprovarAcao(12L, "BOM_TOM"))
            .thenReturn(ControleAcaoResultadoDTO.builder().sucesso(true).mensagem("executada").build());

        handler.verificarComandos();

        verify(controleOperacionalService).aprovarAcao(12L, "BOM_TOM");
        verify(telegramService).responderCallback("cb-2", "Ação aprovada");
        verify(telegramService).apagarMensagem(telegramConfig.getChatId(), 321L);
        verify(telegramService, never()).enviarNotificacao(contains("executada"));
    }

    @Test
    @DisplayName("Callback aprovar não confirma envio quando ação já foi tratada")
    void callbackAprovarNaoConfirmaEnvioQuandoAcaoJaFoiTratada() throws Exception {
        when(telegramService.obterUpdates(anyLong()))
            .thenReturn(updateComCallback("cb-73", "acao:aprovar:73:BOM_TOM", telegramConfig.getChatId()));
        when(controleOperacionalService.aprovarAcao(73L, "BOM_TOM"))
            .thenReturn(ControleAcaoResultadoDTO.builder()
                .sucesso(false)
                .mensagem("Ação 73 já foi tratada com status REJEITADA")
                .build());

        handler.verificarComandos();

        verify(controleOperacionalService).aprovarAcao(73L, "BOM_TOM");
        verify(telegramService).responderCallback("cb-73", "Ação já tratada");
        verify(telegramService).enviarNotificacao(contains("Nada foi enviado agora"));
        verify(telegramService).apagarMensagem(telegramConfig.getChatId(), 321L);
        verify(telegramService, never()).responderCallback("cb-73", "Ação aprovada");
    }

    @Test
    @DisplayName("Callback aprovar com falha não apaga cartão da ação")
    void callbackAprovarComFalhaNaoApagaCartaoDaAcao() throws Exception {
        AcaoControleDTO acao = acaoControle(165L);
        when(telegramService.obterUpdates(anyLong()))
            .thenReturn(updateComCallback("cb-falha", "acao:aprovar:165:BOM_TOM", telegramConfig.getChatId()));
        when(controleOperacionalService.aprovarAcao(165L, "BOM_TOM"))
            .thenReturn(ControleAcaoResultadoDTO.builder()
                .sucesso(false)
                .mensagem("Ação 165 falhou antes de enviar e continua pendente")
                .acao(acao)
                .build());

        handler.verificarComandos();

        verify(controleOperacionalService).aprovarAcao(165L, "BOM_TOM");
        verify(telegramService).responderCallback("cb-falha", "Falha; continua pendente");
        verify(telegramService).enviarNotificacao(contains("continua pendente"));
        verify(telegramService, never()).apagarMensagem(telegramConfig.getChatId(), 321L);
    }

    @Test
    @DisplayName("Callback aprovar com falha final nao confirma envio")
    void callbackAprovarComFalhaFinalNaoConfirmaEnvio() throws Exception {
        when(telegramService.obterUpdates(anyLong()))
            .thenReturn(updateComCallback("cb-falha-final", "acao:aprovar:166:BOM_TOM", telegramConfig.getChatId()));
        when(controleOperacionalService.aprovarAcao(166L, "BOM_TOM"))
            .thenReturn(ControleAcaoResultadoDTO.builder()
                .sucesso(false)
                .mensagem("Ação 166 aprovada, mas falhou ao executar: Falha ao enviar resposta na conversa")
                .build());

        handler.verificarComandos();

        verify(controleOperacionalService).aprovarAcao(166L, "BOM_TOM");
        verify(telegramService).responderCallback("cb-falha-final", "Falha ao enviar");
        verify(telegramService).enviarNotificacao(contains("Nada foi enviado agora"));
        verify(telegramService).apagarMensagem(telegramConfig.getChatId(), 321L);
        verify(telegramService, never()).responderCallback("cb-falha-final", "Ação aprovada");
    }

    @Test
    @DisplayName("Offset do Telegram é persistido ao processar update")
    void offsetTelegramEhPersistidoAoProcessarUpdate() throws Exception {
        when(telegramService.obterUpdates(anyLong()))
            .thenReturn(updateComCallback("cb-2", "acao:aprovar:12:BOM_TOM", telegramConfig.getChatId()));
        when(controleOperacionalService.aprovarAcao(12L, "BOM_TOM"))
            .thenReturn(ControleAcaoResultadoDTO.builder().sucesso(true).mensagem("executada").build());

        handler.verificarComandos();

        assertEquals("1001", Files.readString(ARQUIVO_OFFSET_TESTE).trim());
    }
}
