package com.freelasatende.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.policy.OperationalSafetyPolicy;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

final class AutoAtendimentoActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(AutoAtendimentoActionExecutor.class);

    interface Hooks {
        void validarConteudoSeguroParaEnvio(
            PlataformaFreela plataforma,
            Long projetoId,
            Long conversaId,
            TipoAcaoPendente tipo,
            String mensagem,
            String contexto
        );

        void validarRespostaConversaAindaAtual(RespostaPayload payload);

        boolean deveSimularEnvioReal();

        void registrarEnvioSimuladoCliente(
            AcaoPendente acao,
            PlataformaFreela plataforma,
            Long projetoId,
            Long conversaId,
            TipoAcaoPendente tipo,
            String mensagem,
            String tituloProjeto
        );

        void marcarProjetoComoAprovado(
            PlataformaFreela plataforma,
            Long projetoId,
            String tituloProjeto,
            com.freelasatende.model.enums.ModoSistema modo
        );

        void registrarInteracao(Projeto projeto, TipoInteracao tipo, String conteudo, com.freelasatende.model.enums.ModoSistema modo);

        void registrarInteracao(
            PlataformaFreela plataforma,
            Long projetoId,
            String tituloProjeto,
            TipoInteracao tipo,
            String conteudo,
            com.freelasatende.model.enums.ModoSistema modo
        );

        void registrarEnvioCliente(
            PlataformaFreela plataforma,
            Long projetoId,
            Long conversaId,
            TipoAcaoPendente tipo,
            String mensagem
        );

        /**
         * Imediatamente antes de um envio externo de um projeto reivindicado nesta thread,
         * confirma e renova a reserva no banco; perdeu = ReivindicacaoPerdidaException e
         * nada e enviado. Sem reserva (ex.: aprovacao do operador) nao faz nada.
         */
        default void confirmarDonoAntesDoEnvio(Long projetoId) {
        }
    }

    private final ObjectMapper objectMapper;
    private final OperationalSafetyPolicy operationalSafetyPolicy;
    private final MarketplaceAdapterRegistry marketplaceAdapterRegistry;
    private final GerenciadorConexoesService gerenciadorConexoes;
    private final Hooks hooks;
    private final boolean realWriteEnabled;
    private final boolean realWriteSimulateEnabled;

    AutoAtendimentoActionExecutor(
        ObjectMapper objectMapper,
        OperationalSafetyPolicy operationalSafetyPolicy,
        MarketplaceAdapterRegistry marketplaceAdapterRegistry,
        GerenciadorConexoesService gerenciadorConexoes,
        Hooks hooks,
        boolean realWriteEnabled,
        boolean realWriteSimulateEnabled
    ) {
        this.objectMapper = objectMapper;
        this.operationalSafetyPolicy = operationalSafetyPolicy;
        this.marketplaceAdapterRegistry = marketplaceAdapterRegistry;
        this.gerenciadorConexoes = gerenciadorConexoes;
        this.hooks = hooks;
        this.realWriteEnabled = realWriteEnabled;
        this.realWriteSimulateEnabled = realWriteSimulateEnabled;
    }

    void executar(AcaoPendente acao) throws JsonProcessingException {
        if (!operationalSafetyPolicy.clientWritePathEnabled(realWriteEnabled, realWriteSimulateEnabled)) {
            throw new IllegalStateException(operationalSafetyPolicy.writeDisabledOperatorMessage());
        }
        switch (acao.getTipo()) {
            case ENVIAR_PROPOSTA -> enviarProposta(acao);
            case ENVIAR_PERGUNTA -> enviarPergunta(acao);
            case RESPONDER_CONVERSA -> responderConversa(acao);
            case CANCELAR_PROPOSTA -> cancelarProposta(acao);
        }
    }

    private void enviarProposta(AcaoPendente acao) throws JsonProcessingException {
        PropostaPayload payload = objectMapper.readValue(acao.getPayloadJson(), PropostaPayload.class);
        PlataformaFreela plataforma = plataformaDaAcao(acao, payload.getPlataforma());
        hooks.validarConteudoSeguroParaEnvio(
            plataforma,
            payload.getProjetoId(),
            null,
            TipoAcaoPendente.ENVIAR_PROPOSTA,
            payload.getMensagem(),
            "mensagem da proposta"
        );
        Projeto projeto = Projeto.builder()
            .plataforma(plataforma)
            .id(payload.getProjetoId())
            .idExterno(payload.getProjetoIdExterno())
            .titulo(payload.getTituloProjeto())
            .urlProjeto(payload.getUrlProjeto())
            .build();
        Proposta proposta = Proposta.builder()
            .projetoId(payload.getProjetoId())
            .valorProposto(payload.getValorProposto())
            .valorMinimo(payload.getValorMinimo())
            .mediaMercado(payload.getMediaMercado())
            .mensagem(payload.getMensagem())
            .build();

        if (hooks.deveSimularEnvioReal()) {
            hooks.registrarEnvioSimuladoCliente(
                acao,
                plataforma,
                payload.getProjetoId(),
                null,
                TipoAcaoPendente.ENVIAR_PROPOSTA,
                payload.getMensagem(),
                payload.getTituloProjeto()
            );
            return;
        }

        hooks.confirmarDonoAntesDoEnvio(payload.getProjetoId());
        Proposta enviada = adapter(plataforma).enviarProposta(projeto, proposta);
        if (enviada == null) {
            throw new IllegalStateException("Falha ao enviar proposta");
        }
        hooks.marcarProjetoComoAprovado(plataforma, payload.getProjetoId(), payload.getTituloProjeto(), acao.getModoOrigem());
        hooks.registrarInteracao(projeto, TipoInteracao.PROPOSTA_ENVIADA,
            "Proposta enviada após aprovação humana", acao.getModoOrigem());
        hooks.registrarEnvioCliente(plataforma, payload.getProjetoId(), null, TipoAcaoPendente.ENVIAR_PROPOSTA, payload.getMensagem());
    }

    private void enviarPergunta(AcaoPendente acao) throws JsonProcessingException {
        PerguntaPayload payload = objectMapper.readValue(acao.getPayloadJson(), PerguntaPayload.class);
        PlataformaFreela plataforma = plataformaDaAcao(acao, payload.getPlataforma());
        hooks.validarConteudoSeguroParaEnvio(
            plataforma,
            payload.getProjetoId(),
            null,
            TipoAcaoPendente.ENVIAR_PERGUNTA,
            payload.getMensagem(),
            "mensagem da pergunta"
        );
        if (hooks.deveSimularEnvioReal()) {
            hooks.registrarEnvioSimuladoCliente(
                acao,
                plataforma,
                payload.getProjetoId(),
                null,
                TipoAcaoPendente.ENVIAR_PERGUNTA,
                payload.getMensagem(),
                payload.getTituloProjeto()
            );
            return;
        }

        MarketplaceAdapter adapter = adapter(plataforma);
        Projeto projeto = Projeto.builder()
            .plataforma(plataforma)
            .id(payload.getProjetoId())
            .idExterno(payload.getProjetoIdExterno())
            .titulo(payload.getTituloProjeto())
            .urlProjeto(payload.getUrlProjeto())
            .build();
        // Dona da reivindicacao confirmada e renovada no banco logo antes do envio externo.
        hooks.confirmarDonoAntesDoEnvio(payload.getProjetoId());
        // ACHADO-FA-CONC-01-2: a conexao e reservada de forma atomica ANTES do
        // envio; sem reserva nao ha envio. Se o envio falhar depois da reserva,
        // a conexao e devolvida (decremento atomico que nao fica negativo).
        boolean consomeConexao = adapter.capabilities().conexoes();
        // Reset do dia ANTES de marcar o instante: a reserva cai no periodo iniciado antes de
        // reservadaEm, e a devolucao so vale se nao houver reset depois dela (na virada exata
        // a devolucao e descartada: erra para menos vagas, nunca acima do limite).
        LocalDateTime reservadaEm = null;
        if (consomeConexao) {
            gerenciadorConexoes.verificarResetDiario();
            reservadaEm = LocalDateTime.now();
        }
        if (consomeConexao && !gerenciadorConexoes.reservarConexao()) {
            log.info("Envio de pergunta para projeto {} na plataforma {} recusado: limite diario de conexoes atingido",
                payload.getProjetoId(), plataforma.key());
            throw new LimiteDiarioLocalException("Limite diario de conexoes atingido");
        }
        try {
            boolean enviada = adapter.enviarPergunta(projeto, payload.getMensagem());
            if (!enviada) {
                throw new IllegalStateException("Falha ao enviar pergunta");
            }
        } catch (RuntimeException e) {
            // So devolve a vaga com certeza de que nada saiu. Resposta perdida/ilegivel apos o
            // POST (ex.: 99Freelas devolve false) pode ter enviado e consumido a conexao: a
            // reserva fica, para nao passar do limite diario real.
            if (consomeConexao && nadaFoiEnviado(e)) {
                gerenciadorConexoes.devolverConexao(reservadaEm);
            }
            throw e;
        }
        log.info("Pergunta enviada para projeto {} na plataforma {}", payload.getProjetoId(), plataforma.key());
        hooks.marcarProjetoComoAprovado(plataforma, payload.getProjetoId(), payload.getTituloProjeto(), acao.getModoOrigem());
        hooks.registrarInteracao(plataforma, payload.getProjetoId(), payload.getTituloProjeto(), TipoInteracao.DUVIDA_CLARIFICADA,
            "Pergunta enviada ao cliente após aprovação humana", acao.getModoOrigem());
        hooks.registrarEnvioCliente(plataforma, payload.getProjetoId(), null, TipoAcaoPendente.ENVIAR_PERGUNTA, payload.getMensagem());
    }

    private void responderConversa(AcaoPendente acao) throws JsonProcessingException {
        RespostaPayload payload = objectMapper.readValue(acao.getPayloadJson(), RespostaPayload.class);
        PlataformaFreela plataforma = plataformaDaAcao(acao, payload.getPlataforma());
        payload.setPlataforma(plataforma);
        hooks.validarRespostaConversaAindaAtual(payload);
        hooks.validarConteudoSeguroParaEnvio(
            plataforma,
            payload.getProjetoId(),
            payload.getConversaId(),
            TipoAcaoPendente.RESPONDER_CONVERSA,
            payload.getMensagem(),
            "mensagem da conversa"
        );
        if (hooks.deveSimularEnvioReal()) {
            hooks.registrarEnvioSimuladoCliente(
                acao,
                plataforma,
                payload.getProjetoId(),
                payload.getConversaId(),
                TipoAcaoPendente.RESPONDER_CONVERSA,
                payload.getMensagem(),
                payload.getTituloProjeto()
            );
            return;
        }

        MensagemConversa enviada = adapter(plataforma).enviarMensagemConversa(payload.getConversaId(), payload.getMensagem());
        if (enviada == null) {
            throw new IllegalStateException("Falha ao enviar resposta na conversa");
        }
        hooks.registrarInteracao(plataforma, payload.getProjetoId(), payload.getTituloProjeto(), TipoInteracao.FEEDBACK_RECEBIDO,
            "Mensagem enviada ao cliente após aprovação humana", acao.getModoOrigem());
        hooks.registrarEnvioCliente(
            plataforma,
            payload.getProjetoId(),
            payload.getConversaId(),
            TipoAcaoPendente.RESPONDER_CONVERSA,
            payload.getMensagem()
        );
    }

    private void cancelarProposta(AcaoPendente acao) throws JsonProcessingException {
        CancelamentoPayload payload = objectMapper.readValue(acao.getPayloadJson(), CancelamentoPayload.class);
        PlataformaFreela plataforma = plataformaDaAcao(acao, payload.getPlataforma());
        if (hooks.deveSimularEnvioReal()) {
            hooks.registrarEnvioSimuladoCliente(
                acao,
                plataforma,
                payload.getProjetoId(),
                null,
                TipoAcaoPendente.CANCELAR_PROPOSTA,
                "Cancelamento de proposta simulado: " + AutoAtendimentoTextHelper.safeComentario(payload.getMotivo()),
                payload.getTituloProjeto()
            );
            return;
        }

        boolean cancelou = adapter(plataforma).cancelarProposta(payload.getProjetoId());
        if (!cancelou) {
            throw new IllegalStateException("Falha ao cancelar proposta");
        }
        hooks.registrarInteracao(plataforma, payload.getProjetoId(), payload.getTituloProjeto(), TipoInteracao.CANCELAMENTO,
            "Proposta cancelada após aprovação humana: " + payload.getMotivo(), acao.getModoOrigem());
    }

    private MarketplaceAdapter adapter(PlataformaFreela plataforma) {
        return marketplaceAdapterRegistry.para(plataforma);
    }

    private PlataformaFreela plataformaDaAcao(AcaoPendente acao, PlataformaFreela plataformaPayload) {
        PlataformaFreela plataforma = plataformaPayload != null ? plataformaPayload : acao.getPlataforma();
        if (plataforma == null) {
            throw new IllegalStateException("Acao pendente sem plataforma definida");
        }
        return plataforma;
    }

    /** Recusas que o marketplace/adaptador da antes de processar o envio. */
    private static boolean nadaFoiEnviado(Throwable erro) {
        for (Throwable atual = erro; atual != null; atual = atual.getCause() == atual ? null : atual.getCause()) {
            if (atual instanceof MarketplaceAdapterException marketplace) {
                MarketplaceAdapterErrorType tipo = marketplace.getType();
                return tipo == MarketplaceAdapterErrorType.FALHA_AUTENTICACAO
                    || tipo == MarketplaceAdapterErrorType.PLATAFORMA_NAO_SUPORTADA
                    || tipo == MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL
                    || tipo == MarketplaceAdapterErrorType.LIMITE_CONEXOES;
            }
        }
        return false;
    }
}
