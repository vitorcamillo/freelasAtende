package com.freelasatende.service.impl;

import com.freelasatende.model.dto.AvaliacaoResultado;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Cliente;
import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.StatusProjeto;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.policy.OperationalSafetyPolicy;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.repository.ProjetoRepository;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
class AutoAtendimentoProjetoProcessor {

    private static final Duration WORKANA_ENVIO_BLOQUEADO_COOLDOWN = Duration.ofHours(6);
    /**
     * O limite de conexoes da Workana e semanal: insistir a cada 6h so gasta ciclo e nao
     * destrava nada. A pausa dura ate a renovacao provavel da semana.
     */
    private static final Duration WORKANA_SEM_CONEXOES_COOLDOWN = Duration.ofDays(7);
    /** Por quanto tempo a reivindicacao de um projeto barra outra instancia. */
    private static final Duration PRAZO_REIVINDICACAO = Duration.ofMinutes(10);
    private static final String RESUMO_ABORDAGEM_INICIAL_AUTOMATICA = "Abordagem inicial automatica | ";
    private static final String OBSERVACAO_SESSAO_INVALIDA = "falha de sessao/autorizacao do marketplace";
    private static final String OBSERVACAO_SEM_CONEXOES = "limite semanal de conexoes do Workana";

    interface Hooks {
        Projeto enriquecerProjeto(Projeto projeto);
        void criarAbordagemInicialProjeto(Projeto projeto, ModoSistema modo) throws Exception;
        void criarPropostaProjeto(Projeto projeto, ModoSistema modo) throws Exception;
        void registrarInteracao(Projeto projeto, TipoInteracao tipo, String conteudo, ModoSistema modo);
        boolean clienteAprovado(Cliente cliente);
    }

    private final AvaliadorIaService avaliadorIaService;
    private final MarketplaceAdapterRegistry marketplaceAdapterRegistry;
    private final ProjetoRepository projetoRepository;
    private final InteracaoRepository interacaoRepository;
    private final AcaoPendenteRepository acaoPendenteRepository;
    private final GerenciadorConexoesService gerenciadorConexoes;
    private final OperationalSafetyPolicy operationalSafetyPolicy;
    private final SystemHealthTracker healthTracker;
    private final Hooks hooks;
    private final boolean realWriteEnabled;
    private final boolean realWriteSimulateEnabled;

    Projeto reconstruirProjetoComErroTecnico(Long projetoId) {
        return interacaoRepository.findByProjetoIdOrderByDataDesc(projetoId).stream()
            .filter(interacao -> interacao.getProjetoTitulo() != null && !interacao.getProjetoTitulo().isBlank())
            .findFirst()
            .map(interacao -> Projeto.builder()
                .id(projetoId)
                .plataforma(PlataformaProjetoHelper.plataformaOuPadrao(interacao.getPlataforma()))
                .idExterno(PlataformaProjetoHelper.plataformaOuPadrao(interacao.getPlataforma()).idExterno(projetoId))
                .titulo(interacao.getProjetoTitulo())
                .urlProjeto("/project/projeto-" + projetoId)
                .foiProcessado(false)
                .build())
            .orElseGet(() -> Projeto.builder()
                .id(projetoId)
                .titulo("Projeto " + projetoId)
                .urlProjeto("/project/projeto-" + projetoId)
                .foiProcessado(false)
                .build());
    }

    void processarProjetoInterno(Projeto projeto, ModoSistema modo, boolean reavaliacaoExplicita) {
        PlataformaProjetoHelper.normalizarProjeto(projeto);
        try {
            if (limiteConexoesBloqueia(projeto, modo)) {
                log.info("Abordagem inicial do projeto {} aguardando renovacao do limite diario de conexoes",
                    projeto.getId());
                return;
            }

            if (envioWorkanaBloqueadoRecentemente(projeto, modo)) {
                return;
            }

            if (!reivindicarProjeto(projeto, reavaliacaoExplicita)) {
                // ACHADO-FA-CONC-02-1: outra instancia (ex.: rolling deploy) ja esta tratando
                // este projeto; nenhuma avaliacao, acao, interacao ou notificacao desta execucao.
                log.info("Projeto {} reivindicado por outra execucao; seguindo sem novo atendimento.",
                    projeto.getId());
                return;
            }

            Projeto detalhado = enriquecerComRetryDeadlock(projeto);
            PlataformaProjetoHelper.normalizarProjeto(detalhado);
            healthTracker.registrarSucesso("SCRAPER");

            AvaliacaoResultado avaliacao = reavaliacaoExplicita
                ? avaliadorIaService.reavaliarProjeto(detalhado)
                : avaliadorIaService.avaliarProjeto(detalhado);
            healthTracker.registrarSucesso("IA");
            if (!confirmarReivindicacao(detalhado)) {
                log.info("Projeto {} perdeu a reivindicacao para outra execucao durante a avaliacao; sem efeitos.",
                    detalhado.getId());
                return;
            }
            if (!avaliacao.isCapaz()) {
                detalhado.setStatus(StatusProjeto.IGNORADO);
                detalhado.setMotivoRecusa(avaliacao.getMotivoRecusa());
                detalhado.setFoiProcessado(true);
                detalhado.setDataProcessamento(LocalDateTime.now());
                projetoRepository.save(detalhado);
                String motivoLog = avaliacao.getMotivoRecusa() != null
                    ? avaliacao.getMotivoRecusa() : "sem motivo especificado";
                hooks.registrarInteracao(detalhado, TipoInteracao.PROJETO_IGNORADO,
                    "Projeto ignorado: " + motivoLog, modo);
                return;
            }

            MarketplaceAdapter adapter = marketplaceAdapterRegistry.para(detalhado.getPlataforma());
            Cliente cliente = buscarClienteSeDisponivel(adapter, detalhado);
            if (!hooks.clienteAprovado(cliente)) {
                log.debug("Reputacao do cliente nao bloqueia abordagem inicial do projeto {}", detalhado.getId());
            }

            if (!adapter.capabilities().perguntas() && adapter.capabilities().propostas()) {
                hooks.criarPropostaProjeto(detalhado, modo);
            } else {
                hooks.criarAbordagemInicialProjeto(detalhado, modo);
            }
        } catch (Exception e) {
            // O registro de erro abaixo e nao conclusivo: grava fora da reserva.
            ReservaDeProjeto.fechar();
            if (ReivindicacaoPerdidaException.naCadeia(e)) {
                // Outra instancia tomou o projeto durante a avaliacao: a gravacao fenced foi
                // recusada e nada desta execucao fica registrado.
                log.info("Projeto {} perdeu a reivindicacao durante a avaliacao; sem efeitos.", projeto.getId());
                return;
            }
            if (e instanceof DataIntegrityViolationException && ViolacaoProjetoUnico.ehDuplicado(e)) {
                // ACHADO-FA-CONC-02-1: outro thread/instancia ja gravou este projeto
                // (restricao unica plataforma+id externo). Nao e erro: retorna sem
                // registrar interacao ou acao nova. Outras violacoes seguem como erro.
                log.info("Projeto {} ja gravado por outra execucao (restricao unica); seguindo sem novo atendimento.",
                    projeto.getId());
                return;
            }
            healthTracker.registrarErro("IA", "processar-projeto-" + projeto.getId(), e.getMessage());
            log.error("Erro ao processar projeto {}: {}", projeto.getId(), e.getMessage(), e);
            hooks.registrarInteracao(projeto, TipoInteracao.ERRO_SISTEMA,
                "Falha no processamento do projeto: " + e.getMessage(), modo);
            if (reavaliacaoExplicita) {
                throw new IllegalStateException("Falha no reprocessamento explicito", e);
            }
        } finally {
            ReservaDeProjeto.fechar();
        }
    }

    /**
     * MySQL pode devolver 1213 (deadlock) na transacao curta de enriquecimento.
     * O scheduler nao deve matar o ciclo inteiro por isso: retry curto com espera
     * pequena resolve o caso tipico de duas threads disputando o mesmo projeto.
     */
    /**
     * Reivindica o projeto no banco antes de qualquer efeito (acao, interacao, envio), para
     * a deduplicacao valer entre instancias e nao so na JVM. Feita antes da avaliacao,
     * porque o ramo de projeto ignorado tambem grava projeto e interacao. Projeto novo:
     * INSERT minimo (a concorrente esbarra na PK/indice unico). Ja gravado: reserva com
     * prazo que nao toma uma reivindicacao ativa de outra instancia; concluido so e
     * retomado no reprocessamento explicito do operador. Perdeu a disputa = false.
     */
    private boolean reivindicarProjeto(Projeto projeto, boolean reavaliacaoExplicita) {
        // Token da reivindicacao: precisao da coluna (datetime(6)) para a comparacao exata bater.
        LocalDateTime agora = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int gravados;
        if (projetoRepository.existsById(projeto.getId())) {
            LocalDateTime expiraAntesDe = agora.minus(PRAZO_REIVINDICACAO);
            gravados = reavaliacaoExplicita
                ? projetoRepository.reivindicarReprocessamentoExplicito(projeto.getId(), expiraAntesDe, agora)
                : projetoRepository.reivindicarProcessamento(projeto.getId(), expiraAntesDe, agora);
            if (gravados == 0) {
                return false;
            }
        } else {
            PlataformaFreela plataforma = PlataformaProjetoHelper.plataformaOuPadrao(projeto);
            try {
                gravados = projetoRepository.inserirReivindicacao(
                    projeto.getId(),
                    plataforma.name(),
                    projeto.getIdExterno() != null ? projeto.getIdExterno() : plataforma.idExterno(projeto.getId()),
                    projeto.getTitulo() != null ? projeto.getTitulo() : "Projeto " + projeto.getId(),
                    projeto.getStatus() != null ? projeto.getStatus().name() : null,
                    agora
                );
            } catch (DataIntegrityViolationException e) {
                if (ViolacaoProjetoUnico.ehDuplicado(e)) {
                    return false;
                }
                throw e;
            }
        }
        // O token fica so na reserva da thread, nunca na entidade (que pode estar gerenciada
        // pelo Open EntityManager in View; o Projeto usa @DynamicUpdate para um flush de outra
        // alteracao nao regravar data_processamento/foi_processado). Daqui ate o fim, toda
        // gravacao do projeto passa pelo fencing de GuardaDePosseAspect.
        if (gravados > 0) {
            // So ha posse a defender se a reivindicacao gravou de fato no banco.
            ReservaDeProjeto.abrir(projeto.getId(), agora);
        }
        return true;
    }

    /**
     * Depois da avaliacao, confirma que esta execucao ainda e dona da reivindicacao (token
     * da reserva) e a renova. Zero linhas = posse perdida (tomada ou projeto removido).
     */
    private boolean confirmarReivindicacao(Projeto detalhado) {
        LocalDateTime token = ReservaDeProjeto.tokenAtual(detalhado.getId());
        if (token == null) {
            return true;
        }
        LocalDateTime novo = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        if (projetoRepository.renovarReivindicacao(detalhado.getId(), token, novo) == 0) {
            return false;
        }
        ReservaDeProjeto.atualizarToken(novo);
        return true;
    }

    private Projeto enriquecerComRetryDeadlock(Projeto projeto) {
        IllegalStateException ultimaFalha = null;
        for (int tentativa = 1; tentativa <= 3; tentativa++) {
            try {
                return hooks.enriquecerProjeto(projeto);
            } catch (CannotAcquireLockException e) {
                ultimaFalha = new IllegalStateException("Banco ocupado ao atualizar projeto; deadlock", e);
                log.warn("Deadlock ao enriquecer projeto {}/3: {}", tentativa, e.getMessage());
                try {
                    Thread.sleep(250L * tentativa);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrompido durante retry de projeto", ie);
                }
            }
        }
        throw ultimaFalha != null ? ultimaFalha : new IllegalStateException("Falha ao enriquecer projeto");
    }

    private boolean limiteConexoesBloqueia(Projeto projeto, ModoSistema modo) {
        if (!realWriteEnabled
            || !operationalSafetyPolicy.allowsAutomaticClientSend(modo, realWriteEnabled, realWriteSimulateEnabled)) {
            return false;
        }
        MarketplaceAdapter adapter = marketplaceAdapterRegistry.para(PlataformaProjetoHelper.plataformaOuPadrao(projeto));
        return adapter.capabilities().conexoes() && !gerenciadorConexoes.temConexoesDisponiveis();
    }

    private boolean envioWorkanaBloqueadoRecentemente(Projeto projeto, ModoSistema modo) {
        if (!realWriteEnabled
            || !operationalSafetyPolicy.allowsAutomaticClientSend(modo, realWriteEnabled, realWriteSimulateEnabled)
            || PlataformaProjetoHelper.plataformaOuPadrao(projeto) != PlataformaFreela.WORKANA) {
            return false;
        }

        Optional<AcaoPendente> ultimaFalha = acaoPendenteRepository
            .findFirstByPlataformaAndTipoAndStatusOrderByDataAtualizacaoDesc(
                PlataformaFreela.WORKANA,
                TipoAcaoPendente.ENVIAR_PERGUNTA,
                StatusAcaoPendente.ERRO
            )
            .filter(this::falhaInicialWorkanaPorAutorizacao);
        if (ultimaFalha.isEmpty()) {
            return false;
        }

        LocalDateTime dataFalha = Optional.ofNullable(ultimaFalha.get().getDataAtualizacao())
            .orElse(ultimaFalha.get().getDataCriacao());
        Duration cooldown = falhaInicialWorkanaPorLimiteConexoes(ultimaFalha.get())
            ? WORKANA_SEM_CONEXOES_COOLDOWN
            : WORKANA_ENVIO_BLOQUEADO_COOLDOWN;
        if (dataFalha == null || dataFalha.isBefore(LocalDateTime.now().minus(cooldown))) {
            return false;
        }

        log.warn(
            "Workana com envio inicial bloqueado por falha recente de autorizacao; projeto {} nao sera avaliado pela IA nesta rodada. ultimaAcao={} falhaEm={} cooldownHoras={}",
            projeto.getId(),
            ultimaFalha.get().getId(),
            dataFalha,
            cooldown.toHours()
        );
        return true;
    }

    private boolean falhaInicialWorkanaPorAutorizacao(AcaoPendente acao) {
        String resumo = acao.getResumo() != null ? acao.getResumo() : "";
        String observacao = acao.getObservacaoUsuario() != null ? acao.getObservacaoUsuario() : "";
        return resumo.startsWith(RESUMO_ABORDAGEM_INICIAL_AUTOMATICA)
            && (observacao.contains(OBSERVACAO_SESSAO_INVALIDA)
                || observacao.contains(OBSERVACAO_SEM_CONEXOES));
    }

    private boolean falhaInicialWorkanaPorLimiteConexoes(AcaoPendente acao) {
        String observacao = acao.getObservacaoUsuario() != null ? acao.getObservacaoUsuario() : "";
        return observacao.contains(OBSERVACAO_SEM_CONEXOES);
    }

    private Cliente buscarClienteSeDisponivel(MarketplaceAdapter adapter, Projeto projeto) {
        try {
            return adapter.buscarCliente(PlataformaProjetoHelper.idExternoOuId(projeto));
        } catch (MarketplaceAdapterException e) {
            if (e.getType() == MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL) {
                log.debug("Adapter {} sem consulta de cliente; seguindo sem reputacao externa",
                    adapter.plataforma());
                return null;
            }
            throw e;
        }
    }
}
