package com.freelasatende.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.AprendizadoRepository;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.repository.MensagemClienteRegistroRepository;
import com.freelasatende.service.impl.AutoAtendimentoServiceImpl;
import com.freelasatende.service.impl.TelegramServiceImpl;
import com.freelasatende.service.interfaceServico.IaProvider;
import com.freelasatende.service.interfaceServico.ScraperService;
import com.freelasatende.service.interfaceServico.UrlFetcherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
    "scheduling.enabled=false",
    "ia.api-key=",
    "ia.base-url=https://api.local.invalid",
    "ia.model=test-local",
    "ia.memoria-arquivo=target/test-memoria-aprendizado-replay.json"
})
@ActiveProfiles("test")
@DisplayName("Aprendizado replay em modo ensino")
class AprendizadoReplayEnsinoTest {

    private static final Path RELATORIO_DIR = Path.of("target/aprendizado-replay-ensino");
    private static final Path MEMORIA_TESTE = Path.of("target/test-memoria-aprendizado-replay.json");

    @Autowired
    private AutoAtendimentoServiceImpl autoAtendimentoService;

    @Autowired
    private AcaoPendenteRepository acaoPendenteRepository;

    @Autowired
    private AprendizadoRepository aprendizadoRepository;

    @Autowired
    private InteracaoRepository interacaoRepository;

    @Autowired
    private MensagemClienteRegistroRepository mensagemClienteRegistroRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ScraperService scraperService;

    @MockBean
    private IaProvider iaProvider;

    @MockBean
    private UrlFetcherService urlFetcherService;

    @MockBean
    private TelegramServiceImpl telegramService;

    private final AtomicLong mensagemId = new AtomicLong(91_000);
    private final Map<Long, List<MensagemConversa>> mensagensPorConversa = new HashMap<>();
    private final List<String> prompts = new ArrayList<>();

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        registry.add("ia.memoria-arquivo", () -> MEMORIA_TESTE.toString());
        registry.add("telegram.bot-token", () -> "");
        registry.add("telegram.chat-id", () -> "");
    }

    @BeforeEach
    void setUp() throws Exception {
        mensagemClienteRegistroRepository.deleteAll();
        acaoPendenteRepository.deleteAll();
        interacaoRepository.deleteAll();
        aprendizadoRepository.deleteAll();
        Files.deleteIfExists(MEMORIA_TESTE);
        Files.deleteIfExists(RELATORIO_DIR.resolve("index.html"));
        mensagensPorConversa.clear();
        prompts.clear();
        reset(scraperService, iaProvider, urlFetcherService, telegramService);

        when(iaProvider.getNome()).thenReturn("FakeIA");
        when(iaProvider.getModelo()).thenReturn("test-offline");
        when(iaProvider.estaDisponivel()).thenReturn(true);
        when(iaProvider.enviar(anyString(), anyInt())).thenAnswer(this::responderIa);
        when(urlFetcherService.buscarContextoDeUrls(anyString())).thenReturn("");
        when(scraperService.listarMensagensConversa(anyLong()))
            .thenAnswer(invocation -> List.copyOf(mensagensPorConversa.getOrDefault(invocation.getArgument(0), List.of())));
        bloquearEscritaReal();
    }

    @AfterEach
    void tearDown() throws Exception {
        mensagemClienteRegistroRepository.deleteAll();
        acaoPendenteRepository.deleteAll();
        interacaoRepository.deleteAll();
        aprendizadoRepository.deleteAll();
        Files.deleteIfExists(MEMORIA_TESTE);
    }

    @Test
    @DisplayName("Regra ensinada muda rascunho e pergunta em caso parecido")
    void regraEnsinadaMudaRascunhoEmCasoParecido() throws Exception {
        ConversaResumo primeira = conversa(81_001L, 71_001L, "Painel legado", "Cliente Legado");
        mensagensPorConversa.put(primeira.getIdConversa(), List.of(mensagemCliente(primeira, "Preciso ajustar uma tela do painel legado.")));
        when(scraperService.listarConversasInbox()).thenReturn(List.of(primeira));

        autoAtendimentoService.sincronizarInbox(ModoSistema.ENSINAMENTO, 1);

        AcaoPendente pendenteInicial = pendente(primeira.getIdConversa());
        JsonNode payloadInicial = objectMapper.readTree(pendenteInicial.getPayloadJson());
        String rascunhoInicial = payloadInicial.path("mensagem").asText();
        String perguntaInicial = payloadInicial.path("perguntaParaUsuario").asText();

        String ensino = "Para painel legado, antes de falar em prazo ou valor, sempre confirmar se o cliente tem acesso ao painel.";
        String resultadoEnsino = autoAtendimentoService.registrarContextoEnsino(pendenteInicial.getId(), ensino);
        assertTrue(resultadoEnsino.contains("Nada foi enviado ao cliente"));
        assertEquals(1, aprendizadoRepository.count());
        assertTrue(Files.readString(MEMORIA_TESTE).contains("painel legado"));

        ConversaResumo replay = conversa(81_002L, 71_002L, "Ajuste em painel legado", "Cliente Replay");
        mensagensPorConversa.put(replay.getIdConversa(), List.of(mensagemCliente(replay, "Tenho outro ajuste parecido nesse painel.")));
        when(scraperService.listarConversasInbox()).thenReturn(List.of(primeira, replay));

        autoAtendimentoService.sincronizarInbox(ModoSistema.ENSINAMENTO, 2);

        AcaoPendente pendenteReplay = pendente(replay.getIdConversa());
        JsonNode payloadReplay = objectMapper.readTree(pendenteReplay.getPayloadJson());
        String rascunhoReplay = payloadReplay.path("mensagem").asText();
        String perguntaReplay = payloadReplay.path("perguntaParaUsuario").asText();

        assertNotEquals(rascunhoInicial, rascunhoReplay);
        assertTrue(rascunhoReplay.toLowerCase().contains("acesso"));
        assertFalse(perguntaInicial.isBlank());
        assertFalse(perguntaReplay.isBlank());
        assertNotEquals(perguntaInicial, perguntaReplay);
        assertTrue(prompts.stream().anyMatch(prompt -> prompt.contains("Para painel legado")));
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());

        EnsinoReport.escrever(RELATORIO_DIR, "Aprendizado replay ensino", List.of(
            row("ensino inicial", payloadInicial, true, ensino, rascunhoInicial, "Regra registrada na memoria"),
            row("replay parecido", payloadReplay, false, "", rascunhoReplay, "Rascunho passou a pedir acesso antes de prazo/valor")
        ));
        assertTrue(Files.exists(RELATORIO_DIR.resolve("index.html")));
    }

    private String responderIa(InvocationOnMock invocation) {
        String prompt = invocation.getArgument(0);
        prompts.add(prompt);
        if (prompt.contains("Reescreva o rascunho")) {
            return "Perfeito. Antes de eu falar em prazo, voce consegue confirmar se tem acesso ao painel legado por aqui?";
        }
        if (prompt.contains("Para painel legado")) {
            return "Entendi. Antes de falar em prazo, voce consegue confirmar se tem acesso ao painel legado por aqui?";
        }
        return "Perfeito, consigo seguir com esse ajuste e te passo o proximo passo por aqui.";
    }

    private void bloquearEscritaReal() {
        doAnswer(invocation -> {
            throw new AssertionError("Envio de mensagem real proibido neste teste");
        }).when(scraperService).enviarMensagemConversa(anyLong(), anyString());
        doAnswer(invocation -> {
            throw new AssertionError("Envio de pergunta real proibido neste teste");
        }).when(scraperService).enviarPergunta(anyLong(), anyString());
    }

    private AcaoPendente pendente(Long conversaId) {
        return acaoPendenteRepository
            .findFirstByConversaIdAndTipoAndStatusOrderByDataCriacaoDesc(
                conversaId,
                TipoAcaoPendente.RESPONDER_CONVERSA,
                StatusAcaoPendente.PENDENTE
            )
            .orElseThrow(() -> new AssertionError("Sem pendencia para conversa " + conversaId));
    }

    private ConversaResumo conversa(Long conversaId, Long projetoId, String projeto, String cliente) {
        return ConversaResumo.builder()
            .idConversa(conversaId)
            .idProjeto(projetoId)
            .nomeProjeto(projeto)
            .idCliente(50_000L + conversaId)
            .nomeCliente(cliente)
            .idFreelancer(60_000L)
            .nomeFreelancer("Freelancer")
            .visualizada(false)
            .readOnly(false)
            .build();
    }

    private MensagemConversa mensagemCliente(ConversaResumo conversa, String texto) {
        return MensagemConversa.builder()
            .id(mensagemId.incrementAndGet())
            .idConversa(conversa.getIdConversa())
            .idPessoa(conversa.getIdCliente())
            .nomePessoa(conversa.getNomeCliente())
            .texto(texto)
            .dataCriacaoMillis(mensagemId.get() * 10)
            .enviadaPorMim(false)
            .visualizada(false)
            .excluida(false)
            .sistema(false)
            .build();
    }

    private EnsinoReport.Row row(
        String cenario,
        JsonNode payload,
        boolean ensinou,
        String ensino,
        String rascunho,
        String observacao
    ) {
        return new EnsinoReport.Row(
            cenario,
            true,
            !payload.path("perguntaParaUsuario").asText("").isBlank(),
            ensinou,
            !rascunho.isBlank(),
            false,
            false,
            true,
            "PENDENTE",
            payload.path("mensagemGatilhoTexto").asText(),
            payload.path("historicoConversa").asText(),
            payload.path("analiseIa").asText(),
            payload.path("perguntaParaUsuario").asText(),
            ensino,
            rascunho,
            observacao
        );
    }
}
