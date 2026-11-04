package com.freelasatende.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.entity.Aprendizado;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.MotivoAprendizado;
import com.freelasatende.model.enums.StatusRegraAprendida;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.repository.AprendizadoRepository;
import com.freelasatende.service.interfaceServico.AprendizadoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "scheduling.enabled=false")
@ActiveProfiles("test")
@DisplayName("Treinamento massivo real: aprendizado e memoria")
class AprendizadoTreinamentoMassivoRealTest {

    private static final Path MEMORIA_TESTE = Path.of("target/test-memoria-treinamento-massivo.json");

    @Autowired
    private AprendizadoService aprendizadoService;

    @Autowired
    private AprendizadoRepository aprendizadoRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        registry.add("ia.memoria-arquivo", () -> MEMORIA_TESTE.toString());
        registry.add("ia.api-key", () -> "");
        registry.add("ia.base-url", () -> "https://api.local.invalid");
        registry.add("ia.model", () -> "test-local");
    }

    @BeforeEach
    @Transactional
    void setUp() throws Exception {
        aprendizadoRepository.deleteAll();
        limparMemoria();
    }

    @AfterEach
    @Transactional
    void tearDown() throws Exception {
        aprendizadoRepository.deleteAll();
        limparMemoria();
    }

    @ParameterizedTest(name = "classifica feedback: {0}")
    @MethodSource("feedbacksClassificacao")
    @DisplayName("Mais de 30 feedbacks reais viram motivo correto")
    void classificaFeedbackHumanoEmMotivoCorreto(String feedback, MotivoAprendizado esperado) {
        assertEquals(esperado, MotivoAprendizado.fromTexto(feedback));
    }

    @ParameterizedTest(name = "persiste e sincroniza: {0}")
    @MethodSource("cenariosTreinamento")
    @DisplayName("Mais de 40 decisões treinam banco, regra e memória JSON")
    void decisoesTreinamBancoRegraEMemoriaJson(CenarioTreinamento cenario) throws Exception {
        aprendizadoService.registrarDecisaoDetalhada(
            cenario.projetoId(),
            cenario.titulo(),
            cenario.aprovado(),
            cenario.tipoAcao(),
            cenario.modo(),
            cenario.feedback(),
            null,
            cenario.textoOriginal(),
            cenario.textoReescrito(),
            cenario.origem()
        );

        List<Aprendizado> historico = aprendizadoService.buscarHistorico();
        assertEquals(1, historico.size());

        Aprendizado aprendizado = historico.get(0);
        assertNotNull(aprendizado.getId());
        assertEquals(cenario.projetoId(), aprendizado.getProjetoId());
        assertEquals(cenario.titulo(), aprendizado.getTituloProjeto());
        assertEquals(cenario.aprovado(), aprendizado.getFoiAprovado());
        assertEquals(cenario.tipoAcao(), aprendizado.getTipoAcao());
        assertEquals(cenario.modo(), aprendizado.getModoUsado());
        assertEquals(cenario.origem(), aprendizado.getOrigemFeedback());
        assertEquals(StatusRegraAprendida.CONFIRMADA, aprendizado.getStatusRegra());
        assertEquals(cenario.motivoEsperado(), aprendizado.getMotivoAprendizado());
        assertFalse(aprendizado.getRegrasAprendidas().isBlank());

        String memoria = Files.readString(MEMORIA_TESTE);
        assertTrue(memoria.contains("\"regras_ensinamento\""));
        assertTrue(memoria.contains("\"regras_consolidadas\""));
        assertTrue(memoria.contains(cenario.tipoAcao().name()));
        assertTrue(memoria.contains(cenario.motivoEsperado().name()));
        assertTrue(memoria.contains(aprendizado.getRegrasAprendidas()));
    }

    @ParameterizedTest(name = "regra publica aprovada: {0}")
    @MethodSource("feedbacksAprovadosPublicos")
    @DisplayName("Regras públicas só aparecem para decisões aprovadas")
    void regrasPublicasAparecemSoParaAprovadas(String feedback, String trechoRegra) {
        aprendizadoService.registrarDecisao(900L, "Projeto aprovado", true, "ok", ModoSistema.ENSINAMENTO, feedback);
        aprendizadoService.registrarDecisao(901L, "Projeto recusado", false, "erro", ModoSistema.ENSINAMENTO, "Ficou robotico e com cara de sistema");

        List<String> regras = aprendizadoService.buscarRegrasAprendidas();

        assertFalse(regras.isEmpty());
        assertTrue(regras.stream().anyMatch(regra -> regra.contains(trechoRegra)));
        assertFalse(regras.stream().anyMatch(regra -> regra.contains("Rascunhos recusados")));
    }

    @Test
    @DisplayName("Carga longa mantém somente 80 regras recentes na memória e consolida últimas 12")
    void cargaLongaMantemLimiteDaMemoriaEConsolidaUltimasRegras() throws Exception {
        for (int i = 1; i <= 105; i++) {
            aprendizadoService.registrarDecisaoDetalhada(
                10_000L + i,
                "Projeto carga " + i,
                i % 2 == 0,
                TipoAcaoPendente.values()[i % TipoAcaoPendente.values().length],
                ModoSistema.values()[i % ModoSistema.values().length],
                feedbackPorIndice(i),
                null,
                "Texto original " + i,
                i % 3 == 0 ? "Texto reescrito " + i : null,
                "CARGA_LONGA"
            );
        }

        var root = objectMapper.readTree(Files.readString(MEMORIA_TESTE));
        var regras = root.path("regras_ensinamento");
        String consolidadas = root.path("regras_consolidadas").asText();

        assertEquals(105, aprendizadoRepository.count());
        assertEquals(80, regras.size());
        assertEquals(10_026L, regras.get(0).path("projeto_id").asLong());
        assertEquals(10_105L, regras.get(79).path("projeto_id").asLong());
        assertTrue(consolidadas.contains("## Regras do modo ensinamento confirmadas"));
        assertTrue(consolidadas.contains("Projeto carga 105") || consolidadas.contains("PERGUNTAS_DEMAIS"));
    }

    @Test
    @DisplayName("Carga de ensino preserva instruções exatas do usuário sem regra genérica")
    void cargaDeEnsinoPreservaInstrucoesExatasDoUsuarioSemRegraGenerica() throws Exception {
        for (int i = 1; i <= 64; i++) {
            String regra = "Regra exata " + i
                + ": antes de responder cliente do painel administrativo, confirmar acesso legado e prazo final.";
            aprendizadoService.registrarDecisaoDetalhada(
                70_000L + i,
                "Ensino exato " + i,
                true,
                TipoAcaoPendente.RESPONDER_CONVERSA,
                ModoSistema.ENSINAMENTO,
                regra,
                MotivoAprendizado.OUTRO,
                "Rascunho original " + i,
                null,
                "TREINAMENTO_EXATO"
            );
        }

        List<Aprendizado> historico = aprendizadoService.buscarHistorico();
        var root = objectMapper.readTree(Files.readString(MEMORIA_TESTE));
        String memoria = root.toString();
        String ultimaRegra = "Regra exata 64: antes de responder cliente do painel administrativo, confirmar acesso legado e prazo final.";

        assertEquals(64, historico.size());
        assertEquals(64, root.path("regras_ensinamento").size());
        assertTrue(historico.stream().allMatch(aprendizado ->
            aprendizado.getRegrasAprendidas().contains("Aplicar orientacao ensinada pelo usuario: Regra exata")));
        assertTrue(memoria.contains(ultimaRegra));
        assertFalse(memoria.contains("Manter tom natural, curto e objetivo quando o rascunho for aprovado."));
        assertFalse(memoria.contains("Manter decisões aprovadas apenas quando houver motivo humano claro."));
    }

    @Test
    @DisplayName("Resumo operacional reflete volume real de treinamento")
    void resumoOperacionalRefleteVolumeRealDeTreinamento() {
        cenariosTreinamento().limit(18).map(args -> (CenarioTreinamento) args.get()[0]).forEach(cenario ->
            aprendizadoService.registrarDecisaoDetalhada(
                cenario.projetoId(),
                cenario.titulo(),
                cenario.aprovado(),
                cenario.tipoAcao(),
                cenario.modo(),
                cenario.feedback(),
                cenario.motivoEsperado(),
                cenario.textoOriginal(),
                cenario.textoReescrito(),
                cenario.origem()
            )
        );

        Map<String, Object> resumo = aprendizadoService.obterResumoOperacional();

        assertEquals(18L, resumo.get("totalRegrasConfirmadas"));
        assertEquals(0L, resumo.get("totalAguardandoConfirmacao"));
        assertTrue(resumo.containsKey("ultimosAprendizados"));
        assertTrue(resumo.containsKey("alertasMemoria"));
    }

    @Test
    @DisplayName("Salvar aprendizado direto tambem sincroniza memoria usada pela IA")
    void salvarDiretoComRegraSincronizaMemoriaParaIa() throws Exception {
        Aprendizado salvo = aprendizadoService.salvar(Aprendizado.builder()
            .data(java.time.LocalDateTime.now())
            .projetoId(77_001L)
            .tituloProjeto("Treinamento direto")
            .foiAprovado(true)
            .modoUsado(ModoSistema.ENSINAMENTO)
            .feedbackUsuario("Regra ensinada manualmente pelo painel")
            .regrasAprendidas("Sempre pedir confirmacao humana antes de responder duvida de escopo sensivel.")
            .motivoAprendizado(MotivoAprendizado.ESCOPO_CLARO)
            .tipoAcao(TipoAcaoPendente.RESPONDER_CONVERSA)
            .statusRegra(StatusRegraAprendida.CONFIRMADA)
            .origemFeedback("SALVAR_DIRETO")
            .build());

        var root = objectMapper.readTree(Files.readString(MEMORIA_TESTE));
        var primeiraRegra = root.path("regras_ensinamento").get(0);

        assertNotNull(salvo.getId());
        assertEquals(1, root.path("regras_ensinamento").size());
        assertEquals(salvo.getId().longValue(), primeiraRegra.path("id_aprendizado").asLong());
        assertEquals(77_001L, primeiraRegra.path("projeto_id").asLong());
        assertTrue(primeiraRegra.path("regra").asText().contains("confirmacao humana"));
        assertTrue(root.path("regras_consolidadas").asText().contains("confirmacao humana"));
    }

    @Test
    @DisplayName("Carga concorrente nao corrompe memoria nem perde aprendizados")
    void cargaConcorrenteNaoCorrompeMemoriaNemPerdeAprendizados() throws Exception {
        var tarefas = IntStream.rangeClosed(1, 36)
            .mapToObj(i -> (Callable<Void>) () -> {
                aprendizadoService.registrarDecisaoDetalhada(
                    90_000L + i,
                    "Treinamento concorrente " + i,
                    true,
                    TipoAcaoPendente.RESPONDER_CONVERSA,
                    ModoSistema.ENSINAMENTO,
                    feedbackPorIndice(i),
                    null,
                    "Texto concorrente " + i,
                    null,
                    "CARGA_CONCORRENTE"
                );
                return null;
            })
            .toList();

        var executor = Executors.newFixedThreadPool(12);
        try {
            for (var resultado : executor.invokeAll(tarefas)) {
                resultado.get();
            }
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        var root = objectMapper.readTree(Files.readString(MEMORIA_TESTE));

        assertEquals(36, aprendizadoRepository.count());
        assertEquals(36, root.path("regras_ensinamento").size());
        assertEquals(36, root.path("regras_ensinamento").findValues("id_aprendizado").stream().distinct().count());
        assertTrue(root.path("regras_consolidadas").asText().contains("Regras do modo ensinamento confirmadas"));
    }

    static Stream<Arguments> feedbacksClassificacao() {
        return Stream.of(
            Arguments.of("Bom tom, natural e direto", MotivoAprendizado.BOM_TOM),
            Arguments.of("Boa resposta, pode manter esse estilo", MotivoAprendizado.BOM_TOM),
            Arguments.of("Natural sem parecer automático", MotivoAprendizado.BOM_TOM),
            Arguments.of("Escopo claro e alinhado com o projeto", MotivoAprendizado.ESCOPO_CLARO),
            Arguments.of("A resposta ficou clara para o escopo", MotivoAprendizado.ESCOPO_CLARO),
            Arguments.of("Texto alinhado com a entrega principal", MotivoAprendizado.ESCOPO_CLARO),
            Arguments.of("Ficou robotico e frio", MotivoAprendizado.ROBOTICO),
            Arguments.of("Pareceu sistema", MotivoAprendizado.ROBOTICO),
            Arguments.of("Template automatizado", MotivoAprendizado.ROBOTICO),
            Arguments.of("Mensagem generica demais", MotivoAprendizado.GENERICO),
            Arguments.of("Ficou genérico, sem detalhe do projeto", MotivoAprendizado.GENERICO),
            Arguments.of("Risco de plataforma", MotivoAprendizado.RISCO_PLATAFORMA),
            Arguments.of("Pediu WhatsApp", MotivoAprendizado.RISCO_PLATAFORMA),
            Arguments.of("Pediu telefone", MotivoAprendizado.RISCO_PLATAFORMA),
            Arguments.of("Falou de pagamento por fora", MotivoAprendizado.RISCO_PLATAFORMA),
            Arguments.of("Mandou pedir email do cliente", MotivoAprendizado.RISCO_PLATAFORMA),
            Arguments.of("Sugeriu Pix direto", MotivoAprendizado.RISCO_PLATAFORMA),
            Arguments.of("Quase levou para fora da plataforma", MotivoAprendizado.RISCO_PLATAFORMA),
            Arguments.of("Duplicado com a mensagem anterior", MotivoAprendizado.DUPLICADO),
            Arguments.of("Texto duplicado", MotivoAprendizado.DUPLICADO),
            Arguments.of("Perguntas demais no mesmo rascunho", MotivoAprendizado.PERGUNTAS_DEMAIS),
            Arguments.of("Muitas perguntas para o cliente", MotivoAprendizado.PERGUNTAS_DEMAIS),
            Arguments.of("Perguntou demais antes de propor", MotivoAprendizado.PERGUNTAS_DEMAIS),
            Arguments.of("Fora do tom", MotivoAprendizado.FORA_DO_TOM),
            Arguments.of("Tom errado para cliente novo", MotivoAprendizado.FORA_DO_TOM),
            Arguments.of("Frio demais", MotivoAprendizado.FORA_DO_TOM),
            Arguments.of("Precisa perguntar prazo antes de valor", MotivoAprendizado.OUTRO),
            Arguments.of("Cliente ja respondeu sobre hospedagem", MotivoAprendizado.OUTRO),
            Arguments.of("Nao usar saudacao repetida", MotivoAprendizado.OUTRO),
            Arguments.of("Responder so o ponto pedido", MotivoAprendizado.OUTRO),
            Arguments.of("Confirmar acesso antes de dizer que começa", MotivoAprendizado.OUTRO),
            Arguments.of("Sem feedback especifico", MotivoAprendizado.OUTRO)
        );
    }

    static Stream<Arguments> cenariosTreinamento() {
        return IntStream.rangeClosed(1, 44)
            .mapToObj(i -> Arguments.of(cenario(i)));
    }

    static Stream<Arguments> feedbacksAprovadosPublicos() {
        return Stream.of(
            Arguments.of("Bom tom, natural e direto", "Manter tom natural"),
            Arguments.of("Escopo claro e alinhado", "Valorizar mensagens"),
            Arguments.of("Boa resposta, curto e humano", "Manter tom natural"),
            Arguments.of("Resposta natural sem exagero", "Manter tom natural"),
            Arguments.of("Projeto claro e proposta conectada ao escopo", "Valorizar mensagens"),
            Arguments.of("Tom bom para cliente novo", "Manter tom natural"),
            Arguments.of("Aprovado porque usou detalhe real do projeto", "Aplicar orientacao ensinada"),
            Arguments.of("Ficou alinhado com a entrega principal", "Valorizar mensagens")
        );
    }

    private static CenarioTreinamento cenario(int i) {
        MotivoAprendizado motivo = MotivoAprendizado.values()[(i - 1) % MotivoAprendizado.values().length];
        boolean aprovado = i % 3 != 0;
        TipoAcaoPendente tipo = TipoAcaoPendente.values()[i % TipoAcaoPendente.values().length];
        ModoSistema modo = ModoSistema.values()[i % ModoSistema.values().length];
        String feedback = feedbackParaMotivo(motivo, i);
        return new CenarioTreinamento(
            5_000L + i,
            "Treinamento " + i,
            aprovado,
            tipo,
            modo,
            feedback,
            motivo,
            "Texto original " + i,
            aprovado ? null : "Texto corrigido " + i,
            i % 2 == 0 ? "BOTAO_DASHBOARD" : "RESPOSTA_DUVIDA_IA"
        );
    }

    private static String feedbackParaMotivo(MotivoAprendizado motivo, int i) {
        return switch (motivo) {
            case BOM_TOM -> "BOM_TOM natural aprovado " + i;
            case ESCOPO_CLARO -> "ESCOPO_CLARO alinhado ao escopo " + i;
            case ROBOTICO -> "ROBOTICO com cara de sistema " + i;
            case GENERICO -> "GENERICO sem detalhe do projeto " + i;
            case RISCO_PLATAFORMA -> "RISCO_PLATAFORMA pediu WhatsApp " + i;
            case DUPLICADO -> "DUPLICADO com rascunho anterior " + i;
            case PERGUNTAS_DEMAIS -> "PERGUNTAS_DEMAIS muitas perguntas " + i;
            case FORA_DO_TOM -> "FORA_DO_TOM frio demais " + i;
            case OUTRO -> "Regra operacional especifica " + i;
        };
    }

    private static String feedbackPorIndice(int i) {
        return feedbackParaMotivo(MotivoAprendizado.values()[i % MotivoAprendizado.values().length], i);
    }

    private void limparMemoria() throws Exception {
        Files.deleteIfExists(MEMORIA_TESTE);
        Path parent = MEMORIA_TESTE.getParent();
        if (parent != null && Files.exists(parent)) {
            try (var paths = Files.list(parent)) {
                paths.filter(path -> path.getFileName().toString().startsWith(MEMORIA_TESTE.getFileName() + ".corrompido-"))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {}
                    });
            }
        }
    }

    record CenarioTreinamento(
        Long projetoId,
        String titulo,
        Boolean aprovado,
        TipoAcaoPendente tipoAcao,
        ModoSistema modo,
        String feedback,
        MotivoAprendizado motivoEsperado,
        String textoOriginal,
        String textoReescrito,
        String origem
    ) {}
}
