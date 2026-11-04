package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.dto.PoliticaOperacionalResultadoDTO;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.policy.OperationalSafetyPolicy;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.MensagemClienteGuardService;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.freelasatende.service.impl.AutoAtendimentoTextHelper.anexarObservacao;
import static com.freelasatende.service.impl.AutoAtendimentoTextHelper.safeComentario;

@Slf4j
class AutoAtendimentoFeedbackHandler {

    private final AcaoPendenteRepository acaoPendenteRepository;
    private final ObjectMapper objectMapper;
    private final AvaliadorIaService avaliadorIaService;
    private final OperationalSafetyPolicy operationalSafetyPolicy;
    private final MensagemClienteGuardService mensagemClienteGuardService;
    private final AutoAtendimentoAprendizadoHelper aprendizadoHelper;
    private final Hooks hooks;
    private final boolean realWriteEnabled;
    private final boolean realWriteSimulateEnabled;

    AutoAtendimentoFeedbackHandler(
        AcaoPendenteRepository acaoPendenteRepository,
        ObjectMapper objectMapper,
        AvaliadorIaService avaliadorIaService,
        OperationalSafetyPolicy operationalSafetyPolicy,
        MensagemClienteGuardService mensagemClienteGuardService,
        AutoAtendimentoAprendizadoHelper aprendizadoHelper,
        Hooks hooks,
        boolean realWriteEnabled,
        boolean realWriteSimulateEnabled
    ) {
        this.acaoPendenteRepository = acaoPendenteRepository;
        this.objectMapper = objectMapper;
        this.avaliadorIaService = avaliadorIaService;
        this.operationalSafetyPolicy = operationalSafetyPolicy;
        this.mensagemClienteGuardService = mensagemClienteGuardService;
        this.aprendizadoHelper = aprendizadoHelper;
        this.hooks = hooks;
        this.realWriteEnabled = realWriteEnabled;
        this.realWriteSimulateEnabled = realWriteSimulateEnabled;
    }

    String processarFeedback(Long acaoId, boolean aprovado, String comentario) {
        Optional<AcaoPendente> optional = acaoPendenteRepository.findById(acaoId);
        if (optional.isEmpty()) {
            return "❌ Ação pendente não encontrada: " + acaoId;
        }

        AcaoPendente acao = optional.get();
        if (acao.getStatus() != StatusAcaoPendente.PENDENTE) {
            return "⚠️ Ação " + acaoId + " já foi tratada com status " + acao.getStatus();
        }
        if (comentario == null || comentario.isBlank()) {
            return "⚠️ Feedback obrigatório: escolha ou informe um motivo antes de tratar a ação " + acaoId + ".";
        }

        String mensagemPayload = AutoAtendimentoPayloadHelper.extrairMensagemPayloadSeguro(objectMapper, acao.getPayloadJson());
        if (aprovado && mensagemPayload.isBlank()) {
            registrarInteracao(acao, TipoInteracao.MENSAGEM_ERRO,
                "Aprovação bloqueada porque a ação não possui texto ao cliente");
            acao.setObservacaoUsuario(anexarObservacao(
                acao.getObservacaoUsuario(),
                "Aprovação bloqueada: reescreva ou ensine antes de enviar."
            ));
            acao.setDataAtualizacao(LocalDateTime.now());
            acaoPendenteRepository.save(acao);
            return "🛑 Ação " + acaoId + " não possui texto para enviar. Reescreva ou ensine a resposta antes de aprovar.";
        }

        // Reivindica a acao no banco ANTES de alterar a entidade: com Open EntityManager in
        // View ela segue gerenciada, e um flush de uma requisicao concorrente que perdeu a
        // disputa regravaria PENDENTE por cima de APROVADA/EXECUTADA (sem @Version). Quem
        // recebe 0 linhas nao toca na entidade e nao envia nada.
        StatusAcaoPendente destino = aprovado ? StatusAcaoPendente.APROVADA : StatusAcaoPendente.REJEITADA;
        // A data entra no mesmo UPDATE: a regra antispam conta APROVADA recente como envio em curso.
        LocalDateTime reivindicadaEm = LocalDateTime.now();
        if (acaoPendenteRepository.transicaoAtomicaDeStatus(
                acao.getId(), StatusAcaoPendente.PENDENTE, destino, reivindicadaEm) == 0) {
            return recusaAcaoJaProcessada(acaoId);
        }
        acao.setStatus(destino);
        acao.setObservacaoUsuario(comentario);
        acao.setDataAtualizacao(reivindicadaEm);

        if (!aprovado) {
            acaoPendenteRepository.save(acao);
            hooks.descartarRascunhosPendentes(acao);
            aprendizadoHelper.registrarFeedbackAprendizado(
                acao,
                false,
                comentario,
                mensagemPayload
            );
            registrarInteracao(acao, TipoInteracao.REJEICAO_USUARIO,
                "Ação rejeitada pelo usuário: " + safeComentario(comentario));
            return "🛑 Ação " + acaoId + " rejeitada. Nada será enviado.";
        }

        // Revalida o texto REAL que vai sair (o payload pode ter sido alterado depois
        // que o rascunho foi criado). O guardiao reexecuta as regras de tom, risco de
        // plataforma, spam e duplicidade; o resultado real entra na politica — antes,
        // a aprovacao usava status "PENDENTE" cego e conteudo inseguro podia passar.
        PoliticaOperacionalResultadoDTO politica;
        try {
            politica = revalidarParaAprovacao(acao, comentario, mensagemPayload);
        } catch (RuntimeException e) {
            // Falha antes do envio: esta requisicao detem a reivindicacao e devolve a acao
            // para PENDENTE, senao ela ficaria APROVADA sem envio e sem nova tentativa.
            acao.setStatus(StatusAcaoPendente.PENDENTE);
            acaoPendenteRepository.save(acao);
            throw e;
        }
        if (!politica.isPermitido()) {
            registrarInteracao(acao, TipoInteracao.MENSAGEM_ERRO,
                "Aprovação bloqueada pela política operacional: " + politica.getMotivo());
            // Esta requisicao detem a reivindicacao: devolve a acao para a fila.
            acao.setStatus(StatusAcaoPendente.PENDENTE);
            acao.setObservacaoUsuario(comentario);
            acao.setDataAtualizacao(LocalDateTime.now());
            acaoPendenteRepository.save(acao);
            return "🛑 Ação bloqueada pelas 3 regras: " + politica.getMotivo();
        }

        if (!operationalSafetyPolicy.clientWritePathEnabled(realWriteEnabled, realWriteSimulateEnabled)) {
            registrarInteracao(acao, TipoInteracao.MENSAGEM_ERRO,
                operationalSafetyPolicy.writeDisabledOperatorMessage());
            acao.setStatus(StatusAcaoPendente.PENDENTE);
            acaoPendenteRepository.save(acao);
            return operationalSafetyPolicy.writeDisabledUserMessage();
        }

        try {
            hooks.executarAcao(acao);
            acao.setStatus(StatusAcaoPendente.EXECUTADA);
            acao.setDataAtualizacao(LocalDateTime.now());
            acaoPendenteRepository.save(acao);
            aprendizadoHelper.registrarFeedbackAprendizado(
                acao,
                true,
                comentario,
                AutoAtendimentoPayloadHelper.extrairMensagemPayloadSeguro(objectMapper, acao.getPayloadJson())
            );
            registrarInteracao(acao, TipoInteracao.APROVACAO_USUARIO,
                realWriteEnabled ? "Ação aprovada pelo usuário e executada" : "Ação aprovada pelo usuário e simulada sem envio real");
            if (!realWriteEnabled && realWriteSimulateEnabled) {
                return "✅ Ação " + acaoId + " simulada com sucesso. Nenhum envio real foi feito.";
            }
            return "✅ Ação " + acaoId + " executada com sucesso.";
        } catch (Exception e) {
            boolean falhaPreEnvio = falhaPreEnvioRecuperavel(e);
            boolean abordagemInicialAutomatica = abordagemInicialAutomatica(acao);
            boolean manterPendente = falhaPreEnvio && !abordagemInicialAutomatica;
            acao.setStatus(manterPendente ? StatusAcaoPendente.PENDENTE : StatusAcaoPendente.ERRO);
            acao.setObservacaoUsuario(anexarObservacao(acao.getObservacaoUsuario(), "Falha ao executar: " + safeComentario(e.getMessage())));
            acao.setDataAtualizacao(LocalDateTime.now());
            acaoPendenteRepository.save(acao);
            registrarInteracao(acao, TipoInteracao.ERRO_SISTEMA,
                "Erro ao executar ação pendente: " + e.getMessage());
            if (manterPendente) {
                log.warn("Ação pendente {} falhou antes de confirmar envio e continua pendente: {}", acaoId, e.getMessage());
                return "❌ Ação " + acaoId + " falhou antes de enviar e continua pendente: " + e.getMessage();
            }
            if (falhaPreEnvio && abordagemInicialAutomatica) {
                log.warn("Ação pendente {} de abordagem inicial automatica falhou antes do envio e foi encerrada sem voltar para pendencias: {}",
                    acaoId, e.getMessage());
                return "❌ Ação " + acaoId + " falhou antes de enviar e foi encerrada sem voltar para pendências: " + e.getMessage();
            }
            log.error("Erro ao executar ação pendente {}: {}", acaoId, e.getMessage(), e);
            return "❌ Ação " + acaoId + " aprovada, mas falhou ao executar: " + e.getMessage();
        }
    }

    private PoliticaOperacionalResultadoDTO revalidarParaAprovacao(
            AcaoPendente acao, String comentario, String mensagemPayload) {
        MensagemClienteGuardService.ContextoMensagemCliente contextoRevalidacao =
            new MensagemClienteGuardService.ContextoMensagemCliente(
                acao.getPlataforma(),
                acao.getProjetoId(),
                acao.getConversaId(),
                acao.getTipo(),
                true
            );
        MensagemClienteGuardService.ValidacaoRascunho revalidacao =
            mensagemClienteGuardService.validarRascunho(contextoRevalidacao, mensagemPayload);
        return aprendizadoHelper.avaliarPoliticaParaAprovacao(
            acao,
            comentario,
            mensagemPayload,
            revalidacao.resultado().name(),
            revalidacao.motivo()
        );
    }

    private String recusaAcaoJaProcessada(Long acaoId) {
        String statusAtual = acaoPendenteRepository.findById(acaoId)
            .map(acao -> acao.getStatus().name())
            .orElse("REMOVIDA");
        return "⚠️ Ação " + acaoId + " já foi tratada com status " + statusAtual;
    }

    private boolean abordagemInicialAutomatica(AcaoPendente acao) {
        return acao != null
            && acao.getTipo() == TipoAcaoPendente.ENVIAR_PERGUNTA
            && acao.getResumo() != null
            && acao.getResumo().startsWith("Abordagem inicial automatica | ");
    }

    private boolean falhaPreEnvioRecuperavel(Exception e) {
        Throwable atual = e;
        while (atual != null) {
            if (atual instanceof MarketplaceAdapterException marketplaceError && falhaMarketplaceAntesDoEnvio(marketplaceError)) {
                return true;
            }
            if (atual instanceof LimiteDiarioLocalException) {
                // Nada foi enviado e o limite renova amanha: a acao continua pendente.
                return true;
            }
            atual = atual.getCause();
        }
        String mensagem = e.getMessage();
        if (mensagem == null) {
            return false;
        }
        String normalizada = mensagem.toLowerCase();
        return normalizada.contains("confirmar a ultima mensagem")
            || normalizada.contains("confirmar a última mensagem")
            || normalizada.contains("resposta desatualizada")
            || normalizada.contains("rascunho desatualizado");
    }

    private boolean falhaMarketplaceAntesDoEnvio(MarketplaceAdapterException e) {
        if (e.getType() == MarketplaceAdapterErrorType.FALHA_AUTENTICACAO
            || e.getType() == MarketplaceAdapterErrorType.FALHA_REDE) {
            return true;
        }
        String mensagem = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return e.getType() == MarketplaceAdapterErrorType.FALHA_ENVIO
            && (mensagem.contains("http 401")
                || mensagem.contains("http 403")
                || mensagem.contains("acesso negado")
                || mensagem.contains("não possui autorização")
                || mensagem.contains("nao possui autorizacao"));
    }

    String reescreverAcao(Long acaoId, String novoTexto, String comentario) {
        Optional<AcaoPendente> optional = acaoPendenteRepository.findById(acaoId);
        if (optional.isEmpty()) {
            return "Ação pendente não encontrada: " + acaoId;
        }

        AcaoPendente acao = optional.get();
        if (acao.getStatus() != StatusAcaoPendente.PENDENTE) {
            return "Ação " + acaoId + " não pode ser reescrita porque está com status " + acao.getStatus();
        }
        if (acao.getTipo() == TipoAcaoPendente.CANCELAR_PROPOSTA) {
            return "Cancelamento não possui mensagem ao cliente para reescrever.";
        }
        if (novoTexto == null || novoTexto.isBlank()) {
            return "Novo texto vazio. Nada foi alterado.";
        }
        if (comentario == null || comentario.isBlank()) {
            return "Feedback obrigatório: informe o motivo da reescrita para ensinar o sistema.";
        }

        String textoNormalizado = novoTexto.trim();
        String textoOriginal = AutoAtendimentoPayloadHelper.extrairMensagemPayloadSeguro(objectMapper, acao.getPayloadJson());
        MensagemClienteGuardService.ContextoMensagemCliente contexto =
            new MensagemClienteGuardService.ContextoMensagemCliente(
                acao.getPlataforma(),
                acao.getProjetoId(),
                acao.getConversaId(),
                acao.getTipo(),
                true
            );
        MensagemClienteGuardService.ValidacaoRascunho validacao =
            mensagemClienteGuardService.validarRascunho(contexto, textoNormalizado);
        mensagemClienteGuardService.registrarRascunho(contexto, textoNormalizado, validacao);

        if (!validacao.permitido()) {
            String motivo = validacao.motivo() != null ? validacao.motivo() : validacao.resultado().name();
            registrarInteracao(acao, TipoInteracao.MENSAGEM_ERRO,
                "Reescrita bloqueada pelo guardião: " + motivo);
            return "Rascunho bloqueado pelo guardião: " + motivo;
        }

        try {
            acao.setPayloadJson(AutoAtendimentoPayloadHelper.atualizarMensagemPayload(objectMapper, acao.getPayloadJson(), textoNormalizado));
            acao.setObservacaoUsuario(anexarObservacao(acao.getObservacaoUsuario(), safeComentario(comentario)));
            acao.setDataAtualizacao(LocalDateTime.now());
            acaoPendenteRepository.save(acao);
            aprendizadoHelper.registrarAprendizadoDetalhado(
                acao,
                true,
                comentario,
                textoOriginal,
                textoNormalizado,
                "REESCRITA"
            );
            registrarInteracao(acao, TipoInteracao.REAVALIACAO_SOLICITADA,
                "Ação pendente reescrita manualmente e mantida para aprovação");
            return "Mensagem reescrita com segurança. A ação continua pendente para aprovação.";
        } catch (Exception e) {
            log.error("Erro ao reescrever ação {}: {}", acaoId, e.getMessage(), e);
            registrarInteracao(acao, TipoInteracao.ERRO_SISTEMA,
                "Erro ao reescrever ação pendente: " + e.getMessage());
            return "Erro ao reescrever ação: " + e.getMessage();
        }
    }

    String registrarContextoEnsino(Long acaoId, String orientacaoUsuario) {
        Optional<AcaoPendente> optional = acaoPendenteRepository.findById(acaoId);
        if (optional.isEmpty()) {
            return "Ação pendente não encontrada: " + acaoId;
        }

        AcaoPendente acao = optional.get();
        if (acao.getStatus() != StatusAcaoPendente.PENDENTE) {
            return "Ação " + acaoId + " não está pendente. Status atual: " + acao.getStatus();
        }
        if (orientacaoUsuario == null || orientacaoUsuario.isBlank()) {
            return "Me mande uma orientação em texto para eu aprender o contexto dessa ação.";
        }
        if (acao.getTipo() == TipoAcaoPendente.CANCELAR_PROPOSTA) {
            return "Cancelamento não usa conversa de ensino. Recuse ou aprove manualmente com motivo.";
        }

        String orientacao = orientacaoUsuario.trim();
        String textoAtual = AutoAtendimentoPayloadHelper.extrairMensagemPayloadSeguro(objectMapper, acao.getPayloadJson());
        salvarNotaEnsino(acao, orientacao);
        String contexto = montarContextoAcaoParaIa(acao, orientacao);
        String sugestao;
        try {
            sugestao = avaliadorIaService.reescreverRascunhoComOrientacao(contexto, textoAtual, orientacao);
        } catch (RuntimeException e) {
            registrarInteracao(acao, TipoInteracao.FEEDBACK_USUARIO,
                "Contexto de ensino salvo, mas IA nao reescreveu a mensagem: " + safeComentario(e.getMessage()));
            if (textoAtual.isBlank()) {
                return "Contexto salvo na ação " + acaoId
                    + ". A IA não gerou um novo rascunho válido agora e nada foi enviado ao cliente."
                    + "\nMotivo: " + safeComentario(e.getMessage());
            }
            return "Contexto salvo na ação " + acaoId
                + ". A IA não gerou uma reescrita válida agora. A mensagem atual foi mantida e nada foi enviado ao cliente."
                + "\nMotivo: " + safeComentario(e.getMessage());
        }

        if (sugestao == null || sugestao.isBlank() || sugestao.trim().equals(textoAtual.trim())) {
            registrarInteracao(acao, TipoInteracao.FEEDBACK_USUARIO,
                "Contexto de ensino salvo sem alterar mensagem: " + safeComentario(orientacao));
            return "Contexto salvo na ação " + acaoId + ". Mantive a mensagem atual porque a IA não encontrou uma reescrita melhor com segurança.";
        }

        String resultado = reescreverAcao(acaoId, sugestao.trim(), "Orientação de ensino: " + orientacao);
        registrarInteracao(acao, TipoInteracao.FEEDBACK_USUARIO,
            "Contexto de ensino aplicado a mensagem: " + safeComentario(orientacao));
        String acaoRealizada = textoAtual.isBlank() ? "novo rascunho criado" : "mensagem atualizada";
        return "Contexto salvo e " + acaoRealizada + " na ação " + acaoId + ". Nada foi enviado ao cliente.\n\n"
            + resultado + "\n\nNova mensagem:\n" + sugestao.trim();
    }

    String responderDuvidaIa(Long acaoId, String respostaUsuario, String regraConfirmada) {
        Optional<AcaoPendente> optional = acaoPendenteRepository.findById(acaoId);
        if (optional.isEmpty()) {
            return "Ação pendente não encontrada: " + acaoId;
        }

        AcaoPendente acao = optional.get();
        if (acao.getStatus() != StatusAcaoPendente.PENDENTE) {
            return "Ação " + acaoId + " não está pendente. Status atual: " + acao.getStatus();
        }
        if (respostaUsuario == null || respostaUsuario.isBlank()) {
            return "Responda a dúvida da IA antes de reprocessar a ação.";
        }
        if (acao.getTipo() == TipoAcaoPendente.CANCELAR_PROPOSTA) {
            return "Cancelamento não usa resposta de dúvida da IA. Aprove ou recuse manualmente com motivo.";
        }

        JsonNode payload = AutoAtendimentoPayloadHelper.lerPayloadSeguro(objectMapper, acao.getPayloadJson());
        String pergunta = payload.path("perguntaParaUsuario").asText("Pergunta da IA");
        String regra = regraConfirmada != null && !regraConfirmada.isBlank()
            ? regraConfirmada.trim()
            : payload.path("regraAprendizadoSugerida").asText("");
        String resposta = respostaUsuario.trim();
        String textoAtual = AutoAtendimentoPayloadHelper.extrairMensagemPayloadSeguro(objectMapper, acao.getPayloadJson());
        String orientacao = "Resposta a duvida da IA: " + pergunta
            + "\nResposta do usuario: " + resposta
            + (regra.isBlank() ? "" : "\nRegra confirmada: " + regra);

        salvarRespostaDuvidaIa(acao, pergunta, resposta, regra);
        aprendizadoHelper.registrarAprendizadoDetalhado(
            acao,
            true,
            regra.isBlank() ? resposta : regra,
            textoAtual,
            null,
            "RESPOSTA_DUVIDA_IA"
        );

        if (textoAtual.isBlank()) {
            registrarInteracao(acao, TipoInteracao.FEEDBACK_USUARIO,
                "Resposta de duvida da IA salva sem mensagem para reescrever: " + safeComentario(resposta));
            return "Resposta da IA salva na ação " + acaoId + ", mas não há mensagem ao cliente para reprocessar.";
        }

        String contexto = montarContextoAcaoParaIa(acao, orientacao);
        String sugestao;
        try {
            sugestao = avaliadorIaService.reescreverRascunhoComOrientacao(contexto, textoAtual, orientacao);
        } catch (RuntimeException e) {
            registrarInteracao(acao, TipoInteracao.FEEDBACK_USUARIO,
                "Resposta de duvida da IA salva, mas IA nao reescreveu a mensagem: " + safeComentario(e.getMessage()));
            return "Resposta salva e aprendizado registrado. A IA não gerou uma reescrita válida agora. A mensagem atual foi mantida e nada foi enviado ao cliente."
                + "\nMotivo: " + safeComentario(e.getMessage());
        }
        if (sugestao == null || sugestao.isBlank() || sugestao.trim().equals(textoAtual.trim())) {
            registrarInteracao(acao, TipoInteracao.FEEDBACK_USUARIO,
                "Resposta de duvida da IA salva sem alterar mensagem: " + safeComentario(resposta));
            return "Resposta salva e aprendizado registrado. Mantive a mensagem atual porque a IA não encontrou melhoria segura.";
        }

        String resultado = reescreverAcao(acaoId, sugestao.trim(), "Resposta duvida IA: " + resposta);
        registrarInteracao(acao, TipoInteracao.FEEDBACK_USUARIO,
            "Resposta de duvida da IA aplicada a mensagem: " + safeComentario(resposta));
        return "Resposta salva, aprendizado registrado e mensagem reprocessada. Nada foi enviado ao cliente.\n\n"
            + resultado + "\n\nNova mensagem:\n" + sugestao.trim();
    }

    private void registrarInteracao(AcaoPendente acao, TipoInteracao tipo, String conteudo) {
        hooks.registrarInteracao(acao, tipo, conteudo);
    }

    private void salvarNotaEnsino(AcaoPendente acao, String orientacao) {
        try {
            acao.setPayloadJson(AutoAtendimentoPayloadHelper.anexarNotaEnsinoPayload(
                objectMapper,
                acao.getPayloadJson(),
                orientacao
            ));
        } catch (Exception e) {
            log.warn("Nao foi possivel anexar nota de ensino ao payload da acao {}: {}", acao.getId(), e.getMessage());
        }
        acao.setObservacaoUsuario(anexarObservacao(acao.getObservacaoUsuario(), "Ensino: " + safeComentario(orientacao)));
        acao.setDataAtualizacao(LocalDateTime.now());
        acaoPendenteRepository.save(acao);
    }

    private void salvarRespostaDuvidaIa(AcaoPendente acao, String pergunta, String resposta, String regra) {
        try {
            acao.setPayloadJson(AutoAtendimentoPayloadHelper.anexarRespostaDuvidaIaPayload(
                objectMapper,
                acao.getPayloadJson(),
                pergunta,
                resposta,
                regra
            ));
        } catch (Exception e) {
            log.warn("Nao foi possivel anexar resposta de duvida da IA ao payload da acao {}: {}", acao.getId(), e.getMessage());
        }
        String resumo = "Duvida IA: " + safeComentario(pergunta) + " | Resposta: " + safeComentario(resposta);
        acao.setObservacaoUsuario(anexarObservacao(acao.getObservacaoUsuario(), resumo));
        acao.setDataAtualizacao(LocalDateTime.now());
        acaoPendenteRepository.save(acao);
    }

    private String montarContextoAcaoParaIa(AcaoPendente acao, String orientacao) {
        JsonNode payload = AutoAtendimentoPayloadHelper.lerPayloadSeguro(objectMapper, acao.getPayloadJson());
        return ContextoAcaoIaFormatter.montar(acao, payload, orientacao);
    }

    interface Hooks {
        void executarAcao(AcaoPendente acao) throws Exception;

        void registrarInteracao(AcaoPendente acao, TipoInteracao tipo, String conteudo);

        void descartarRascunhosPendentes(AcaoPendente acao);
    }
}
