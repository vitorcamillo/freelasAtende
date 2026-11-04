package com.freelasatende.controller;

import com.freelasatende.repository.AprendizadoRepository;
import com.freelasatende.repository.MlLabAgentRepository;
import com.freelasatende.repository.MlLabCycleRepository;
import com.freelasatende.repository.MlLabRuleCandidateRepository;
import com.freelasatende.repository.MlLabRunRepository;
import com.freelasatende.service.impl.TelegramServiceImpl;
import com.freelasatende.service.interfaceServico.IaProvider;
import com.freelasatende.service.interfaceServico.ScraperService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "scheduling.enabled=false",
    "ml-lab.artifact-dir=target/ml-lab-test-output",
    "ml-lab.corpus.source-roots=target/ml-lab-test-corpus",
    "ml-lab.corpus.max-files=20",
    "ml-lab.corpus.max-bytes-per-file=1200",
    "ia.memoria-arquivo=target/ml-lab-test-memoria.json"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MlLabControllerTest {

    private static final Path CORPUS = Path.of("target/ml-lab-test-corpus/contexto.md");
    private static final Path MEMORIA_TESTE = Path.of("target/ml-lab-test-memoria.json");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MlLabRunRepository runRepository;

    @Autowired
    private MlLabAgentRepository agentRepository;

    @Autowired
    private MlLabCycleRepository cycleRepository;

    @Autowired
    private MlLabRuleCandidateRepository candidateRepository;

    @Autowired
    private AprendizadoRepository aprendizadoRepository;

    @MockBean
    private TelegramServiceImpl telegramService;

    @MockBean
    private ScraperService scraperService;

    @MockBean
    private IaProvider iaProvider;

    @BeforeEach
    void setUp() throws Exception {
        candidateRepository.deleteAll();
        aprendizadoRepository.deleteAll();
        Files.deleteIfExists(MEMORIA_TESTE);
        cycleRepository.deleteAll();
        agentRepository.deleteAll();
        runRepository.deleteAll();
        Files.createDirectories(CORPUS.getParent());
        Files.writeString(CORPUS, """
            Regra comercial: pedir prazo e criterio de pronto antes de promessa.
            api-key=segredo-que-deve-sumir
            Projeto bom: WordPress, landing page, automacao simples.
            """);
        when(iaProvider.getNome()).thenReturn("FakeIA");
        when(iaProvider.getModelo()).thenReturn("test-real-lab");
        when(iaProvider.estaDisponivel()).thenReturn(true);
        when(iaProvider.enviar(anyString(), anyInt())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            if (prompt.contains("Voce e avaliador do ML Lab")) {
                return "{\"score\":91,\"motivo\":\"seguro, claro e comercial\"}";
            }
            return """
                {
                  "resposta":"Rascunho real de laboratorio: validar escopo, prazo e risco antes de qualquer envio.",
                  "pensamentoVisivel":"Fonte: IA real do teste; leitura: projeto seguro com aprovacao manual.",
                  "duvida":"Qual prazo e prioridade devemos assumir?",
                  "regra":"Quando faltar prazo, perguntar ao dono antes de enviar qualquer coisa ao cliente."
                }
                """;
        });
    }

    @Test
    @DisplayName("ML Lab cria 30 agentes e executa 100 ciclos sem envio externo")
    void mlLabCriaTrintaAgentesEExecutaCemCiclosSemEnvioExterno() throws Exception {
        String created = mockMvc.perform(post("/api/ml-lab/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"totalCiclos\":100,\"objetivo\":\"teste gigante\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalAgentes").value(30))
            .andExpect(jsonPath("$.totalCiclos").value(100))
            .andExpect(jsonPath("$.externalWritesBlocked").value(true))
            .andExpect(jsonPath("$.corpusSummary").value(containsString("Escrita externa bloqueada")))
            .andExpect(jsonPath("$.agents", hasSize(30)))
            .andReturn()
            .getResponse()
            .getContentAsString();

        Long runId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .readTree(created)
            .get("id")
            .asLong();

        mockMvc.perform(post("/api/ml-lab/runs/{id}/start", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(true))
            .andExpect(jsonPath("$.overview.status").value("CONCLUIDO"))
            .andExpect(jsonPath("$.overview.ciclosConcluidos").value(100))
            .andExpect(jsonPath("$.overview.bestAgentKey").isNotEmpty())
            .andExpect(jsonPath("$.overview.candidates").isArray())
            .andExpect(jsonPath("$.overview.safety.envioCliente").value("bloqueado"))
            .andExpect(jsonPath("$.overview.safety.memoriaPrincipal").value("nao alterada"));

        mockMvc.perform(get("/api/ml-lab/runs/{id}/agents", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(30)))
            .andExpect(jsonPath("$[0].agentKey").isNotEmpty())
            .andExpect(jsonPath("$[0].scoreTotal").isNumber())
            .andExpect(jsonPath("$[0].regras").isArray());

        mockMvc.perform(post("/api/ml-lab/runs/{id}/agents/{agentKey}/chat", runId, "P1-A")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mensagem\":\"quais regras voce aprendeu?\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.agentKey").value("P1-A"))
            .andExpect(jsonPath("$.resposta").value(containsString("regras")))
            .andExpect(jsonPath("$.regras").isArray());

        verifyNoInteractions(telegramService);
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        verify(iaProvider, never()).enviar(anyString(), anyInt());
    }

    @Test
    @DisplayName("ML Lab real usa IaProvider em background e mantém envio externo bloqueado")
    void mlLabRealUsaIaProviderEmBackgroundEMantemEnvioExternoBloqueado() throws Exception {
        String created = mockMvc.perform(post("/api/ml-lab/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"totalCiclos\":1,\"usarIaReal\":true,\"maxTokensPorChamada\":1800}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.realIaEnabled").value(true))
            .andExpect(jsonPath("$.iaProviderName").value("FakeIA"))
            .andExpect(jsonPath("$.iaModelName").value("test-real-lab"))
            .andReturn()
            .getResponse()
            .getContentAsString();
        Long runId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .readTree(created)
            .get("id")
            .asLong();

        mockMvc.perform(post("/api/ml-lab/runs/{id}/start", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(true))
            .andExpect(jsonPath("$.mensagem").value(containsString("background")));

        var overview = awaitRunFinished(runId);
        org.junit.jupiter.api.Assertions.assertEquals("CONCLUIDO", overview.get("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals(1, overview.get("ciclosConcluidos").asInt());
        org.junit.jupiter.api.Assertions.assertTrue(overview.get("iaCallsTotal").asInt() >= 60);
        org.junit.jupiter.api.Assertions.assertEquals("bloqueado", overview.at("/safety/envioCliente").asText());
        org.junit.jupiter.api.Assertions.assertEquals("habilitada", overview.at("/safety/iaReal").asText());

        verify(iaProvider, atLeast(60)).enviar(anyString(), anyInt());
        verifyNoInteractions(telegramService);
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
    }

    @Test
    @DisplayName("ML Lab multi-criterio expõe configuração, fitness e paralelismo")
    void mlLabMultiCriterioExpoeConfiguracaoFitnessEParalelismo() throws Exception {
        String created = mockMvc.perform(post("/api/ml-lab/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "totalCiclos":2,
                      "usarIaReal":true,
                      "selectionMode":"MULTI_CRITERIA",
                      "parallelWorkers":8,
                      "eliteCount":6,
                      "repechageCount":9,
                      "childProtectionCycles":2
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.selectionMode").value("MULTI_CRITERIA"))
            .andExpect(jsonPath("$.parallelWorkers").value(8))
            .andExpect(jsonPath("$.eliteCount").value(6))
            .andExpect(jsonPath("$.repechageCount").value(9))
            .andExpect(jsonPath("$.childProtectionCycles").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
        Long runId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .readTree(created)
            .get("id")
            .asLong();

        mockMvc.perform(post("/api/ml-lab/runs/{id}/start", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(true))
            .andExpect(jsonPath("$.mensagem").value(containsString("background")));

        var overview = awaitRunFinished(runId);
        org.junit.jupiter.api.Assertions.assertEquals("CONCLUIDO", overview.get("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals("MULTI_CRITERIA", overview.get("selectionMode").asText());
        org.junit.jupiter.api.Assertions.assertEquals(8, overview.get("parallelWorkers").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(6, overview.get("eliteCount").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(9, overview.get("repechageCount").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(2, overview.get("childProtectionCycles").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(120, overview.get("iaCallsTotal").asInt());
        org.junit.jupiter.api.Assertions.assertTrue(overview.at("/agents/0/fitnessScore").asDouble() > 0);
        org.junit.jupiter.api.Assertions.assertFalse(overview.at("/agents/0/selectionBand").asText().isBlank());
        org.junit.jupiter.api.Assertions.assertFalse(overview.at("/agents/0/selectionReason").asText().isBlank());
        org.junit.jupiter.api.Assertions.assertTrue(overview.get("agents").findValues("protectedCyclesLeft").stream()
            .anyMatch(node -> node.asInt() > 0));

        verify(iaProvider, atLeast(120)).enviar(anyString(), anyInt());
        verifyNoInteractions(telegramService);
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
    }

    @Test
    @DisplayName("ML Lab retrya resposta da IA apenas thinking sem usar fallback")
    void mlLabRetryaRespostaThinkingSemFallback() throws Exception {
        AtomicInteger chamadas = new AtomicInteger();
        when(iaProvider.enviar(anyString(), anyInt())).thenAnswer(invocation -> {
            int chamada = chamadas.incrementAndGet();
            if (chamada == 1) {
                throw new IaProvider.IaProviderException(
                    "IA retornou apenas thinking, sem texto final",
                    502,
                    true
                );
            }
            String prompt = invocation.getArgument(0);
            if (prompt.contains("Voce e avaliador do ML Lab")) {
                return "{\"score\":91,\"motivo\":\"seguro, claro e comercial\"}";
            }
            return """
                {
                  "resposta":"Rascunho real de laboratorio: validar escopo, prazo e risco antes de qualquer envio.",
                  "pensamentoVisivel":"Fonte: IA real do teste; leitura: provider recuperou apos retry.",
                  "duvida":"Qual prazo e prioridade devemos assumir?",
                  "regra":"Quando o provider falhar de forma recuperavel, retryar antes de encerrar o ciclo."
                }
                """;
        });

        String created = mockMvc.perform(post("/api/ml-lab/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "totalCiclos":1,
                      "usarIaReal":true,
                      "selectionMode":"MULTI_CRITERIA",
                      "parallelWorkers":6
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        Long runId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .readTree(created)
            .get("id")
            .asLong();

        mockMvc.perform(post("/api/ml-lab/runs/{id}/start", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(true));

        var overview = awaitRunFinished(runId);
        org.junit.jupiter.api.Assertions.assertEquals("CONCLUIDO", overview.get("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals(1, overview.get("ciclosConcluidos").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(0, overview.get("deterministicFallbackTotal").asInt());
        org.junit.jupiter.api.Assertions.assertTrue(overview.get("iaCallsTotal").asInt() > 60);
        org.junit.jupiter.api.Assertions.assertNull(overview.get("erro").textValue());
    }

    @Test
    @DisplayName("ML Lab aproveita score quando avaliador retorna JSON em markdown")
    void mlLabAproveitaScoreQuandoAvaliadorRetornaJsonEmMarkdown() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            if (prompt.contains("Voce e avaliador do ML Lab")) {
                return """
                    ```json
                    {"score": 28, "motivo": "texto longo do avaliador que pode vir cercado por markdown"}
                    ```
                    """;
            }
            return """
                {
                  "resposta":"Rascunho real de laboratorio: validar escopo, prazo e risco antes de qualquer envio.",
                  "pensamentoVisivel":"Fonte: IA real do teste; leitura: avaliador veio em markdown.",
                  "duvida":"Qual prazo e prioridade devemos assumir?",
                  "regra":"Quando avaliador retornar markdown, extrair score sem encerrar o ciclo."
                }
                """;
        });

        String created = mockMvc.perform(post("/api/ml-lab/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "totalCiclos":1,
                      "usarIaReal":true,
                      "selectionMode":"MULTI_CRITERIA",
                      "parallelWorkers":6
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        Long runId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .readTree(created)
            .get("id")
            .asLong();

        mockMvc.perform(post("/api/ml-lab/runs/{id}/start", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(true));

        var overview = awaitRunFinished(runId);
        org.junit.jupiter.api.Assertions.assertEquals("CONCLUIDO", overview.get("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals(1, overview.get("ciclosConcluidos").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(0, overview.get("deterministicFallbackTotal").asInt());
        org.junit.jupiter.api.Assertions.assertNull(overview.get("erro").textValue());
    }

    @Test
    @DisplayName("ML Lab aproveita score quando avaliador retorna analise textual")
    void mlLabAproveitaScoreQuandoAvaliadorRetornaAnaliseTextual() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            if (prompt.contains("Voce e avaliador do ML Lab")) {
                return """
                    Let me analyze this response according to the scoring criteria.

                    Score: 28
                    Motivo: seguranca adequada, mas resposta pouco comercial.
                    """;
            }
            return """
                {
                  "resposta":"Rascunho real de laboratorio: validar escopo, prazo e risco antes de qualquer envio.",
                  "pensamentoVisivel":"Fonte: IA real do teste; leitura: avaliador veio textual.",
                  "duvida":"Qual prazo e prioridade devemos assumir?",
                  "regra":"Quando avaliador retornar score textual, extrair a nota sem encerrar o ciclo."
                }
                """;
        });

        String created = mockMvc.perform(post("/api/ml-lab/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "totalCiclos":1,
                      "usarIaReal":true,
                      "selectionMode":"MULTI_CRITERIA",
                      "parallelWorkers":6
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        Long runId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .readTree(created)
            .get("id")
            .asLong();

        mockMvc.perform(post("/api/ml-lab/runs/{id}/start", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(true));

        var overview = awaitRunFinished(runId);
        org.junit.jupiter.api.Assertions.assertEquals("CONCLUIDO", overview.get("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals(1, overview.get("ciclosConcluidos").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(0, overview.get("deterministicFallbackTotal").asInt());
        org.junit.jupiter.api.Assertions.assertNull(overview.get("erro").textValue());
    }

    @Test
    @DisplayName("Aprovacao de candidata aplica regra na memoria principal")
    void aprovacaoDeCandidataAplicaRegraNaMemoriaPrincipal() throws Exception {
        String created = mockMvc.perform(post("/api/ml-lab/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"totalCiclos\":2}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        Long runId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .readTree(created)
            .get("id")
            .asLong();

        String started = mockMvc.perform(post("/api/ml-lab/runs/{id}/start", runId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        Long candidateId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .readTree(started)
            .at("/overview/candidates/0/id")
            .asLong();

        mockMvc.perform(post("/api/ml-lab/candidates/{candidateId}/approve", candidateId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(true))
            .andExpect(jsonPath("$.mensagem").value(containsString("aplicada na memoria principal")));

        assertTrue(aprendizadoRepository.findAll().stream()
            .anyMatch(aprendizado -> aprendizado.getOrigemFeedback() != null
                && aprendizado.getOrigemFeedback().startsWith("ML_LAB_RUN_")
                && aprendizado.getRegrasAprendidas() != null
                && !aprendizado.getRegrasAprendidas().isBlank()));
    }

    private com.fasterxml.jackson.databind.JsonNode awaitRunFinished(Long runId) throws Exception {
        var mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder().build();
        // Run roda em executor proprio com 30 agentes/6 workers; isolado termina em
        // ~2-3s, mas sob carga da suite completa (muitos contextos Spring na mesma
        // JVM) ja estourou os 60s antigos. 900 iteracoes = ate ~90s nominais.
        int maxIteracoes = 900;
        for (int i = 0; i < maxIteracoes; i++) {
            String body = mockMvc.perform(get("/api/ml-lab/runs/{id}/overview", runId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
            var node = mapper.readTree(body);
            String status = node.get("status").asText();
            if ("CONCLUIDO".equals(status) || "ERRO".equals(status)) {
                return node;
            }
            Thread.sleep(100);
        }
        fail("Run real nao finalizou dentro do tempo esperado");
        return mapper.createObjectNode();
    }
}
