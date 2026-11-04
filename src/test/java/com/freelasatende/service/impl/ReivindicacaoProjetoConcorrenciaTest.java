package com.freelasatende.service.impl;

import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.repository.ProjetoRepository;
import com.freelasatende.service.interfaceServico.HistoricoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Duas (ou mais) "instancias" disputando o mesmo projeto contra o MESMO banco, cada uma
 * na sua thread e transacao, largando juntas: a garantia vem da PK/indice unico e dos
 * UPDATE condicionais com contagem de linhas, nao de lock em memoria.
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
@DisplayName("Reivindicacao de projeto entre instancias concorrentes")
class ReivindicacaoProjetoConcorrenciaTest {

    private static final int INSTANCIAS = 8;
    private static final Long PROJETO_ID = 7_770_001L;

    @Autowired
    private ProjetoRepository projetoRepository;

    @Autowired
    private InteracaoRepository interacaoRepository;

    @Autowired
    private HistoricoService historicoService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    @AfterEach
    void limpar() {
        interacaoRepository.deleteAll();
        projetoRepository.deleteAll();
    }

    @RepeatedTest(3)
    @Timeout(60)
    @DisplayName("INSERT concorrente do mesmo projeto novo: so uma instancia vence, as outras esbarram na chave")
    void insertConcorrenteSoUmaVence() throws Exception {
        List<Boolean> resultados = disputar(() -> {
            try {
                projetoRepository.inserirReivindicacao(PROJETO_ID, PlataformaFreela.padrao().name(),
                    PlataformaFreela.padrao().idExterno(PROJETO_ID), "Projeto", null, agora());
                return true;
            } catch (DataIntegrityViolationException e) {
                assertTrue(ViolacaoProjetoUnico.ehDuplicado(e), "Perdedora deve ser violacao de chave de projetos");
                return false;
            }
        });

        assertEquals(1, resultados.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, projetoRepository.count());
    }

    @RepeatedTest(3)
    @Timeout(60)
    @DisplayName("UPDATE condicional concorrente num projeto gravado e livre: so uma instancia vence")
    void updateConcorrenteSoUmaVence() throws Exception {
        projetoRepository.inserirReivindicacao(PROJETO_ID, PlataformaFreela.padrao().name(),
            PlataformaFreela.padrao().idExterno(PROJETO_ID), "Projeto", null, agora().minusHours(1));

        List<Boolean> resultados = disputar(() -> projetoRepository.reivindicarProcessamento(
            PROJETO_ID, agora().minusMinutes(10), agora()) == 1);

        assertEquals(1, resultados.stream().filter(Boolean::booleanValue).count());
    }

    @Test
    @Timeout(60)
    @DisplayName("Instancia que perdeu a reivindicacao vencida nao consegue renova-la; a nova dona consegue")
    void instanciaQuePerdeuNaoRenova() {
        LocalDateTime tokenAntigo = agora().minusMinutes(20);
        projetoRepository.inserirReivindicacao(PROJETO_ID, PlataformaFreela.padrao().name(),
            PlataformaFreela.padrao().idExterno(PROJETO_ID), "Projeto", null, tokenAntigo);
        LocalDateTime tokenNovo = agora();

        assertEquals(1, projetoRepository.reivindicarProcessamento(PROJETO_ID, agora().minusMinutes(10), tokenNovo));
        assertEquals(0, projetoRepository.renovarReivindicacao(PROJETO_ID, tokenAntigo, agora()),
            "A instancia antiga perdeu o projeto e deve parar antes de qualquer efeito");
        assertEquals(1, projetoRepository.renovarReivindicacao(PROJETO_ID, tokenNovo, agora()));
    }

    @Test
    @Timeout(60)
    @DisplayName("Reivindicacao ativa (recente e nao concluida) nao e tomada, nem no reprocessamento explicito")
    void reivindicacaoAtivaNaoETomada() {
        projetoRepository.inserirReivindicacao(PROJETO_ID, PlataformaFreela.padrao().name(),
            PlataformaFreela.padrao().idExterno(PROJETO_ID), "Projeto", null, agora());

        assertEquals(0, projetoRepository.reivindicarProcessamento(PROJETO_ID, agora().minusMinutes(10), agora()));
        assertEquals(0, projetoRepository.reivindicarReprocessamentoExplicito(
            PROJETO_ID, agora().minusMinutes(10), agora()));
        Projeto gravado = projetoRepository.findById(PROJETO_ID).orElseThrow();
        assertEquals(Boolean.FALSE, gravado.getFoiProcessado());
    }

    @RepeatedTest(3)
    @Timeout(60)
    @DisplayName("Renovacao concorrente com o mesmo token: so uma instancia renova")
    void renovacaoConcorrenteSoUmaVence() throws Exception {
        LocalDateTime token = agora().minusSeconds(5);
        projetoRepository.inserirReivindicacao(PROJETO_ID, PlataformaFreela.padrao().name(),
            PlataformaFreela.padrao().idExterno(PROJETO_ID), "Projeto", null, token);
        java.util.concurrent.atomic.AtomicInteger sequencia = new java.util.concurrent.atomic.AtomicInteger();

        List<Boolean> resultados = disputar(() -> projetoRepository.renovarReivindicacao(
            PROJETO_ID, token, agora().plusNanos(1_000L * sequencia.incrementAndGet())) == 1);

        assertEquals(1, resultados.stream().filter(Boolean::booleanValue).count());
    }

    @RepeatedTest(3)
    @Timeout(60)
    @DisplayName("Gravacao final disputada pela dona antiga e pela nova: so a dona atual grava (fencing)")
    void gravacaoFinalSoDaDonaAtual() throws Exception {
        LocalDateTime tokenAntigo = agora().minusMinutes(20);
        LocalDateTime tokenAtual = agora();
        // A instancia nova ja tomou a reivindicacao vencida da antiga.
        projetoRepository.inserirReivindicacao(PROJETO_ID, PlataformaFreela.padrao().name(),
            PlataformaFreela.padrao().idExterno(PROJETO_ID), "Projeto", null, tokenAtual);
        java.util.concurrent.atomic.AtomicInteger vez = new java.util.concurrent.atomic.AtomicInteger();

        List<Boolean> resultados = disputar(() -> {
            boolean antiga = vez.getAndIncrement() % 2 == 0;
            ReservaDeProjeto.abrir(PROJETO_ID, antiga ? tokenAntigo : tokenAtual);
            try {
                historicoService.registrarInteracao(Interacao.builder().projetoId(PROJETO_ID)
                    .projetoTitulo("Projeto").tipo(TipoInteracao.PROJETO_APROVADO)
                    .conteudo(antiga ? "antiga" : "atual").build());
                return true;
            } catch (ReivindicacaoPerdidaException e) {
                return false;
            } finally {
                ReservaDeProjeto.fechar();
            }
        });

        assertEquals(INSTANCIAS / 2, resultados.stream().filter(Boolean::booleanValue).count());
        List<Interacao> gravadas = interacaoRepository.findByProjetoIdOrderByDataDesc(PROJETO_ID);
        assertEquals(INSTANCIAS / 2, gravadas.size());
        assertTrue(gravadas.stream().allMatch(interacao -> "atual".equals(interacao.getConteudo())),
            "Nenhuma gravacao da instancia que perdeu a reivindicacao pode entrar");
    }

    @Test
    @Timeout(60)
    @DisplayName("Entidade gerenciada (EntityManager compartilhado) suja nao regrava o token de outra instancia")
    void entidadeGerenciadaNaoRegravaTokenDeOutraInstancia() throws Exception {
        LocalDateTime tokenAntigo = agora().minusMinutes(20);
        projetoRepository.inserirReivindicacao(PROJETO_ID, PlataformaFreela.padrao().name(),
            PlataformaFreela.padrao().idExterno(PROJETO_ID), "Projeto", null, tokenAntigo);
        LocalDateTime tokenOutra = agora();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // Requisicao com contexto de persistencia aberto: a entidade fica gerenciada.
            Projeto gerenciado = entityManager.find(Projeto.class, PROJETO_ID);
            // Outra instancia (outra conexao/transacao) toma a reivindicacao vencida.
            try {
                disputarUma(() -> projetoRepository.reivindicarProcessamento(
                    PROJETO_ID, agora().minusMinutes(10), tokenOutra) == 1);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            // Alteracao qualquer na entidade gerenciada + fencing com o token antigo: o
            // flush automatico antes do UPDATE nao pode regravar data_processamento.
            gerenciado.setStatus(com.freelasatende.model.enums.StatusProjeto.EM_ANALISE);
            assertEquals(0, projetoRepository.confirmarDonoDaReivindicacao(PROJETO_ID, tokenAntigo));
        });

        assertEquals(tokenOutra, projetoRepository.findById(PROJETO_ID).orElseThrow().getDataProcessamento(),
            "O token da instancia que assumiu continua gravado");
    }

    @Test
    @Timeout(60)
    @DisplayName("Entidade gerenciada com data de processamento alterada pelo chamador grava com posse valida")
    void entidadeGerenciadaAlteradaPeloChamadorGravaComPosse() {
        LocalDateTime token = agora();
        projetoRepository.inserirReivindicacao(PROJETO_ID, PlataformaFreela.padrao().name(),
            PlataformaFreela.padrao().idExterno(PROJETO_ID), "Projeto", null, token);
        ReservaDeProjeto.abrir(PROJETO_ID, token);
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                // Como marcarProjetoComoAprovado: entidade gerenciada, data e estado alterados.
                Projeto gerenciado = entityManager.find(Projeto.class, PROJETO_ID);
                gerenciado.setStatus(com.freelasatende.model.enums.StatusProjeto.APROVADO);
                gerenciado.setFoiProcessado(true);
                gerenciado.setDataProcessamento(LocalDateTime.now());
                projetoRepository.save(gerenciado);
            });
        } finally {
            ReservaDeProjeto.fechar();
        }

        Projeto gravado = projetoRepository.findById(PROJETO_ID).orElseThrow();
        assertEquals(com.freelasatende.model.enums.StatusProjeto.APROVADO, gravado.getStatus());
        assertEquals(token, gravado.getDataProcessamento(), "A data gravada continua sendo o token da reserva");
    }

    private void disputarUma(Callable<Boolean> tarefa) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertTrue(executor.submit(tarefa).get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static LocalDateTime agora() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** Roda a mesma tarefa em INSTANCIAS threads destravadas juntas; devolve o resultado de cada uma. */
    private List<Boolean> disputar(Callable<Boolean> tarefa) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(INSTANCIAS);
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futuros = new ArrayList<>();
            for (int i = 0; i < INSTANCIAS; i++) {
                futuros.add(executor.submit(() -> {
                    largada.await();
                    return tarefa.call();
                }));
            }
            largada.countDown();
            List<Boolean> resultados = new ArrayList<>();
            for (Future<Boolean> futuro : futuros) {
                resultados.add(futuro.get(45, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
