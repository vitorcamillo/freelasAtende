package com.freelasatende.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.dto.QualidadeRascunhoDTO;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.MensagemClienteRegistro;
import com.freelasatende.model.enums.StatusMensagemCliente;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.repository.MensagemClienteRegistroRepository;
import com.freelasatende.service.impl.AutoAtendimentoServiceImpl;
import com.freelasatende.service.impl.TelegramServiceImpl;
import com.freelasatende.service.interfaceServico.IaProvider;
import com.freelasatende.service.interfaceServico.QualidadeRascunhoService;
import com.freelasatende.service.interfaceServico.ScraperService;
import com.freelasatende.util.IaSafetyPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
    "scheduling.enabled=false",
    "ia.memoria-arquivo=target/test-memoria-ia-real-dry-run.json"
})
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "real.ia.enabled", matches = "true")
@DisplayName("IA real dry-run em modo ensino")
class IaRealDryRunEnsinoIT {

    private static final Path RELATORIO_DIR = Path.of("target/ia-real-dry-run-ensino");
    private static final Path MEMORIA_TESTE = Path.of("target/test-memoria-ia-real-dry-run.json");
    private static final Map<String, String> DOT_ENV = lerDotEnv();

    @Autowired
    private AutoAtendimentoServiceImpl autoAtendimentoService;

    @Autowired
    private IaProvider iaProvider;

    @Autowired
    private QualidadeRascunhoService qualidadeRascunhoService;

    @Autowired
    private AcaoPendenteRepository acaoPendenteRepository;

    @Autowired
    private InteracaoRepository interacaoRepository;

    @Autowired
    private MensagemClienteRegistroRepository mensagemClienteRegistroRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ScraperService scraperService;

    @MockBean
    private TelegramServiceImpl telegramService;

    private final AtomicLong mensagemId = new AtomicLong(150_000);

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        registry.add("ia.provider", () -> valor("ia.provider", "IA_PROVIDER", "openai"));
        registry.add("ia.api-key", () -> valor("ia.api-key", "IA_API_KEY", ""));
        registry.add("ia.base-url", () -> valor("ia.base-url", "IA_BASE_URL", "https://api.openai.com/v1"));
        registry.add("ia.model", () -> valor("ia.model", "IA_MODEL", "gpt-4o-mini"));
        registry.add("ia.timeout", () -> valor("ia.timeout", "IA_TIMEOUT", "60"));
        registry.add("ia.max-retries", () -> valor("ia.max-retries", "IA_MAX_RETRIES", "1"));
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
        reset(scraperService, telegramService);
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
    @DisplayName("Provider real gera rascunho e ensino sem enviar ao cliente")
    void providerRealGeraRascunhoEnsinoSemEnvio() throws Exception {
        assumeFalse(Boolean.getBoolean("real.write.enabled"), "Este dry-run exige real.write.enabled=false.");
        assumeTrue(iaProvider.estaDisponivel(), "IA real indisponivel ou sem credenciais validas.");

        ConversaResumo conversa = ConversaResumo.builder()
            .idConversa(151_001L)
            .idProjeto(141_001L)
            .nomeProjeto("Landing page para consultoria")
            .idCliente(51_001L)
            .nomeCliente("Cliente Dry Run")
            .idFreelancer(61_001L)
            .nomeFreelancer("Freelancer")
            .visualizada(false)
            .readOnly(false)
            .build();
        MensagemConversa mensagem = MensagemConversa.builder()
            .id(mensagemId.incrementAndGet())
            .idConversa(conversa.getIdConversa())
            .idPessoa(conversa.getIdCliente())
            .nomePessoa(conversa.getNomeCliente())
            .texto("Gostei da proposta. Voce consegue me dizer um prazo realista para a primeira versao?")
            .dataCriacaoMillis(1_510_010L)
            .enviadaPorMim(false)
            .visualizada(false)
            .excluida(false)
            .sistema(false)
            .build();

        when(scraperService.listarConversasInbox()).thenReturn(List.of(conversa));
        when(scraperService.listarMensagensConversa(conversa.getIdConversa())).thenReturn(List.of(mensagem));

        autoAtendimentoService.sincronizarInbox(ModoSistema.ENSINAMENTO, 1);

        Optional<AcaoPendente> pendenteOpt = acaoPendenteRepository
            .findFirstByConversaIdAndTipoAndStatusOrderByDataCriacaoDesc(
                conversa.getIdConversa(),
                TipoAcaoPendente.RESPONDER_CONVERSA,
                StatusAcaoPendente.PENDENTE
            );
        if (pendenteOpt.isEmpty()) {
            List<MensagemClienteRegistro> registros = mensagemClienteRegistroRepository.findTop50ByOrderByDataDesc();
            assertFalse(registros.isEmpty(), "IA real nao criou pendencia nem registro de bloqueio.");
            MensagemClienteRegistro bloqueio = registros.get(0);
            assertTrue(bloqueio.getStatus() == StatusMensagemCliente.BLOQUEADA,
                "Sem pendencia, o resultado esperado e bloqueio seguro. Registro: " + bloqueio);
            verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
            verify(scraperService, never()).enviarPergunta(anyLong(), anyString());

            EnsinoReport.escrever(RELATORIO_DIR, "IA real dry-run ensino", List.of(
                new EnsinoReport.Row(
                    "provider real dry-run bloqueado",
                    true,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    bloqueio.getStatus().name(),
                    mensagem.getTexto(),
                    "Conversa mockada com provider real; rascunho foi barrado antes de virar pendencia.",
                    "Guardiao bloqueou a saida da IA real antes de qualquer envio.",
                    "",
                    "",
                    bloqueio.getTextoPreview(),
                    bloqueio.getMotivoBloqueio()
                )
            ));
            assertTrue(Files.exists(RELATORIO_DIR.resolve("index.html")));
            return;
        }

        AcaoPendente pendente = pendenteOpt.get();
        JsonNode payloadInicial = objectMapper.readTree(pendente.getPayloadJson());

        String rascunhoInicial = payloadInicial.path("mensagem").asText();
        assertFalse(rascunhoInicial.isBlank());
        assertFalse(IaSafetyPolicy.containsUnsafeContent(rascunhoInicial));
        QualidadeRascunhoDTO qualidade = qualidadeRascunhoService.avaliar(rascunhoInicial);
        assertFalse(qualidade.bloqueante(), qualidade.getComentario());
        assertTrue(payloadInicial.path("perguntaParaUsuario").asText().toLowerCase().contains("prazo"));
        assertTrue(payloadInicial.path("duvidasInternas").toString().toLowerCase().contains("prazo"));

        String orientacao = "Responda de forma natural, sem prometer data fechada; diga que consegue estimar depois de alinhar escopo da primeira versao.";
        String retornoEnsino = autoAtendimentoService.registrarContextoEnsino(pendente.getId(), orientacao);
        assertFalse(retornoEnsino.toLowerCase().contains("enviado ao cliente com sucesso"));

        AcaoPendente depoisEnsino = acaoPendenteRepository.findById(pendente.getId()).orElseThrow();
        JsonNode payloadDepois = objectMapper.readTree(depoisEnsino.getPayloadJson());
        String rascunhoFinal = payloadDepois.path("mensagem").asText();
        assertFalse(rascunhoFinal.isBlank());
        assertFalse(IaSafetyPolicy.containsUnsafeContent(rascunhoFinal));
        assertTrue(depoisEnsino.getStatus() == StatusAcaoPendente.PENDENTE);

        String envio = autoAtendimentoService.processarFeedback(pendente.getId(), true, "BOM_TOM antes do ensino");
        assertTrue(envio.contains("executada"));
        assertTrue(acaoPendenteRepository.findById(pendente.getId()).orElseThrow().getStatus() == StatusAcaoPendente.EXECUTADA);
        verify(scraperService).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());

        EnsinoReport.escrever(RELATORIO_DIR, "IA real dry-run ensino", List.of(
            new EnsinoReport.Row(
                "provider real dry-run",
                true,
                true,
                true,
                true,
                true,
                false,
                true,
                depoisEnsino.getStatus().name(),
                payloadInicial.path("mensagemGatilhoTexto").asText(),
                payloadInicial.path("historicoConversa").asText(),
                payloadInicial.path("analiseIa").asText(),
                payloadInicial.path("perguntaParaUsuario").asText(),
                orientacao,
                rascunhoFinal,
                "Envio aprovado: " + envio
            )
        ));
        assertTrue(Files.exists(RELATORIO_DIR.resolve("index.html")));
    }

    private void bloquearEscritaReal() {
        doAnswer(invocation -> {
            throw new AssertionError("Envio de mensagem real proibido no dry-run de IA");
        }).when(scraperService).enviarMensagemConversa(anyLong(), anyString());
        doAnswer(invocation -> {
            throw new AssertionError("Envio de pergunta real proibido no dry-run de IA");
        }).when(scraperService).enviarPergunta(anyLong(), anyString());
    }

    private static String valor(String systemProperty, String envName, String fallback) {
        String property = System.getProperty(systemProperty);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        String env = System.getenv(envName);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return DOT_ENV.getOrDefault(envName, fallback);
    }

    private static Map<String, String> lerDotEnv() {
        Map<String, String> map = new HashMap<>();
        File file = Path.of(".env").toFile();
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
                value = value.replaceAll("^\"|\"$", "").replaceAll("^'|'$", "");
                map.put(key, value);
            }
        } catch (Exception ignored) {
            return Map.of();
        }
        return map;
    }
}
