package com.freelasatende.service.impl;

import com.freelasatende.model.entity.Configuracao;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.repository.ConfiguracaoRepository;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provas de concorrencia para {@link GerenciadorConexoesServiceImpl} (fatia FA-CONC-01).
 *
 * <p>As conexoes do 99Freelas sao creditos pagos com limite diario. O servico faz
 * leitura-modificacao-escrita de {@code Configuracao.conexoesUsadasHoje} sem transacao,
 * lock pessimista ou {@code @Version} (a entidade nao tem versao otimista), e
 * {@code temConexoesDisponiveis()} e checado em chamada separada de
 * {@code registrarUsoConexao()} — o mesmo padrao check-then-act de
 * {@code AutoAtendimentoProjetoProcessor#limiteConexoesBloqueia} e
 * {@code AutoAtendimentoActionExecutor#enviarPergunta}. Em producao esses caminhos
 * rodam em threads distintas (scheduler de polling + aprovacao de acao via API).
 *
 * <p>Os cenarios concorrentes usam {@link CountDownLatch} de largada e rodam 3x
 * para nao depender de sorte. Banco: o H2 em memoria (MODE=MySQL) que o projeto
 * ja usa nos testes — pode esconder o problema em relacao ao MySQL de producao.
 *
 * <p><b>ACHADO-FA-CONC-01-1</b> e <b>ACHADO-FA-CONC-01-2</b>: os dois cenarios
 * concorrentes estao desabilitados porque o codigo de producao viola as
 * invariantes por construcao — leitura-modificacao-escrita sem transacao/lock/
 * {@code @Version} ({@code registrarUsoConexao}) e check-then-act entre metodos
 * separados ({@code temConexoesDisponiveis} + {@code registrarUsoConexao}).
 * As assercoes permanecem com o comportamento CORRETO e servem de gate de
 * regressao para a correcao; reabilitar os testes quando o servico tornar o
 * incremento atomico e a checagem de limite parte do mesmo atomo.
 */
@SpringBootTest(properties = {
    "scheduling.enabled=false",
    "ia.api-key=",
    "ia.base-url=https://api.local.invalid",
    "ia.model=test-local",
    "telegram.bot-token=",
    "telegram.chat-id="
})
@ActiveProfiles("test")
@DisplayName("GerenciadorConexoesServiceImpl sob concorrencia")
class GerenciadorConexoesConcorrenciaTest {

    private static final int LIMITE_ALTO_CENARIO = 200;
    private static final int LIMITE_DIARIO_CENARIO = 5;
    private static final int USOS_SEQUENCIAIS = 10;
    private static final int THREADS_INCREMENTO = 50;
    private static final int THREADS_LIMITE = 20;
    private static final int REPETICOES_CONCORRENTES = 3;

    @Autowired
    private ConfiguracaoRepository configuracaoRepository;

    @Autowired
    private GerenciadorConexoesService gerenciadorConexoesService;

    @BeforeEach
    void prepararCenario() {
        configuracaoRepository.deleteAll();
    }

    @AfterEach
    void limparDados() {
        configuracaoRepository.deleteAll();
    }

    @Test
    @Timeout(60)
    @DisplayName("Deve contar 10 usos sequenciais sem perder incremento")
    void deveContarDezUsosSequenciaisSemPerda() {
        salvarConfiguracao(LIMITE_ALTO_CENARIO, 0, LocalDateTime.now());

        for (int i = 0; i < USOS_SEQUENCIAIS; i++) {
            gerenciadorConexoesService.registrarUsoConexao();
        }

        assertEquals(USOS_SEQUENCIAIS, conexoesUsadasAtuais(),
            "Contador diario deve somar todos os usos sequenciais");
    }

    @RepeatedTest(REPETICOES_CONCORRENTES)
    @Timeout(60)
    @DisplayName("Deve registrar 50 incrementos concorrentes sem perder nenhum")
    void deveRegistrarCinquentaIncrementosConcorrentesSemPerda() throws Exception {
        salvarConfiguracao(LIMITE_ALTO_CENARIO, 0, LocalDateTime.now());

        executarConcorrente(THREADS_INCREMENTO, THREADS_INCREMENTO,
            gerenciadorConexoesService::registrarUsoConexao);

        assertEquals(THREADS_INCREMENTO, conexoesUsadasAtuais(),
            "Nenhum incremento pode ser perdido: a leitura-modificacao-escrita de "
                + "conexoesUsadasHoje precisa ser atomica sob concorrencia");
    }

    @RepeatedTest(REPETICOES_CONCORRENTES)
    @Timeout(60)
    @DisplayName("Deve respeitar limite diario de 5 com 20 threads checando e registrando juntas")
    void deveRespeitarLimiteDiarioComCheckERegistroConcorrentes() throws Exception {
        salvarConfiguracao(LIMITE_DIARIO_CENARIO, 0, LocalDateTime.now());
        AtomicInteger usosRegistrados = new AtomicInteger();

        executarConcorrente(THREADS_LIMITE, THREADS_LIMITE, () -> {
            if (gerenciadorConexoesService.reservarConexao()) {
                usosRegistrados.incrementAndGet();
            }
        });

        assertTrue(usosRegistrados.get() <= LIMITE_DIARIO_CENARIO,
            "Check-then-act concorrente nao pode registrar mais usos que o limite diario L="
                + LIMITE_DIARIO_CENARIO + "; registrados=" + usosRegistrados.get());
        assertTrue(conexoesUsadasAtuais() <= LIMITE_DIARIO_CENARIO,
            "Contador persistido nao pode passar do limite diario L="
                + LIMITE_DIARIO_CENARIO + "; contador=" + conexoesUsadasAtuais());
    }

    @Test
    @Timeout(60)
    @DisplayName("Deve zerar contador do dia anterior antes de registrar o primeiro uso de hoje")
    void deveZerarContadorDoDiaAnteriorAntesDeRegistrarPrimeiroUsoDeHoje() {
        salvarConfiguracao(LIMITE_ALTO_CENARIO, 150, LocalDateTime.now().minusDays(1));

        gerenciadorConexoesService.registrarUsoConexao();

        Configuracao config = configuracaoRepository.findFirstByOrderByIdAsc().orElseThrow();
        assertEquals(1, config.getConexoesUsadasHoje(),
            "Primeiro uso de hoje deve resetar o saldo de ontem antes de somar");
        assertEquals(LocalDate.now(), config.getDataUltimoReset().toLocalDate(),
            "Reset diario deve registrar a data de hoje");
    }

    @RepeatedTest(REPETICOES_CONCORRENTES)
    @Timeout(60)
    @DisplayName("Na virada do dia, 20 reservas concorrentes zeram o contador uma vez e respeitam o limite")
    void viradaDoDiaComReservasConcorrentesNaoUltrapassaLimite() throws Exception {
        // Contador de ontem cheio: o reset atrasado de uma thread nao pode apagar reservas de hoje.
        salvarConfiguracao(LIMITE_DIARIO_CENARIO, LIMITE_DIARIO_CENARIO, LocalDateTime.now().minusDays(1));
        AtomicInteger reservas = new AtomicInteger();

        executarConcorrente(THREADS_LIMITE, THREADS_LIMITE, () -> {
            if (gerenciadorConexoesService.reservarConexao()) {
                reservas.incrementAndGet();
            }
        });

        assertEquals(LIMITE_DIARIO_CENARIO, reservas.get(),
            "Exatamente L reservas devem vencer no novo dia; reservas=" + reservas.get());
        assertEquals(LIMITE_DIARIO_CENARIO, conexoesUsadasAtuais(),
            "Contador persistido deve bater com as reservas aceitas");
    }

    @Test
    @Timeout(60)
    @DisplayName("Marcar sessao renovada nao regrava o contador de conexoes")
    void marcarSessaoRenovadaNaoRegravaContador() {
        salvarConfiguracao(LIMITE_ALTO_CENARIO, 0, LocalDateTime.now());
        Configuracao antiga = configuracaoRepository.findFirstByOrderByIdAsc().orElseThrow();
        assertTrue(gerenciadorConexoesService.reservarConexao());
        assertTrue(gerenciadorConexoesService.reservarConexao());

        gerenciadorConexoesService.marcarSessaoRenovada();

        Configuracao atual = configuracaoRepository.findFirstByOrderByIdAsc().orElseThrow();
        assertEquals(2, atual.getConexoesUsadasHoje());
        assertTrue(atual.getDataUltimaRenovacao() != null
            && !atual.getDataUltimaRenovacao().equals(antiga.getDataUltimaRenovacao()));
    }

    @Test
    @Timeout(60)
    @DisplayName("Devolucao de reserva feita antes do reset diario nao desconta do dia novo")
    void devolucaoDeReservaAnteriorAoResetNaoDescontaDoDiaNovo() {
        LocalDateTime reservadaOntem = LocalDateTime.now().minusDays(1);
        salvarConfiguracao(LIMITE_DIARIO_CENARIO, 0, LocalDateTime.now().minusDays(1).minusHours(1));
        assertTrue(gerenciadorConexoesService.reservarConexao());
        assertTrue(gerenciadorConexoesService.reservarConexao());

        gerenciadorConexoesService.devolverConexao(reservadaOntem);
        assertEquals(2, conexoesUsadasAtuais(), "Reserva de ontem nao pode liberar vaga de hoje");

        gerenciadorConexoesService.devolverConexao(LocalDateTime.now());
        assertEquals(1, conexoesUsadasAtuais(), "Reserva de hoje que falhou devolve a vaga");
    }

    @Test
    @Timeout(60)
    @DisplayName("Reserva com parametros de antes da meia-noite, executada depois do reset, conta no dia novo")
    void reservaIntercaladaComResetNaViradaNaoSePerde() {
        LocalDateTime meiaNoite = LocalDate.now().atStartOfDay();
        // Outra thread ja fez o reset de hoje: contador do dia novo em 2.
        salvarConfiguracao(LIMITE_DIARIO_CENARIO, 2, meiaNoite.plusSeconds(1));
        Long id = configuracaoRepository.findFirstByOrderByIdAsc().orElseThrow().getId();

        // Esta thread montou "agora" um instante antes da meia-noite (dia anterior).
        LocalDateTime antesDaMeiaNoite = meiaNoite.minusNanos(1_000);
        int reservas = configuracaoRepository.reservarConexaoAtomica(
            id, LIMITE_DIARIO_CENARIO, antesDaMeiaNoite, antesDaMeiaNoite.toLocalDate().atStartOfDay());

        assertEquals(1, reservas);
        assertEquals(3, conexoesUsadasAtuais(), "A reserva entra no contador de hoje, sem ser zerada");
    }

    @Test
    @Timeout(60)
    @DisplayName("Primeira reserva do dia zera o contador de ontem na mesma instrucao")
    void primeiraReservaDoDiaZeraNaMesmaInstrucao() {
        salvarConfiguracao(LIMITE_DIARIO_CENARIO, LIMITE_DIARIO_CENARIO, LocalDateTime.now().minusDays(1));
        Long id = configuracaoRepository.findFirstByOrderByIdAsc().orElseThrow().getId();
        LocalDateTime agora = LocalDateTime.now();

        int reservas = configuracaoRepository.reservarConexaoAtomica(
            id, LIMITE_DIARIO_CENARIO, agora, agora.toLocalDate().atStartOfDay());

        assertEquals(1, reservas);
        assertEquals(1, conexoesUsadasAtuais());
        assertEquals(LocalDate.now(),
            configuracaoRepository.findFirstByOrderByIdAsc().orElseThrow().getDataUltimoReset().toLocalDate());
    }

    private void salvarConfiguracao(int limiteDiario, int conexoesUsadasHoje, LocalDateTime ultimoReset) {
        configuracaoRepository.save(Configuracao.builder()
            .modoSistema(ModoSistema.REGULAR)
            .pollingIntervaloSegundos(30)
            .ativo(true)
            .limiteConexoesDiario(limiteDiario)
            .conexoesUsadasHoje(conexoesUsadasHoje)
            .dataUltimoReset(ultimoReset)
            .build());
    }

    private int conexoesUsadasAtuais() {
        return configuracaoRepository.findFirstByOrderByIdAsc().orElseThrow().getConexoesUsadasHoje();
    }

    /**
     * Dispara {@code totalTarefas} copias de {@code tarefa} em {@code numeroThreads}
     * threads, todas destravadas juntas por {@link CountDownLatch}; propaga a primeira
     * falha de qualquer tarefa e sempre encerra o pool.
     */
    private void executarConcorrente(int numeroThreads, int totalTarefas, Runnable tarefa) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(numeroThreads);
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<?>> futuros = new ArrayList<>(totalTarefas);
            for (int i = 0; i < totalTarefas; i++) {
                futuros.add(executor.submit(() -> {
                    largada.await();
                    tarefa.run();
                    return null;
                }));
            }
            largada.countDown();
            for (Future<?> futuro : futuros) {
                futuro.get(45, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
