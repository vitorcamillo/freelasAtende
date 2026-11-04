package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.dto.AvaliacaoResultado;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.StatusMensagemCliente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.policy.OperationalSafetyPolicy;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.AprendizadoRepository;
import com.freelasatende.repository.ConfiguracaoRepository;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.repository.MensagemClienteRegistroRepository;
import com.freelasatende.repository.ProjetoRepository;
import com.freelasatende.service.interfaceServico.AprendizadoService;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.HistoricoService;
import com.freelasatende.service.interfaceServico.MensagemClienteGuardService;
import com.freelasatende.service.interfaceServico.ModoEnsinamentoPolicyService;
import com.freelasatende.service.interfaceServico.QualidadeRascunhoService;
import com.freelasatende.service.interfaceServico.ScraperService;
import com.freelasatende.util.IaSafetyPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provas de concorrencia do processamento de projeto e da aprovacao de acao
 * (fatia FA-CONC-02).
 *
 * <p>{@code AutoAtendimentoServiceImpl#processarProjeto} decide "ja atendido?"
 * com {@code historicoService.projetoJaProcessado} (check-then-act sobre a
 * tabela de interacoes) e a tabela {@code projetos} so tem indice NAO unico em
 * {@code (plataforma, id_externo)}; {@code criarOuRecuperarAcao*} e
 * {@code criarAbordagemInicialProjeto} usam {@code synchronized}, que protege
 * apenas uma JVM. Do lado da aprovacao,
 * {@code AutoAtendimentoFeedbackHandler#processarFeedback} le o status
 * PENDENTE e so depois executa o envio, sem lock, sem {@code @Version} e sem
 * update condicional — a unica barreira residual contra envio duplicado e o
 * fingerprint de duplicidade do guardiao, que depende do texto e nao do status
 * da acao. Em producao esses caminhos rodam em threads distintas (scheduler de
 * polling + aprovacao via API).
 *
 * <p>Os cenarios concorrentes usam {@link CountDownLatch} de largada e
 * {@link CyclicBarrier} dentro do spy do repositorio/servico — a intercalacao e
 * coordenada na propria leitura que alimenta o check-then-act (barreira dupla:
 * todas leem antes de qualquer seguir), e nao apenas uma largada conjunta que
 * pode nao se sobrepor. Rodam 3x para nao depender de sorte. Banco: o H2 em
 * memoria (MODE=MySQL) que o projeto ja usa nos testes — pode esconder o
 * problema em relacao ao MySQL de producao. Nenhum externo real: marketplaces
 * passam pelo {@link ScraperService} mockado, IA e Telegram sao mocks e o envio
 * roda em modo simulado. {@code aprovacaoIsoladaDaAcaoDePerguntaExecutaExatamenteUmEnvioSimulado}
 * (ATIVO) prova que a fixture da aprovacao exercita o envio de verdade.
 *
 * <p><b>ACHADO-FA-CONC-02-1</b>: cada thread que passa do check
 * "ja processado?" registra nova interacao de atendimento
 * ({@code AutoAtendimentoProjetoHandler#criarAbordagemInicialProjeto} registra
 * PROJETO_APROVADO incondicionalmente, mesmo recuperando a acao ja existente).
 * Cenario desabilitado com o comportamento CORRETO asserido, servindo de gate
 * de regressao para a correcao.
 *
 * <p><b>ACHADO-FA-CONC-02-2</b>: a checagem de status PENDENTE e a execucao do
 * envio nao formam um atomo; N threads aprovando a mesma acao podem disparar o
 * executor de envio mais de uma vez. Cenario desabilitado com o comportamento
 * CORRETO asserido — EXATAMENTE 1 envio/sucesso e as demais com a recusa de
 * negocio "ja tratada/nao pendente" — servindo de gate de regressao para a
 * correcao.
 */
@SpringBootTest(properties = {
    "scheduling.enabled=false",
    "ia.api-key=",
    "ia.base-url=https://api.local.invalid",
    "ia.model=test-local",
    "telegram.bot-token=",
    "telegram.chat-id=",
    "ia.memoria-arquivo=target/test-memoria-concorrencia.json"
})
@ActiveProfiles("test")
@DisplayName("Processamento e aprovacao de acoes sob concorrencia")
class ProcessamentoDuplicadoConcorrenciaTest {

    private static final int THREADS_PROCESSAMENTO = 20;
    private static final int THREADS_APROVACAO = 10;
    private static final int REPETICOES_CONCORRENTES = 3;
    private static final long PROJETO_ID_EXTERNO = 5010L;
    private static final Path MEMORIA_CONCORRENCIA = Path.of("target/test-memoria-concorrencia.json");

    @MockBean
    private TelegramServiceImpl telegramService;

    @MockBean
    private ScraperService scraperService;

    @MockBean
    private AutenticacaoService autenticacaoService;

    @MockBean
    private GerenciadorConexoesService gerenciadorConexoesService;

    @MockBean
    private AvaliadorIaService avaliadorIaService;

    @SpyBean
    private MensagemClienteGuardService mensagemClienteGuardService;

    // Spies para coordenar a intercalacao na propria leitura do check-then-act
    // (CyclicBarrier dentro do spy), e nao apenas uma largada conjunta.
    @SpyBean
    private AcaoPendenteRepository acaoPendenteRepository;

    @Autowired
    private InteracaoRepository interacaoRepository;

    @Autowired
    private ProjetoRepository projetoRepository;

    @Autowired
    private MensagemClienteRegistroRepository mensagemClienteRegistroRepository;

    @Autowired
    private AprendizadoRepository aprendizadoRepository;

    @Autowired
    private ConfiguracaoRepository configuracaoRepository;

    @SpyBean
    private HistoricoService historicoService;

    @Autowired
    private AprendizadoService aprendizadoService;

    @Autowired
    private QualidadeRascunhoService qualidadeRascunhoService;

    @Autowired
    private ModoEnsinamentoPolicyService modoEnsinamentoPolicyService;

    @Autowired
    private SystemHealthTracker healthTracker;

    @Autowired
    private TelegramMessageFormatter telegramMessageFormatter;

    @Autowired
    private MarketplaceAdapterRegistry marketplaceAdapterRegistry;

    @Autowired
    private IaSafetyPolicy iaSafetyPolicy;

    @Autowired
    private OperationalSafetyPolicy operationalSafetyPolicy;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void prepararCenario() {
        limparEstado();
        when(avaliadorIaService.avaliarProjeto(any())).thenReturn(
            AvaliacaoResultado.builder().capaz(true).motivoInteresse("dentro do escopo").build());
        when(avaliadorIaService.obterDiagnosticoMemoriaIa()).thenReturn(Map.of("saude", "OK"));
        when(scraperService.verificarLogin()).thenReturn(true);
        when(gerenciadorConexoesService.getStatusConexoes()).thenReturn("900 conexoes disponiveis");
    }

    @AfterEach
    void limparDados() {
        limparEstado();
    }

    @Test
    @Timeout(60)
    @DisplayName("Deve processar o mesmo projeto duas vezes em sequencia sem criar linhas novas")
    void deveProcessarMesmoProjetoDuasVezesSemCriarNovasLinhas() {
        AutoAtendimentoServiceImpl servico = servicoComEscritaSimulada(false);

        servico.processarProjeto(novoProjetoConcorrencia(), ModoSistema.ENSINAMENTO);
        servico.processarProjeto(novoProjetoConcorrencia(), ModoSistema.ENSINAMENTO);

        assertEquals(1, projetoRepository.count(),
            "Segundo processamento sequencial nao pode duplicar o projeto");
        assertEquals(1, acaoPendenteRepository.countByProjetoIdAndStatus(
                idInternoConcorrencia(), StatusAcaoPendente.PENDENTE),
            "Segundo processamento sequencial nao pode criar segunda acao pendente");
        assertEquals(1, interacoesAtendimentoDoProjeto().size(),
            "Segundo processamento sequencial nao pode registrar segunda interacao de atendimento");
    }

    @RepeatedTest(REPETICOES_CONCORRENTES)
    @Timeout(60)
    @DisplayName("Deve criar no maximo 1 projeto, 1 interacao de atendimento e 1 acao com 20 threads no mesmo projeto")
    void deveCriarNoMaximoUmProjetoUmaInteracaoEUmaAcaoComVinteThreadsNoMesmoProjeto() throws Exception {
        AutoAtendimentoServiceImpl servico = servicoComEscritaSimulada(false);
        CyclicBarrier barreiraChecagem = new CyclicBarrier(THREADS_PROCESSAMENTO);
        List<Boolean> resultadosDaChecagem = new CopyOnWriteArrayList<>();

        // Intercalacao coordenada no ponto do check-then-act: cada thread espera as
        // demais chegarem ao projetoJaProcessado, le o estado real e so segue depois
        // de TODAS terem lido. Cada thread chama este metodo exatamente 1 vez.
        doAnswer(invocacao -> {
            barreiraChecagem.await(20, TimeUnit.SECONDS);
            boolean jaProcessado = (Boolean) invocacao.callRealMethod();
            resultadosDaChecagem.add(jaProcessado);
            barreiraChecagem.await(20, TimeUnit.SECONDS);
            return jaProcessado;
        }).when(historicoService).projetoJaProcessado(any(PlataformaFreela.class), anyLong());

        dispararEmParalelo(THREADS_PROCESSAMENTO, () -> {
            servico.processarProjeto(novoProjetoConcorrencia(), ModoSistema.ENSINAMENTO);
            return "processado";
        });

        assertEquals(THREADS_PROCESSAMENTO, resultadosDaChecagem.size(),
            "As 20 threads devem ter passado pelo ponto de leitura do check-then-act");
        assertTrue(resultadosDaChecagem.stream().allMatch(jaProcessado -> !jaProcessado),
            "As 20 threads devem ter lido o mesmo estado inicial (nao processado): a barreira "
                + "na leitura garante sobreposicao real e nao apenas largada conjunta; lidos="
                + resultadosDaChecagem);
        assertEquals(1, projetoRepository.count(),
            "Nenhuma thread pode criar um segundo projeto para o mesmo id externo");
        assertEquals(1, acaoPendenteRepository.count(),
            "Check-then-act de acao pendente precisa ser atomico: uma unica acao por projeto");
        long interacoesAtendimento = interacoesAtendimentoDoProjeto().size();
        assertTrue(interacoesAtendimento <= 1,
            "Cada etapa de atendimento pode registrar no maximo 1 interacao por projeto; registradas="
                + interacoesAtendimento);
        verify(telegramService, atMostOnce())
            .enviarAcaoPendenteComBotoes(anyString(), any());
    }

    @Test
    @Timeout(60)
    @DisplayName("Sanidade da fixture: aprovacao isolada da acao de pergunta executa exatamente 1 envio simulado")
    void aprovacaoIsoladaDaAcaoDePerguntaExecutaExatamenteUmEnvioSimulado() {
        AutoAtendimentoServiceImpl servico = servicoComEscritaSimulada(true);
        AcaoPendente acao = salvarAcaoPendenteDePergunta();

        String resposta = servico.processarFeedback(acao.getId(), true, "Aprovado apos leitura do escopo");

        assertTrue(resposta.startsWith("✅"),
            "Aprovacao isolada da fixture deve executar o envio simulado; resposta=" + resposta);
        verify(mensagemClienteGuardService, times(1)).registrarEnvioSimulado(any(), anyString());
        assertEquals(1, mensagemClienteRegistroRepository.countByStatus(StatusMensagemCliente.SIMULADA),
            "Aprovacao isolada deve registrar exatamente 1 envio simulado");
        AcaoPendente aposAprovacao = acaoPendenteRepository.findById(acao.getId()).orElseThrow();
        assertEquals(StatusAcaoPendente.EXECUTADA, aposAprovacao.getStatus(),
            "Aprovacao isolada deve encerrar a acao como EXECUTADA");
    }

    @RepeatedTest(REPETICOES_CONCORRENTES)
    @Timeout(60)
    @DisplayName("Deve enviar exatamente 1 vez com 10 threads aprovando a mesma acao")
    void deveEnviarExatamenteUmaVezComDezAprovacoesConcorrentesDaMesmaAcao() throws Exception {
        AutoAtendimentoServiceImpl servico = servicoComEscritaSimulada(true);
        AcaoPendente acao = salvarAcaoPendenteDePergunta();
        CyclicBarrier barreiraLeitura = new CyclicBarrier(THREADS_APROVACAO);
        List<StatusAcaoPendente> statusLidosNaChecagem = new CopyOnWriteArrayList<>();
        AtomicInteger chegadasNaLeitura = new AtomicInteger();

        // Intercalacao coordenada no ponto da leitura do status: cada thread espera
        // as demais chegarem ao findById, le o status real e so segue depois de TODAS
        // terem lido. Cada thread chama findById 1x; leituras posteriores (assertions)
        // passam direto pelo limite de chegadas abaixo.
        doAnswer(invocacao -> {
            if (chegadasNaLeitura.getAndIncrement() < THREADS_APROVACAO) {
                barreiraLeitura.await(20, TimeUnit.SECONDS);
                @SuppressWarnings("unchecked")
                Optional<AcaoPendente> lida = (Optional<AcaoPendente>) org.mockito.Mockito.mockingDetails(invocacao.getMock()).getMockCreationSettings().getDefaultAnswer().answer(invocacao);
                statusLidosNaChecagem.add(lida.get().getStatus());
                barreiraLeitura.await(20, TimeUnit.SECONDS);
                return lida;
            }
            return org.mockito.Mockito.mockingDetails(invocacao.getMock()).getMockCreationSettings().getDefaultAnswer().answer(invocacao);
        }).when(acaoPendenteRepository).findById(any());

        List<String> respostas = dispararEmParalelo(THREADS_APROVACAO,
            () -> servico.processarFeedback(acao.getId(), true, "Aprovado apos leitura do escopo"));

        long enviosSimulados = mensagemClienteRegistroRepository
            .countByStatus(StatusMensagemCliente.SIMULADA);
        long respostasDeSucesso = respostas.stream().filter(m -> m != null && m.startsWith("✅")).count();
        List<String> respostasDeRecusa = respostas.stream()
            .filter(m -> m == null || !m.startsWith("✅"))
            .toList();

        assertTrue(respostas.stream().allMatch(m -> m != null && !m.isBlank()),
            "Toda aprovacao concorrente deve receber uma resposta de negocio, nunca excecao");
        assertEquals(THREADS_APROVACAO, statusLidosNaChecagem.size(),
            "As 10 threads devem ter passado pelo ponto de leitura do status da acao");
        assertTrue(statusLidosNaChecagem.stream().allMatch(status -> status == StatusAcaoPendente.PENDENTE),
            "As 10 threads devem ter lido o mesmo estado inicial (PENDENTE): a barreira na "
                + "leitura garante sobreposicao real e nao apenas largada conjunta; lidos="
                + statusLidosNaChecagem);
        verify(mensagemClienteGuardService, times(1))
            .registrarEnvioSimulado(any(), anyString());
        assertEquals(1, enviosSimulados,
            "Executor de envio deve registrar EXATAMENTE 1 envio para a mesma acao (zero envios "
                + "nao prova nada: pode ser falha antes do envio); registrados=" + enviosSimulados);
        assertEquals(1, respostasDeSucesso,
            "Somente a aprovacao vencedora pode retornar sucesso; sucessos=" + respostasDeSucesso);
        assertEquals(THREADS_APROVACAO - 1, respostasDeRecusa.size(),
            "As aprovacoes que nao enviaram devem receber recusa de negocio, nunca excecao; "
                + "recusas=" + respostasDeRecusa.size());
        assertTrue(respostasDeRecusa.stream()
                .allMatch(ProcessamentoDuplicadoConcorrenciaTest::ehRecusaDeAcaoJaTratada),
            "As demais aprovacoes devem receber a recusa de negocio 'ja aprovada/nao pendente' "
                + "(nao falha tecnica antes do envio); recebidas=" + respostasDeRecusa);

        StatusAcaoPendente statusFinal = acaoPendenteRepository.findById(acao.getId())
            .orElseThrow()
            .getStatus();
        assertTrue(statusFinal == StatusAcaoPendente.EXECUTADA
                || statusFinal == StatusAcaoPendente.APROVADA,
            "A acao deve terminar executada/aprovada pelo vencedor da aprovacao; status=" + statusFinal);
    }

    private AutoAtendimentoServiceImpl servicoComEscritaSimulada(boolean simularEnvio) {
        AutoAtendimentoServiceImpl servico = new AutoAtendimentoServiceImpl(
            avaliadorIaService,
            telegramService,
            historicoService,
            aprendizadoService,
            gerenciadorConexoesService,
            acaoPendenteRepository,
            interacaoRepository,
            projetoRepository,
            objectMapper,
            iaSafetyPolicy,
            operationalSafetyPolicy,
            mensagemClienteGuardService,
            qualidadeRascunhoService,
            modoEnsinamentoPolicyService,
            healthTracker,
            telegramMessageFormatter,
            marketplaceAdapterRegistry
        );
        if (simularEnvio) {
            ReflectionTestUtils.setField(servico, "realWriteSimulateEnabled", true);
        }
        return servico;
    }

    private Projeto novoProjetoConcorrencia() {
        return Projeto.builder()
            .id(idInternoConcorrencia())
            .plataforma(PlataformaFreela.FREELAS_99)
            .idExterno(PROJETO_ID_EXTERNO)
            .titulo("Landing page para clinica")
            .urlProjeto("https://www.99freelas.com.br/project/landing-page-para-clinica-" + PROJETO_ID_EXTERNO)
            .foiProcessado(false)
            .build();
    }

    private Long idInternoConcorrencia() {
        return PlataformaFreela.FREELAS_99.idInterno(PROJETO_ID_EXTERNO);
    }

    private AcaoPendente salvarAcaoPendenteDePergunta() {
        return acaoPendenteRepository.save(AcaoPendente.builder()
            .projetoId(idInternoConcorrencia())
            .plataforma(PlataformaFreela.FREELAS_99)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Landing page para clinica")
            .resumo("Confirmar escopo antes de responder")
            .payloadJson("""
                {
                  "projetoId":%s,
                  "tituloProjeto":"Landing page para clinica",
                  "mensagem":"Ola! Tenho disponibilidade para iniciar nesta semana. Voce pode me contar mais sobre o que precisa?",
                  "nivelAutonomia":"PERGUNTA_CLIENTE",
                  "entendimentoIa":"Projeto possivel, mas falta detalhar o escopo.",
                  "analiseIa":"Li o projeto e encontrei escopo aberto.",
                  "opcoesIa":["Responder a IA antes de enviar"],
                  "atitudeSugerida":"Conversar com voce antes de perguntar ao cliente.",
                  "conversaEnsinoObrigatoria":true,
                  "informacoesConfirmadas":["Titulo: Landing page para clinica"],
                  "informacoesPendentes":["Detalhar escopo antes da proposta."],
                  "sabeFazer":["rascunhar pergunta curta"],
                  "duvidasInternas":["confirmar se o escopo e requisito bloqueante"],
                  "perguntasParaCliente":["Voce pode me contar mais sobre o que precisa?"],
                  "riscosDecisao":["proposta sem escopo pode ficar imprecisa"],
                  "proximaAcaoSugerida":"Perguntar ao cliente se voce aprovar.",
                  "perguntaParaUsuario":"Essa pergunta esta certa para este projeto?",
                  "regraAprendizadoSugerida":"Quando faltar escopo, perguntar antes da proposta."
                }
                """.formatted(idInternoConcorrencia()))
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build());
    }

    private List<Interacao> interacoesAtendimentoDoProjeto() {
        return interacaoRepository.findByProjetoIdOrderByDataDesc(idInternoConcorrencia()).stream()
            .filter(interacao -> interacao.getTipo() == TipoInteracao.PROJETO_APROVADO
                || interacao.getTipo() == TipoInteracao.DUVIDA_ENVIADA)
            .toList();
    }

    /**
     * Recusa de negocio do perdedor ("⚠️ Ação X já foi tratada com status ...");
     * falha tecnica ("❌ ... falhou antes de enviar ...") NAO conta como recusa.
     */
    private static boolean ehRecusaDeAcaoJaTratada(String mensagem) {
        return mensagem != null && (mensagem.startsWith("⚠️") || mensagem.startsWith("🛑"))
            && (mensagem.contains("tratada") || mensagem.contains("pendente") || mensagem.contains("bloqueada pelas 3 regras"));
    }

    /**
     * Dispara {@code numeroThreads} copias de {@code tarefa}, todas destravadas
     * juntas por {@link CountDownLatch}; propaga a primeira falha de qualquer
     * tarefa e sempre encerra o pool.
     */
    private List<String> dispararEmParalelo(int numeroThreads, Supplier<String> tarefa) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(numeroThreads);
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<String>> futuros = new ArrayList<>(numeroThreads);
            for (int i = 0; i < numeroThreads; i++) {
                futuros.add(executor.submit(() -> {
                    largada.await();
                    return tarefa.get();
                }));
            }
            largada.countDown();
            List<String> resultados = new ArrayList<>(numeroThreads);
            for (Future<String> futuro : futuros) {
                resultados.add(futuro.get(45, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private void limparEstado() {
        mensagemClienteRegistroRepository.deleteAll();
        acaoPendenteRepository.deleteAll();
        aprendizadoRepository.deleteAll();
        configuracaoRepository.deleteAll();
        interacaoRepository.deleteAll();
        projetoRepository.deleteAll();
        try {
            Files.deleteIfExists(MEMORIA_CONCORRENCIA);
        } catch (Exception ignored) {
        }
    }
}
