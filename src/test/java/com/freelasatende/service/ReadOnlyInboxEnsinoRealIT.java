package com.freelasatende.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.repository.MensagemClienteRegistroRepository;
import com.freelasatende.service.impl.AutoAtendimentoServiceImpl;
import com.freelasatende.service.impl.TelegramServiceImpl;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.ScraperService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
    "scheduling.enabled=false",
    "auth.cookies-arquivo=data/cookies.json",
    "ia.api-key=",
    "ia.base-url=https://api.local.invalid",
    "ia.model=test-local",
    "ia.memoria-arquivo=target/test-memoria-read-only-inbox.json"
})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "test", matches = ".*ReadOnlyInboxEnsinoRealIT.*")
@DisplayName("Inbox real read-only em modo ensino")
class ReadOnlyInboxEnsinoRealIT {

    private static final Path RELATORIO_DIR = Path.of("target/read-only-inbox-ensino");
    private static final Path MEMORIA_TESTE = Path.of("target/test-memoria-read-only-inbox.json");

    @Autowired
    private AutoAtendimentoServiceImpl autoAtendimentoService;

    @Autowired
    private AutenticacaoService autenticacaoService;

    @Autowired
    private AcaoPendenteRepository acaoPendenteRepository;

    @Autowired
    private InteracaoRepository interacaoRepository;

    @Autowired
    private MensagemClienteRegistroRepository mensagemClienteRegistroRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @SpyBean
    private ScraperService scraperService;

    @MockBean
    private AvaliadorIaService avaliadorIaService;

    @MockBean
    private TelegramServiceImpl telegramService;

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
        Files.deleteIfExists(MEMORIA_TESTE);
        Files.deleteIfExists(RELATORIO_DIR.resolve("index.html"));
        reset(avaliadorIaService, telegramService);

        when(avaliadorIaService.sugerirRespostaConversa(any(), anyString(), anyString()))
            .thenAnswer(invocation -> "Li a mensagem real e vou manter uma resposta curta por aqui antes de qualquer envio.");
        when(avaliadorIaService.reescreverRascunhoComOrientacao(anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        bloquearEscritaReal();
    }

    @AfterEach
    void tearDown() throws Exception {
        mensagemClienteRegistroRepository.deleteAll();
        acaoPendenteRepository.deleteAll();
        interacaoRepository.deleteAll();
        Files.deleteIfExists(MEMORIA_TESTE);
    }

    @Test
    @DisplayName("Le inbox real, cria pendencias de ensino e nao envia nada")
    void leInboxRealEmModoEnsinoSemEnviar() throws Exception {
        autenticacaoService.carregarCookies();
        assumeTrue(scraperService.verificarLogin(),
            "Sessao real do 99Freelas invalida. Faca login antes; este teste nao tenta login automatico.");
        assertTrue(mockingDetails(telegramService).isMock(), "Telegram precisa estar mockado neste teste real read-only.");

        List<ConversaResumo> conversasReais = scraperService.listarConversasInbox();
        assumeFalse(conversasReais.isEmpty(), "Inbox real vazia ou indisponivel para leitura read-only.");

        int limite = Math.min(Integer.getInteger("real.inbox.limit", 3), conversasReais.size());
        doReturn(conversasReais).when(scraperService).listarConversasInbox();

        autoAtendimentoService.sincronizarInbox(ModoSistema.ENSINAMENTO, limite);

        List<AcaoPendente> pendencias = acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.PENDENTE);
        assumeFalse(pendencias.isEmpty(), "Nenhuma conversa real acionavel encontrada nas " + limite + " primeiras conversas.");

        List<EnsinoReport.Row> rows = new ArrayList<>();
        for (AcaoPendente acao : pendencias) {
            JsonNode payload = objectMapper.readTree(acao.getPayloadJson());
            assertFalse(payload.path("mensagemGatilhoTexto").asText("").isBlank());
            assertFalse(payload.path("historicoConversa").asText("").isBlank());
            rows.add(new EnsinoReport.Row(
                "conversa real " + acao.getConversaId(),
                true,
                !payload.path("perguntaParaUsuario").asText("").isBlank(),
                false,
                !payload.path("mensagem").asText("").isBlank(),
                false,
                false,
                true,
                acao.getStatus().name(),
                payload.path("mensagemGatilhoTexto").asText(),
                payload.path("historicoConversa").asText(),
                payload.path("analiseIa").asText(),
                payload.path("perguntaParaUsuario").asText(),
                "",
                payload.path("mensagem").asText(),
                "Leitura real com IA mockada e escrita bloqueada."
            ));
        }

        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        verify(scraperService, never()).enviarProposta(any(), any(Proposta.class));

        EnsinoReport.escrever(RELATORIO_DIR, "Read-only inbox ensino real", rows);
        assertTrue(Files.exists(RELATORIO_DIR.resolve("index.html")));
    }

    private void bloquearEscritaReal() {
        doAnswer(invocation -> {
            throw new AssertionError("Envio de mensagem real proibido no teste read-only");
        }).when(scraperService).enviarMensagemConversa(anyLong(), anyString());
        doAnswer(invocation -> {
            throw new AssertionError("Envio de pergunta real proibido no teste read-only");
        }).when(scraperService).enviarPergunta(anyLong(), anyString());
        doAnswer(invocation -> {
            throw new AssertionError("Envio de proposta real proibido no teste read-only");
        }).when(scraperService).enviarProposta(any(), any(Proposta.class));
        doAnswer(invocation -> {
            throw new AssertionError("Cancelamento real proibido no teste read-only");
        }).when(scraperService).cancelarProposta(anyLong());
    }
}
