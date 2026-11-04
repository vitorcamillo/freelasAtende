package com.freelasatende.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.TelegramConfig;
import com.freelasatende.model.dto.AcaoControleDTO;
import com.freelasatende.model.dto.ControleAcaoResultadoDTO;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.StatusAcaoPendente;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramCommandHandlerRewriteCallbackTest {

    private static final String CHAT_ID = "12345";

    @Mock
    private TelegramServiceImpl telegramService;
    @Mock
    private TelegramConfig telegramConfig;
    @Mock
    private PollingScheduler pollingScheduler;
    @Mock
    private AutoAtendimentoService autoAtendimentoService;
    @Mock
    private GerenciadorConexoesService gerenciadorConexoes;
    @Mock
    private HistoricoService historicoService;
    @Mock
    private AutenticacaoService autenticacaoService;
    @Mock
    private IaProvider iaProvider;
    @Mock
    private AvaliadorIaService avaliadorIaService;
    @Mock
    private ControleOperacionalService controleOperacionalService;
    @Mock
    private MarketplaceGateway marketplaceGateway;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TelegramCommandHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        when(telegramConfig.getChatId()).thenReturn(CHAT_ID);
        when(telegramService.obterUpdates(anyLong()))
            .thenReturn(objectMapper.readTree("{\"ok\":true,\"result\":[]}"));
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
        ReflectionTestUtils.setField(
            handler,
            "arquivoOffsetTelegram",
            Path.of("target", "telegram-rewrite-callback-offset.txt").toString()
        );
    }

    @Test
    @DisplayName("Callback reescrever reprocessa rascunho atual sem pedir texto manual")
    void callbackReescreverReprocessaRascunhoAtualSemPedirTextoManual() throws Exception {
        AcaoControleDTO acao = acaoControle(12L);
        when(telegramService.obterUpdates(anyLong()))
            .thenReturn(updateComCallback("cb-1", "acao:reescrever:12", CHAT_ID));
        when(controleOperacionalService.registrarContextoEnsino(eq(12L), contains("Reescreva a mensagem atual")))
            .thenReturn(ControleAcaoResultadoDTO.builder()
                .sucesso(true)
                .mensagem("Contexto salvo e rascunho atualizado.")
                .acao(acao)
                .build());

        handler.verificarComandos();

        verify(telegramService).responderCallback("cb-1", "Reescrevendo");
        verify(controleOperacionalService).registrarContextoEnsino(eq(12L), contains("alternativa diferente"));
        verify(controleOperacionalService).registrarContextoEnsino(eq(12L), contains("Nao use pergunta generica"));
        verify(controleOperacionalService).registrarContextoEnsino(eq(12L), contains("um dado concreto do projeto"));
        verify(controleOperacionalService).registrarContextoEnsino(eq(12L), contains("Evite frases genericas"));
        verify(telegramService).enviarAcaoPendenteComBotoes(contains("Contexto salvo"), eq(12L));
        verify(telegramService, never()).enviarEntradaAcaoComBotoes(anyString(), anyLong());
    }

    @Test
    @DisplayName("Callback reescrever não captura a próxima conversa normal como texto da ação")
    void callbackReescreverNaoCapturaProximaConversaNormalComoTextoDaAcao() throws Exception {
        AcaoControleDTO acao = acaoControle(12L);
        when(telegramService.obterUpdates(anyLong()))
            .thenReturn(updateComCallback("cb-1", "acao:reescrever:12", CHAT_ID))
            .thenReturn(updateComMensagem("Oi, consegue confirmar o prazo ideal?", CHAT_ID));
        when(controleOperacionalService.registrarContextoEnsino(eq(12L), contains("Reescreva a mensagem atual")))
            .thenReturn(ControleAcaoResultadoDTO.builder()
                .sucesso(true)
                .mensagem("Rascunho reprocessado.")
                .acao(acao)
                .build());

        handler.verificarComandos();
        handler.verificarComandos();

        verify(controleOperacionalService).registrarContextoEnsino(eq(12L), contains("alternativa diferente"));
        verify(controleOperacionalService, never()).reescreverAcao(anyLong(), anyString(), anyString());
        verify(telegramService).enviarAcaoPendenteComBotoes(contains("Rascunho reprocessado"), eq(12L));
    }

    @Test
    @DisplayName("Callback robotico reprocessa rascunho atual sem recusar")
    void callbackRoboticoReprocessaRascunhoAtualSemRecusar() throws Exception {
        AcaoControleDTO acao = acaoControle(12L);
        when(telegramService.obterUpdates(anyLong()))
            .thenReturn(updateComCallback("cb-3", "acao:recusar:12:ROBOTICO", CHAT_ID));
        when(controleOperacionalService.registrarContextoEnsino(eq(12L), contains("ficou robotico")))
            .thenReturn(ControleAcaoResultadoDTO.builder()
                .sucesso(true)
                .mensagem("Rascunho reprocessado por tom robotico.")
                .acao(acao)
                .build());

        handler.verificarComandos();

        verify(controleOperacionalService).registrarContextoEnsino(eq(12L), contains("tom mais humano"));
        verify(controleOperacionalService).registrarContextoEnsino(eq(12L), contains("Mantenha o mesmo objetivo"));
        verify(controleOperacionalService, never()).recusarAcao(12L, "ROBOTICO");
        verify(telegramService).responderCallback("cb-3", "Reescrevendo tom");
        verify(telegramService).enviarAcaoPendenteComBotoes(contains("Rascunho reprocessado"), eq(12L));
        verify(telegramService).apagarMensagem(CHAT_ID, 321L);
    }

    @Test
    @DisplayName("Callback risco registra recusa sem reescrever")
    void callbackRiscoRegistraRecusaSemReescrever() throws Exception {
        when(telegramService.obterUpdates(anyLong()))
            .thenReturn(updateComCallback("cb-risco", "acao:recusar:12:RISCO_PLATAFORMA", CHAT_ID));
        when(controleOperacionalService.recusarAcao(12L, "RISCO_PLATAFORMA"))
            .thenReturn(ControleAcaoResultadoDTO.builder().sucesso(true).mensagem("risco registrado").build());

        handler.verificarComandos();

        verify(controleOperacionalService).recusarAcao(12L, "RISCO_PLATAFORMA");
        verify(controleOperacionalService, never()).registrarContextoEnsino(eq(12L), anyString());
        verify(telegramService).responderCallback("cb-risco", "Ação recusada");
        verify(telegramService).apagarMensagem(CHAT_ID, 321L);
    }

    private JsonNode updateComCallback(String callbackId, String data, String chatId) throws Exception {
        String json = """
            {
              "ok": true,
              "result": [
                {
                  "update_id": 1000,
                  "callback_query": {
                    "id": "%s",
                    "data": "%s",
                    "message": {
                      "message_id": 321,
                      "chat": {
                        "id": "%s"
                      }
                    }
                  }
                }
              ]
            }
            """.formatted(callbackId, data, chatId);
        return objectMapper.readTree(json);
    }

    private JsonNode updateComMensagem(String texto, String chatId) throws Exception {
        String json = """
            {
              "ok": true,
              "result": [
                {
                  "update_id": 1001,
                  "message": {
                    "text": "%s",
                    "chat": {
                      "id": "%s"
                    }
                  }
                }
              ]
            }
            """.formatted(texto, chatId);
        return objectMapper.readTree(json);
    }

    private AcaoControleDTO acaoControle(Long id) {
        return AcaoControleDTO.builder()
            .id(id)
            .projetoId(99L)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Landing page")
            .textoClientePreview("Oi, consegue confirmar o prazo ideal?")
            .entendimentoIa("Cliente quer prazo antes de decidir.")
            .atitudeSugerida("Revisar rascunho antes de enviar.")
            .statusGuardiao("PENDENTE")
            .nivelConfianca("ALTA")
            .scoreConfianca(94)
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
    }
}
