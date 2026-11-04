package com.freelasatende.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.TelegramConfig;
import com.freelasatende.model.dto.AcaoControleDTO;
import com.freelasatende.model.dto.ConversaMensagemControleDTO;
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
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
    "scheduling.enabled=false",
    "telegram.operator-chat.ia-enabled=false"
})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class TelegramCommandHandlerTestSupport {

    @MockBean
    protected TelegramServiceImpl telegramService;

    @Autowired
    protected TelegramConfig telegramConfig;

    @Autowired
    protected PollingScheduler pollingScheduler;

    @Autowired
    protected AutoAtendimentoService autoAtendimentoService;

    @Autowired
    protected GerenciadorConexoesService gerenciadorConexoes;

    @Autowired
    protected HistoricoService historicoService;

    @Autowired
    protected AutenticacaoService autenticacaoService;

    @MockBean
    protected IaProvider iaProvider;

    @Autowired
    protected AvaliadorIaService avaliadorIaService;

    @MockBean
    protected ControleOperacionalService controleOperacionalService;

    @MockBean
    protected MarketplaceGateway marketplaceGateway;

    @Autowired
    protected ObjectMapper objectMapper;

    protected TelegramCommandHandler handler;
    protected static final Path ARQUIVO_OFFSET_TESTE = Path.of("target", "telegram-command-handler-offset.txt");

    @DynamicPropertySource
    static void carregarDotEnv(DynamicPropertyRegistry registry) {
        Map<String, String> env = lerDotEnvSeExistir(new File(".env"));
        registry.add("telegram.bot-token", () -> env.getOrDefault("TELEGRAM_BOT_TOKEN", "test-token"));
        registry.add("telegram.chat-id", () -> env.getOrDefault("TELEGRAM_CHAT_ID", "12345"));
        registry.add("ia.api-key", () -> env.getOrDefault("IA_API_KEY", ""));
        registry.add("ia.base-url", () -> env.getOrDefault("IA_BASE_URL", "https://api.openai.com/v1"));
        registry.add("ia.model", () -> env.getOrDefault("IA_MODEL", "gpt-4o-mini"));
    }

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

    protected JsonNode updateComCallback(String callbackId, String data, String chatId) throws Exception {
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

    protected JsonNode updateComMensagem(String texto, String chatId) throws Exception {
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

    protected AcaoControleDTO acaoControle(Long id) {
        return AcaoControleDTO.builder()
            .id(id)
            .projetoId(99L)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Landing page")
            .resumoInterno("Confirmar prazo antes de responder.")
            .textoClientePreview("Oi, consegue confirmar o prazo ideal?")
            .entendimentoIa("Cliente quer prazo antes de decidir.")
            .analiseIa("Li o contexto e falta prazo desejado antes de uma resposta segura.")
            .opcoesIa(List.of("perguntar prazo", "rascunhar proposta curta"))
            .atitudeSugerida("Conversar com voce antes de perguntar ao cliente.")
            .conversaEnsinoObrigatoria(true)
            .conversaEnsinoRespondida(false)
            .informacoesConfirmadas(List.of("cliente pediu prazo"))
            .informacoesPendentes(List.of("prazo ideal", "urgencia"))
            .sabeFazer(List.of("perguntar sem prometer prazo"))
            .duvidasInternas(List.of("se devemos perguntar prazo ou propor estimativa"))
            .perguntasParaCliente(List.of("Qual prazo ideal para entrega?"))
            .riscosDecisao(List.of("responder prazo sem base"))
            .proximaAcaoSugerida("Salvar orientacao de ensino antes de aprovar.")
            .perguntaParaUsuario("Quer que eu pergunte prazo ou ja proponha uma estimativa?")
            .regraAprendizadoSugerida("Em projetos de landing page, confirmar prazo antes de estimar entrega.")
            .mensagemGatilhoId(501L)
            .mensagemGatilhoTexto("Consegue me passar uma ideia de prazo?")
            .mensagemGatilhoAutor("CLIENTE")
            .mensagemGatilhoNome("Cliente")
            .idCliente(1613360L)
            .nomeCliente("Cliente")
            .idFreelancer(269409L)
            .nomeFreelancer("Vitor Camillo")
            .ultimasMensagensMinhas(List.of(ConversaMensagemControleDTO.builder()
                .id(500L)
                .autor("EU")
                .nome("Vitor Camillo")
                .texto("Oi, já vi seu projeto e estou avaliando.")
                .dataCriacaoMillis(1000L)
                .build()))
            .ultimasMensagensCliente(List.of(ConversaMensagemControleDTO.builder()
                .id(501L)
                .autor("CLIENTE")
                .nome("Cliente")
                .texto("Consegue me passar uma ideia de prazo?")
                .dataCriacaoMillis(2000L)
                .build()))
            .mensagensIgnoradasResumo(List.of("1 mensagem minha detectada: entra no contexto, mas nao aciona resposta."))
            .autoriaConfiavel(true)
            .motivoAutoria("Autoria definida por idFreelancer/idCliente da conversa.")
            .statusGuardiao("PENDENTE")
            .nivelConfianca("ALTA")
            .scoreNaturalidade(96)
            .scoreClareza(98)
            .scoreRisco(0)
            .scoreDuplicidade(0)
            .scoreConfianca(94)
            .decisaoQualidade("APROVAR")
            .comentarioQualidade("Rascunho natural.")
            .sinaisQualidade(List.of())
            .sinaisRisco(List.of())
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
    }

    private static Map<String, String> lerDotEnvSeExistir(File file) {
        Map<String, String> map = new HashMap<>();
        if (!file.exists() || !file.isFile()) {
            return map;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int idx = line.indexOf('=');
                if (idx <= 0) {
                    continue;
                }
                String key = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();
                value = value.replaceAll("^\"|\"$", "").replaceAll("^\\'|\\'$", "");
                map.put(key, value);
            }
        } catch (Exception ignore) {}
        return map;
    }
}
