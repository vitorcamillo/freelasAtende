package com.freelasatende.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Cliente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
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
import com.freelasatende.service.interfaceServico.AprendizadoService;
import com.freelasatende.service.interfaceServico.AutoAtendimentoService;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.HistoricoService;
import com.freelasatende.service.interfaceServico.MensagemClienteGuardService;
import com.freelasatende.service.interfaceServico.ModoEnsinamentoPolicyService;
import com.freelasatende.service.interfaceServico.QualidadeRascunhoService;
import com.freelasatende.service.interfaceServico.TelegramService;
import com.freelasatende.util.IaSafetyPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoAtendimentoServiceImpl implements AutoAtendimentoService {

    private final AvaliadorIaService avaliadorIaService;
    private final TelegramService telegramService;
    private final HistoricoService historicoService;
    private final AprendizadoService aprendizadoService;
    private final com.freelasatende.service.interfaceServico.GerenciadorConexoesService gerenciadorConexoes;
    private final AcaoPendenteRepository acaoPendenteRepository;
    private final InteracaoRepository interacaoRepository;
    private final ProjetoRepository projetoRepository;
    private final ObjectMapper objectMapper;
    private final IaSafetyPolicy iaSafetyPolicy;
    private final OperationalSafetyPolicy operationalSafetyPolicy;
    private final MensagemClienteGuardService mensagemClienteGuardService;
    private final QualidadeRascunhoService qualidadeRascunhoService;
    private final ModoEnsinamentoPolicyService modoEnsinamentoPolicyService;
    private final SystemHealthTracker healthTracker;
    private final TelegramMessageFormatter telegramMessageFormatter;
    private final MarketplaceAdapterRegistry marketplaceAdapterRegistry;
    @Value("${sistema.min-conexoes-reserva:700}")
    private int minConexoesReserva;
    @Value("${sistema.min-media-cliente:3.5}")
    private double minMediaCliente;
    @Value("${sistema.bloquear-cliente-sem-avaliacao:true}")
    private boolean bloquearClienteSemAvaliacao;
    @Value("${real.write.enabled:false}")
    private boolean realWriteEnabled;
    @Value("${real.write.simulate:false}")
    private boolean realWriteSimulateEnabled;
    private volatile AutoAtendimentoInboxSynchronizer inboxSynchronizer;

    private AutoAtendimentoAprendizadoHelper aprendizadoHelper() {
        return new AutoAtendimentoAprendizadoHelper(
            aprendizadoService,
            qualidadeRascunhoService,
            modoEnsinamentoPolicyService
        );
    }

    private AutoAtendimentoAcaoPendenteManager acaoPendenteManager() {
        return new AutoAtendimentoAcaoPendenteManager(
            acaoPendenteRepository,
            objectMapper,
            mensagemClienteGuardService,
            qualidadeRascunhoService,
            telegramService,
            telegramMessageFormatter,
            this::registrarInteracao
        );
    }

    private AutoAtendimentoHandlerFactory handlerFactory() {
        return new AutoAtendimentoHandlerFactory(
            objectMapper,
            marketplaceAdapterRegistry(),
            avaliadorIaService,
            telegramService,
            historicoService,
            acaoPendenteRepository,
            interacaoRepository,
            projetoRepository,
            operationalSafetyPolicy,
            gerenciadorConexoes,
            iaSafetyPolicy,
            mensagemClienteGuardService,
            qualidadeRascunhoService,
            telegramMessageFormatter,
            realWriteEnabled,
            realWriteSimulateEnabled
        );
    }

    private AutoAtendimentoHandlerFactory.Callbacks handlerCallbacks() {
        return new AutoAtendimentoHandlerFactory.Callbacks(
            this::executarAcao,
            this::registrarInteracao,
            this::registrarInteracao,
            this::descartarRascunhosPendentes,
            this::criarOuRecuperarAcaoConversa,
            this::criarOuRecuperarAcaoConversaRevisaoHumana,
            this::criarOuRecuperarAcaoProjeto,
            this::notificarAcaoPendente,
            this::apagarMensagemTelegramDaAcao,
            this::validarERegistrarRascunho,
            this::temMargemParaGastarConexao
        );
    }

    private AutoAtendimentoProjetoProcessor projetoProcessor() {
        return new AutoAtendimentoProjetoProcessor(
            avaliadorIaService, marketplaceAdapterRegistry(), projetoRepository, interacaoRepository,
            acaoPendenteRepository, gerenciadorConexoes, operationalSafetyPolicy, healthTracker,
            new AutoAtendimentoProjetoProcessor.Hooks() {
                @Override
                public Projeto enriquecerProjeto(Projeto projeto) { return AutoAtendimentoServiceImpl.this.enriquecerProjeto(projeto); }

                @Override
                public void criarAbordagemInicialProjeto(Projeto projeto, ModoSistema modo) throws Exception { AutoAtendimentoServiceImpl.this.criarAbordagemInicialProjeto(projeto, modo); }

                @Override
                public void criarPropostaProjeto(Projeto projeto, ModoSistema modo) throws Exception { AutoAtendimentoServiceImpl.this.criarPropostaProjeto(projeto, modo); }

                @Override
                public void registrarInteracao(Projeto projeto, TipoInteracao tipo, String conteudo, ModoSistema modo) { AutoAtendimentoServiceImpl.this.registrarInteracao(projeto, tipo, conteudo, modo); }

                @Override
                public boolean clienteAprovado(Cliente cliente) { return AutoAtendimentoServiceImpl.this.clienteAprovado(cliente); }
            },
            realWriteEnabled,
            realWriteSimulateEnabled
        );
    }

    private AutoAtendimentoFeedbackHandler feedbackHandler() {
        return handlerFactory().feedbackHandler(aprendizadoHelper(), handlerCallbacks());
    }

    private AutoAtendimentoConversaHandler conversaHandler() {
        return handlerFactory().conversaHandler(handlerCallbacks());
    }

    private AutoAtendimentoProjetoHandler projetoHandler() {
        return handlerFactory().projetoHandler(handlerCallbacks());
    }

    private AutoAtendimentoInboxSynchronizer inboxSynchronizer() {
        AutoAtendimentoInboxSynchronizer atual = inboxSynchronizer;
        if (atual == null) {
            synchronized (this) {
                atual = inboxSynchronizer;
                if (atual == null) {
                    atual = new AutoAtendimentoInboxSynchronizer(marketplaceAdapterRegistry(), this::processarConversa);
                    inboxSynchronizer = atual;
                }
            }
        }
        return atual;
    }

    @Override
    public void processarProjeto(Projeto projeto, ModoSistema modo) {
        PlataformaProjetoHelper.normalizarProjeto(projeto);
        if (projeto == null || projeto.getId() == null) {
            return;
        }
        if (historicoService.projetoJaProcessado(projeto.getPlataforma(), projeto.getId())
                && !AdiamentoPorLimiteLocal.pendente(acaoPendenteRepository, projeto)) {
            log.trace("Projeto {} já foi processado anteriormente, pulando.", projeto.getId());
            return;
        }

        processarProjetoInterno(projeto, modo, false);
    }

    @Override
    public String reprocessarProjetoIgnorado(Long projetoId, ModoSistema modo) {
        if (projetoId == null) {
            return "Projeto invalido para reprocessamento.";
        }

        Optional<Projeto> encontrado = projetoRepository.findById(projetoId);
        boolean somenteErroTecnico = !historicoService.projetoJaProcessado(projetoId);
        if (encontrado.isEmpty() && !somenteErroTecnico) {
            return "Projeto nao encontrado para reprocessamento: " + projetoId + ".";
        }

        Projeto projeto = encontrado.orElseGet(() -> reconstruirProjetoComErroTecnico(projetoId));
        if (projeto.getStatus() != StatusProjeto.IGNORADO && !somenteErroTecnico) {
            return "Projeto " + projetoId + " nao esta ignorado; reprocessamento nao executado.";
        }

        if (realWriteEnabled
            && operationalSafetyPolicy.allowsAutomaticClientSend(modo, realWriteEnabled, realWriteSimulateEnabled)
            && !gerenciadorConexoes.temConexoesDisponiveis()) {
            return "Projeto " + projetoId + " aguardando renovacao do limite diario de conexoes.";
        }

        try {
            processarProjetoInterno(projeto, modo, true);
        } catch (IllegalStateException e) {
            return "Erro ao reprocessar projeto " + projetoId + "; consulte os logs operacionais.";
        }
        Projeto atualizado = projetoRepository.findById(projetoId).orElse(projeto);
        if (atualizado.getStatus() == StatusProjeto.EM_ANALISE) {
            return "Projeto " + projetoId + " reprocessado pelo fluxo seguro de abordagem inicial.";
        }
        return "Projeto " + projetoId + " reavaliado e mantido ignorado: "
            + (atualizado.getMotivoRecusa() != null ? atualizado.getMotivoRecusa() : "fora do escopo atual")
            + ".";
    }

    private Projeto reconstruirProjetoComErroTecnico(Long projetoId) {
        return projetoProcessor().reconstruirProjetoComErroTecnico(projetoId);
    }

    private void processarProjetoInterno(Projeto projeto, ModoSistema modo, boolean reavaliacaoExplicita) {
        projetoProcessor().processarProjetoInterno(projeto, modo, reavaliacaoExplicita);
    }

    @Override
    public void sincronizarInbox(ModoSistema modo) {
        sincronizarInbox(modo, Integer.MAX_VALUE);
    }

    @Override
    public void sincronizarInbox(ModoSistema modo, int limiteConversas) {
        sincronizarInbox(modo, limiteConversas, null);
    }

    @Override
    public void sincronizarInbox(ModoSistema modo, int limiteConversas, Collection<PlataformaFreela> plataformas) {
        inboxSynchronizer().sincronizar(modo, limiteConversas, plataformas);
    }

    @Override
    public String processarFeedback(Long acaoId, boolean aprovado, String comentario) {
        return feedbackHandler().processarFeedback(acaoId, aprovado, comentario);
    }

    @Override
    public String reescreverAcao(Long acaoId, String novoTexto, String comentario) {
        return feedbackHandler().reescreverAcao(acaoId, novoTexto, comentario);
    }

    @Override
    public String registrarContextoEnsino(Long acaoId, String orientacaoUsuario) {
        return feedbackHandler().registrarContextoEnsino(acaoId, orientacaoUsuario);
    }

    @Override
    public String responderDuvidaIa(Long acaoId, String respostaUsuario, String regraConfirmada) {
        return feedbackHandler().responderDuvidaIa(acaoId, respostaUsuario, regraConfirmada);
    }

    @Override
    public long contarAcoesPendentes() {
        return acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE);
    }

    private void tratarProposta(Projeto projeto, Proposta proposta, ModoSistema modo) throws JsonProcessingException {
        projetoHandler().tratarProposta(projeto, proposta, modo);
    }

    private synchronized void criarAbordagemInicialProjeto(Projeto projeto, ModoSistema modo) throws JsonProcessingException {
        projetoHandler().criarAbordagemInicialProjeto(projeto, modo);
    }

    private synchronized void criarPropostaProjeto(Projeto projeto, ModoSistema modo) throws JsonProcessingException {
        Proposta proposta = avaliadorIaService.sugerirProposta(projeto);
        if (proposta == null || proposta.getValorProposto() == null) {
            throw new IllegalStateException("IA nao gerou valor para a proposta");
        }
        proposta.setProjetoId(projeto.getId());
        if (proposta.getMensagem() == null || proposta.getMensagem().isBlank()) {
            proposta.setMensagem(avaliadorIaService.gerarTextoProposta(projeto, proposta));
        }
        if (proposta.getMensagem() == null || proposta.getMensagem().isBlank()) {
            proposta.setMensagem(com.freelasatende.util.AtendimentoTextos.abordagemInicial());
        }
        tratarProposta(projeto, proposta, modo);
    }

    private void tratarDuvidas(Projeto projeto, List<String> duvidas, ModoSistema modo) throws JsonProcessingException {
        projetoHandler().tratarDuvidas(projeto, duvidas, modo);
    }

    /**
     * Modo ENSINAMENTO: cria ação pendente para tudo, esperando aprovação do usuário.
     */
    private void criarAcaoPendenteAprendizado(Projeto projeto, List<String> duvidas, Proposta proposta, ModoSistema modo) {
        projetoHandler().criarAcaoPendenteAprendizado(projeto, duvidas, proposta, modo);
    }

    private void processarConversa(ConversaResumo conversa, ModoSistema modo) throws JsonProcessingException {
        conversaHandler().processarConversa(conversa, modo);
    }

    private Projeto enriquecerProjeto(Projeto projeto) {
        PlataformaProjetoHelper.normalizarProjeto(projeto);
        return AutoAtendimentoProjetoSupport.enriquecerProjeto(
            marketplaceAdapterRegistry().para(projeto.getPlataforma()),
            projeto
        );
    }

    private synchronized AcaoPendente criarOuRecuperarAcaoProjeto(
        Projeto projeto,
        TipoAcaoPendente tipo,
        String resumo,
        String payloadJson,
        String textoCliente,
        ModoSistema modo
    ) {
        return acaoPendenteManager().criarOuRecuperarAcaoProjeto(
            projeto,
            tipo,
            resumo,
            payloadJson,
            textoCliente,
            modo
        );
    }

    private synchronized AcaoPendente criarOuRecuperarAcaoConversa(
        ConversaResumo conversa,
        String resumo,
        String payloadJson,
        String textoCliente,
        ModoSistema modo
    ) {
        return acaoPendenteManager().criarOuRecuperarAcaoConversa(
            conversa,
            resumo,
            payloadJson,
            textoCliente,
            modo
        );
    }

    private synchronized AcaoPendente criarOuRecuperarAcaoConversaRevisaoHumana(
        ConversaResumo conversa,
        String resumo,
        String payloadJson,
        ModoSistema modo
    ) {
        return acaoPendenteManager().criarOuRecuperarAcaoConversaRevisaoHumana(
            conversa,
            resumo,
            payloadJson,
            modo
        );
    }

    private void notificarAcaoPendente(AcaoPendente acao) {
        acaoPendenteManager().notificarAcaoPendente(acao);
    }

    private void apagarMensagemTelegramDaAcao(AcaoPendente acao) {
        acaoPendenteManager().apagarMensagemTelegramDaAcao(acao);
    }

    private boolean validarERegistrarRascunho(
        PlataformaFreela plataforma,
        Long projetoId,
        Long conversaId,
        TipoAcaoPendente tipo,
        String textoCliente,
        ModoSistema modo,
        String tituloProjeto
    ) {
        acaoPendenteManager().validarERegistrarMensagem(
            plataforma,
            projetoId,
            conversaId,
            tipo,
            textoCliente,
            modo,
            tituloProjeto
        );
        return true;
    }

    private AutoAtendimentoActionExecutor actionExecutor() {
        return handlerFactory().actionExecutor();
    }

    private void executarAcao(AcaoPendente acao) throws JsonProcessingException {
        actionExecutor().executar(acao);
    }

    private void registrarInteracao(Projeto projeto, TipoInteracao tipo, String conteudo, ModoSistema modo) {
        registrarInteracao(PlataformaProjetoHelper.plataformaOuPadrao(projeto), projeto.getId(), projeto.getTitulo(), tipo, conteudo, modo);
    }

    private void registrarInteracao(
        PlataformaFreela plataforma,
        Long projetoId,
        String tituloProjeto,
        TipoInteracao tipo,
        String conteudo,
        ModoSistema modo
    ) {
        historicoService.registrarInteracao(com.freelasatende.model.entity.Interacao.builder()
            .plataforma(PlataformaProjetoHelper.plataformaOuPadrao(plataforma))
            .projetoId(projetoId)
            .projetoTitulo(tituloProjeto)
            .tipo(tipo)
            .conteudo(conteudo)
            .modoUsado(modo)
            .build());
    }

    private boolean propostaTemValorReal(Projeto projeto, Proposta proposta) {
        return AutoAtendimentoProjetoSupport.propostaTemValorReal(projeto, proposta);
    }

    private boolean temMargemParaGastarConexao(Projeto projeto) {
        return AutoAtendimentoProjetoSupport.temMargemParaGastarConexao(
            marketplaceAdapterRegistry().para(PlataformaProjetoHelper.plataformaOuPadrao(projeto)),
            minConexoesReserva
        );
    }

    private MarketplaceAdapterRegistry marketplaceAdapterRegistry() {
        return marketplaceAdapterRegistry;
    }

    private boolean clienteAprovado(Cliente cliente) {
        return AutoAtendimentoProjetoSupport.clienteAprovado(
            cliente,
            bloquearClienteSemAvaliacao,
            minMediaCliente
        );
    }

    private void descartarRascunhosPendentes(AcaoPendente acao) {
        acaoPendenteManager().descartarRascunhosPendentes(acao);
    }

    @Override
    public void otimizarMemoriaContexto() {
        try {
            log.info("Otimizando memoria de contexto da IA...");
            avaliadorIaService.otimizarMemoriaContexto();
            telegramService.enviarNotificacao("🧠 *Otimização*: Memória da IA sumarizada com sucesso!");
        } catch (Exception e) {
            log.error("Erro ao otimizar memoria: {}", e.getMessage());
        }
    }

    @Override
    public void cancelarPropostasAntigas(int dias) {
        new AutoAtendimentoCancelamentoHelper(
            historicoService,
            acaoPendenteRepository,
            telegramService,
            objectMapper,
            new AutoAtendimentoCancelamentoHelper.Hooks() {
                @Override
                public void registrarInteracao(
                    PlataformaFreela plataforma,
                    Long projetoId,
                    String tituloProjeto,
                    TipoInteracao tipo,
                    String conteudo,
                    ModoSistema modo
                ) {
                    AutoAtendimentoServiceImpl.this.registrarInteracao(plataforma, projetoId, tituloProjeto, tipo, conteudo, modo);
                }

                @Override
                public void notificarAcaoPendente(AcaoPendente acao) {
                    AutoAtendimentoServiceImpl.this.notificarAcaoPendente(acao);
                }
            }
        ).cancelarPropostasAntigas(dias);
    }

}
