package com.freelasatende.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.policy.OperationalSafetyPolicy;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.repository.ProjetoRepository;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.MensagemClienteGuardService;
import com.freelasatende.service.interfaceServico.TelegramService;
import com.freelasatende.util.IaSafetyPolicy;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
class AutoAtendimentoHandlerFactory {

    interface ExecutarAcao {
        void executar(AcaoPendente acao) throws Exception;
    }

    interface RegistrarInteracaoPlataforma {
        void registrar(
            PlataformaFreela plataforma,
            Long projetoId,
            String tituloProjeto,
            TipoInteracao tipo,
            String conteudo,
            ModoSistema modo
        );
    }

    interface RegistrarInteracaoProjeto {
        void registrar(Projeto projeto, TipoInteracao tipo, String conteudo, ModoSistema modo);
    }

    interface CriarAcaoConversa {
        AcaoPendente criar(ConversaResumo conversa, String resumo, String payloadJson, String textoCliente, ModoSistema modo);
    }

    interface CriarAcaoConversaRevisaoHumana {
        AcaoPendente criar(ConversaResumo conversa, String resumo, String payloadJson, ModoSistema modo);
    }

    interface CriarAcaoProjeto {
        AcaoPendente criar(
            Projeto projeto,
            TipoAcaoPendente tipo,
            String resumo,
            String payloadJson,
            String textoCliente,
            ModoSistema modo
        );
    }

    interface ValidarRascunho {
        boolean validar(
            PlataformaFreela plataforma,
            Long projetoId,
            Long conversaId,
            TipoAcaoPendente tipo,
            String mensagem,
            ModoSistema modo,
            String tituloProjeto
        );
    }

    interface AcaoConsumer {
        void aceitar(AcaoPendente acao);
    }

    interface MargemConexao {
        boolean temMargem(Projeto projeto);
    }

    record Callbacks(
        ExecutarAcao executarAcao,
        RegistrarInteracaoPlataforma registrarInteracaoPlataforma,
        RegistrarInteracaoProjeto registrarInteracaoProjeto,
        AcaoConsumer descartarRascunhos,
        CriarAcaoConversa criarAcaoConversa,
        CriarAcaoConversaRevisaoHumana criarAcaoConversaRevisaoHumana,
        CriarAcaoProjeto criarAcaoProjeto,
        AcaoConsumer notificarAcao,
        AcaoConsumer apagarMensagemTelegram,
        ValidarRascunho validarRascunho,
        MargemConexao margemConexao
    ) {
    }

    private final ObjectMapper objectMapper;
    private final MarketplaceAdapterRegistry marketplaceAdapterRegistry;
    private final AvaliadorIaService avaliadorIaService;
    private final TelegramService telegramService;
    private final com.freelasatende.service.interfaceServico.HistoricoService historicoService;
    private final AcaoPendenteRepository acaoPendenteRepository;
    private final InteracaoRepository interacaoRepository;
    private final ProjetoRepository projetoRepository;
    private final OperationalSafetyPolicy operationalSafetyPolicy;
    private final GerenciadorConexoesService gerenciadorConexoes;
    private final IaSafetyPolicy iaSafetyPolicy;
    private final MensagemClienteGuardService mensagemClienteGuardService;
    private final com.freelasatende.service.interfaceServico.QualidadeRascunhoService qualidadeRascunhoService;
    private final TelegramMessageFormatter telegramMessageFormatter;
    private final boolean realWriteEnabled;
    private final boolean realWriteSimulateEnabled;

    AutoAtendimentoFeedbackHandler feedbackHandler(
        AutoAtendimentoAprendizadoHelper aprendizadoHelper,
        Callbacks callbacks
    ) {
        return new AutoAtendimentoFeedbackHandler(
            acaoPendenteRepository,
            objectMapper,
            avaliadorIaService,
            operationalSafetyPolicy,
            mensagemClienteGuardService,
            aprendizadoHelper,
            new AutoAtendimentoFeedbackHandler.Hooks() {
                @Override
                public void executarAcao(AcaoPendente acao) throws Exception {
                    callbacks.executarAcao().executar(acao);
                }

                @Override
                public void registrarInteracao(
                    AcaoPendente acao,
                    TipoInteracao tipo,
                    String conteudo
                ) {
                    callbacks.registrarInteracaoPlataforma().registrar(
                        acao.getPlataforma(),
                        acao.getProjetoId(),
                        acao.getTituloProjeto(),
                        tipo,
                        conteudo,
                        acao.getModoOrigem()
                    );
                }

                @Override
                public void descartarRascunhosPendentes(AcaoPendente acao) {
                    callbacks.descartarRascunhos().aceitar(acao);
                }
            },
            realWriteEnabled,
            realWriteSimulateEnabled
        );
    }

    AutoAtendimentoConversaHandler conversaHandler(Callbacks callbacks) {
        return new AutoAtendimentoConversaHandler(
            marketplaceAdapterRegistry,
            avaliadorIaService,
            acaoPendenteRepository,
            interacaoRepository,
            projetoRepository,
            objectMapper,
            operationalSafetyPolicy,
            new AutoAtendimentoConversaHandler.Hooks() {
                @Override
                public AcaoPendente criarOuRecuperarAcaoConversa(
                    ConversaResumo conversa,
                    String resumo,
                    String payloadJson,
                    String textoCliente,
                    ModoSistema modo
                ) {
                    return callbacks.criarAcaoConversa().criar(conversa, resumo, payloadJson, textoCliente, modo);
                }

                @Override
                public AcaoPendente criarOuRecuperarAcaoConversaRevisaoHumana(
                    ConversaResumo conversa,
                    String resumo,
                    String payloadJson,
                    ModoSistema modo
                ) {
                    return callbacks.criarAcaoConversaRevisaoHumana().criar(conversa, resumo, payloadJson, modo);
                }

                @Override
                public void notificarAcaoPendente(AcaoPendente acao) {
                    callbacks.notificarAcao().aceitar(acao);
                }

                @Override
                public void alertarContatoLiberado(String mensagem) {
                    telegramService.enviarNotificacao(mensagem);
                }

                @Override
                public void apagarMensagemTelegramDaAcao(AcaoPendente acao) {
                    callbacks.apagarMensagemTelegram().aceitar(acao);
                }

                @Override
                public void descartarRascunhosPendentes(AcaoPendente acao) {
                    callbacks.descartarRascunhos().aceitar(acao);
                }

                @Override
                public void executarAcao(AcaoPendente acao) throws JsonProcessingException {
                    try {
                        callbacks.executarAcao().executar(acao);
                    } catch (JsonProcessingException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException("Falha ao executar acao pendente: " + mensagemCausaRaiz(e), e);
                    }
                }

                @Override
                public void registrarInteracao(
                    PlataformaFreela plataforma,
                    Long projetoId,
                    String tituloProjeto,
                    TipoInteracao tipo,
                    String conteudo,
                    ModoSistema modo
                ) {
                    callbacks.registrarInteracaoPlataforma().registrar(
                        plataforma,
                        projetoId,
                        tituloProjeto,
                        tipo,
                        conteudo,
                        modo
                    );
                }
            },
            realWriteEnabled,
            realWriteSimulateEnabled
        );
    }

    AutoAtendimentoProjetoHandler projetoHandler(Callbacks callbacks) {
        return new AutoAtendimentoProjetoHandler(
            objectMapper,
            acaoPendenteRepository,
            projetoRepository,
            operationalSafetyPolicy,
            marketplaceAdapterRegistry,
            gerenciadorConexoes,
            new AutoAtendimentoProjetoHandler.Hooks() {
                @Override
                public AcaoPendente criarOuRecuperarAcaoProjeto(
                    Projeto projeto,
                    TipoAcaoPendente tipo,
                    String resumo,
                    String payloadJson,
                    String textoCliente,
                    ModoSistema modo
                ) {
                    return callbacks.criarAcaoProjeto().criar(projeto, tipo, resumo, payloadJson, textoCliente, modo);
                }

                @Override
                public void registrarInteracao(Projeto projeto, TipoInteracao tipo, String conteudo, ModoSistema modo) {
                    callbacks.registrarInteracaoProjeto().registrar(projeto, tipo, conteudo, modo);
                }

                @Override
                public void notificarAcaoPendente(AcaoPendente acao) {
                    callbacks.notificarAcao().aceitar(acao);
                }

                @Override
                public boolean validarERegistrarRascunho(
                    PlataformaFreela plataforma,
                    Long projetoId,
                    Long conversaId,
                    TipoAcaoPendente tipo,
                    String mensagem,
                    ModoSistema modo,
                    String tituloProjeto
                ) {
                    return callbacks.validarRascunho()
                        .validar(plataforma, projetoId, conversaId, tipo, mensagem, modo, tituloProjeto);
                }

                @Override
                public void executarAcao(AcaoPendente acao) throws JsonProcessingException {
                    try {
                        callbacks.executarAcao().executar(acao);
                    } catch (JsonProcessingException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException("Falha ao executar acao pendente", e);
                    }
                }

                @Override
                public boolean temMargemParaGastarConexao(Projeto projeto) {
                    return callbacks.margemConexao().temMargem(projeto);
                }
            },
            realWriteEnabled,
            realWriteSimulateEnabled
        );
    }

    private String mensagemCausaRaiz(Throwable erro) {
        Throwable atual = erro;
        Throwable raiz = erro;
        while (atual != null) {
            raiz = atual;
            atual = atual.getCause();
        }
        String mensagem = raiz != null ? raiz.getMessage() : null;
        if (mensagem == null || mensagem.isBlank()) {
            return raiz != null ? raiz.getClass().getSimpleName() : "erro sem detalhe";
        }
        return raiz.getClass().getSimpleName() + ": " + mensagem;
    }

    AutoAtendimentoActionExecutor actionExecutor() {
        return new AutoAtendimentoActionExecutor(
            objectMapper,
            operationalSafetyPolicy,
            marketplaceAdapterRegistry,
            gerenciadorConexoes,
            new AutoAtendimentoActionHooks(
                marketplaceAdapterRegistry,
                telegramService,
                historicoService,
                projetoRepository,
                iaSafetyPolicy,
                operationalSafetyPolicy,
                mensagemClienteGuardService,
                qualidadeRascunhoService,
                telegramMessageFormatter,
                realWriteEnabled,
                realWriteSimulateEnabled
            ),
            realWriteEnabled,
            realWriteSimulateEnabled
        );
    }
}
