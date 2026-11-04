package com.freelasatende.service;

import com.freelasatende.model.dto.AvaliacaoResultado;
import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoInteracao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoAtendimentoReivindicacaoELimiteLocalTest extends AutoAtendimentoServiceImplTestSupport {

    @Test
    @DisplayName("Reserva recusada pelo limite diario local adia o projeto sem envio e sem bloqueio definitivo")
    void limiteDiarioLocalAdiaProjetoSemBloquear() {
        habilitarEscritaRealMockada();
        when(gerenciadorConexoesService.temConexoesDisponiveis()).thenReturn(true);
        when(gerenciadorConexoesService.reservarConexao()).thenReturn(false);
        Long projetoId = sequencia.incrementAndGet();

        autoAtendimentoService.processarProjeto(projetoBase(projetoId), ModoSistema.REGULAR);

        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        assertEquals(0, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        assertEquals(0, acaoPendenteRepository.countByStatus(StatusAcaoPendente.EXECUTADA));
        assertNotEquals(Boolean.TRUE, projetoRepository.findById(projetoId).map(Projeto::getFoiProcessado).orElse(null),
            "Projeto adiado nao pode ficar marcado como processado");
        assertEquals(1, acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.ERRO).stream()
            .filter(acao -> acao.getObservacaoUsuario() != null
                && acao.getObservacaoUsuario().contains("Limite diario local"))
            .count(), "A acao automatica registra o adiamento pelo limite local");
        assertEquals(0, interacaoRepository.findByProjetoIdOrderByDataDesc(projetoId).stream()
            .filter(interacao -> interacao.getTipo() == TipoInteracao.ERRO_SISTEMA).count(),
            "Limite local nao e erro de sistema");

        // O avaliador real registra PROJETO_APROVADO, que o historico considera conclusivo.
        interacaoRepository.save(Interacao.builder().plataforma(PlataformaFreela.padrao()).projetoId(projetoId)
            .projetoTitulo("Site institucional").tipo(TipoInteracao.PROJETO_APROVADO)
            .conteudo("IA avaliou: SIM").data(LocalDateTime.now()).modoUsado(ModoSistema.REGULAR).build());

        // Dia seguinte: contador renovado e reivindicacao vencida; o projeto e enviado.
        Projeto gravado = projetoRepository.findById(projetoId).orElseThrow();
        gravado.setDataProcessamento(LocalDateTime.now().minusDays(1));
        projetoRepository.save(gravado);
        when(gerenciadorConexoesService.reservarConexao()).thenReturn(true);

        autoAtendimentoService.processarProjeto(projetoBase(projetoId), ModoSistema.REGULAR);

        verify(scraperService, times(1)).enviarPergunta(eq(projetoId), anyString());
    }

    @Test
    @DisplayName("Projeto reivindicado ha pouco por outra instancia nao gera acao nem envio")
    void projetoReivindicadoPorOutraInstanciaNaoGeraEfeitos() {
        habilitarEscritaRealMockada();
        when(gerenciadorConexoesService.temConexoesDisponiveis()).thenReturn(true);
        Long projetoId = sequencia.incrementAndGet();
        // A outra instancia (rolling deploy) reivindicou este projeto agora.
        projetoRepository.inserirReivindicacao(projetoId, PlataformaFreela.padrao().name(),
            PlataformaFreela.padrao().idExterno(projetoId), "Outra instancia", null, LocalDateTime.now());

        autoAtendimentoService.processarProjeto(projetoBase(projetoId), ModoSistema.REGULAR);

        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        assertEquals(0, acaoPendenteRepository.count());
        assertEquals(0, interacaoRepository.findByProjetoIdOrderByDataDesc(projetoId).size());
    }

    @Test
    @DisplayName("Avaliacao negativa tambem respeita a reivindicacao de outra instancia")
    void avaliacaoNegativaRespeitaReivindicacao() {
        when(avaliadorIaService.avaliarProjeto(any(Projeto.class)))
            .thenReturn(AvaliacaoResultado.builder().capaz(false).motivoRecusa("Fora do escopo").build());
        Long projetoId = sequencia.incrementAndGet();
        projetoRepository.inserirReivindicacao(projetoId, PlataformaFreela.padrao().name(),
            PlataformaFreela.padrao().idExterno(projetoId), "Outra instancia", null, LocalDateTime.now());

        autoAtendimentoService.processarProjeto(projetoBase(projetoId), ModoSistema.REGULAR);

        verify(avaliadorIaService, never()).avaliarProjeto(any(Projeto.class));
        assertEquals(0, interacaoRepository.findByProjetoIdOrderByDataDesc(projetoId).size());
        assertEquals("Outra instancia", projetoRepository.findById(projetoId).orElseThrow().getTitulo());
    }

    @Test
    @DisplayName("Projeto concluido por outra instancia nao e retomado pelo fluxo normal")
    void projetoConcluidoNaoERetomadoPeloFluxoNormal() {
        Long projetoId = sequencia.incrementAndGet();
        projetoRepository.inserirReivindicacao(projetoId, PlataformaFreela.padrao().name(),
            PlataformaFreela.padrao().idExterno(projetoId), "Concluido", null, LocalDateTime.now().minusHours(1));
        Projeto concluido = projetoRepository.findById(projetoId).orElseThrow();
        concluido.setFoiProcessado(true);
        projetoRepository.save(concluido);

        autoAtendimentoService.processarProjeto(projetoBase(projetoId), ModoSistema.REGULAR);

        verify(avaliadorIaService, never()).avaliarProjeto(any(Projeto.class));
        assertEquals(0, interacaoRepository.findByProjetoIdOrderByDataDesc(projetoId).size());
    }

    @Test
    @DisplayName("Reprocessamento explicito espelha a reivindicacao na entidade (sem foiProcessado antigo)")
    void reprocessamentoExplicitoEspelhaReivindicacaoNaEntidade() {
        Long projetoId = sequencia.incrementAndGet();
        projetoRepository.inserirReivindicacao(projetoId, PlataformaFreela.padrao().name(),
            PlataformaFreela.padrao().idExterno(projetoId), "Ignorado", null, LocalDateTime.now().minusHours(1));
        Projeto ignorado = projetoRepository.findById(projetoId).orElseThrow();
        ignorado.setFoiProcessado(true);
        projetoRepository.save(ignorado);
        AtomicReference<Boolean> foiProcessadoDuranteAvaliacao = new AtomicReference<>();
        when(avaliadorIaService.reavaliarProjeto(any(Projeto.class))).thenAnswer(chamada -> {
            foiProcessadoDuranteAvaliacao.set(projetoRepository.findById(projetoId).orElseThrow().getFoiProcessado());
            return AvaliacaoResultado.builder().capaz(false).motivoRecusa("Fora do escopo").build();
        });

        autoAtendimentoService.reprocessarProjetoIgnorado(projetoId, ModoSistema.REGULAR);

        assertEquals(Boolean.FALSE, foiProcessadoDuranteAvaliacao.get(),
            "Durante o reprocessamento o projeto fica em andamento (foiProcessado=false) no banco");
    }

    @Test
    @DisplayName("Duas instancias processando o mesmo projeto novo ao mesmo tempo: um unico envio")
    void duasInstanciasProcessandoMesmoProjetoEnviamUmaVez() throws Exception {
        habilitarEscritaRealMockada();
        when(gerenciadorConexoesService.temConexoesDisponiveis()).thenReturn(true);
        Long projetoId = sequencia.incrementAndGet();
        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futuros = List.of(
                executor.submit(() -> { largada.await(); autoAtendimentoService.processarProjeto(projetoBase(projetoId), ModoSistema.REGULAR); return null; }),
                executor.submit(() -> { largada.await(); autoAtendimentoService.processarProjeto(projetoBase(projetoId), ModoSistema.REGULAR); return null; }));
            largada.countDown();
            for (Future<?> futuro : futuros) {
                futuro.get(45, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        verify(scraperService, times(1)).enviarPergunta(eq(projetoId), anyString());
        assertEquals(1, acaoPendenteRepository.countByStatus(StatusAcaoPendente.EXECUTADA));
    }

    @Test
    @DisplayName("Ultima conexao consumida por outra execucao durante a avaliacao adia o projeto, sem pendencia humana")
    void ultimaConexaoConsumidaDuranteAvaliacaoAdiaProjeto() {
        habilitarEscritaRealMockada();
        // Livre na checagem do inicio do processamento; esgotada quando a abordagem vai enviar.
        when(gerenciadorConexoesService.temConexoesDisponiveis()).thenReturn(true, false);
        Long projetoId = sequencia.incrementAndGet();

        autoAtendimentoService.processarProjeto(projetoBase(projetoId), ModoSistema.REGULAR);

        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        assertEquals(0, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        assertEquals(1, acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.ERRO).stream()
            .filter(acao -> acao.getObservacaoUsuario() != null
                && acao.getObservacaoUsuario().contains("Limite diario local"))
            .count());
        assertNotEquals(Boolean.TRUE, projetoRepository.findById(projetoId).map(Projeto::getFoiProcessado).orElse(null));
    }

    private Projeto projetoBase(Long id) {
        Projeto projeto = projetoBase();
        projeto.setId(id);
        return projeto;
    }
}
