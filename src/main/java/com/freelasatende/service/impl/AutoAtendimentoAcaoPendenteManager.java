package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.QualidadeRascunhoDTO;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.service.interfaceServico.MensagemClienteGuardService;
import com.freelasatende.service.interfaceServico.QualidadeRascunhoService;
import com.freelasatende.service.interfaceServico.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
class AutoAtendimentoAcaoPendenteManager {

    interface Hooks {
        void registrarInteracao(
            PlataformaFreela plataforma,
            Long projetoId,
            String tituloProjeto,
            TipoInteracao tipo,
            String conteudo,
            ModoSistema modo
        );
    }

    private final AcaoPendenteRepository acaoPendenteRepository;
    private final ObjectMapper objectMapper;
    private final MensagemClienteGuardService mensagemClienteGuardService;
    private final QualidadeRascunhoService qualidadeRascunhoService;
    private final TelegramService telegramService;
    private final TelegramMessageFormatter telegramMessageFormatter;
    private final Hooks hooks;

    synchronized AcaoPendente criarOuRecuperarAcaoProjeto(
        Projeto projeto,
        TipoAcaoPendente tipo,
        String resumo,
        String payloadJson,
        String textoCliente,
        ModoSistema modo
    ) {
        PlataformaFreela plataforma = PlataformaProjetoHelper.plataformaOuPadrao(projeto);
        Optional<AcaoPendente> existente = acaoPendenteRepository
            .findFirstByPlataformaAndProjetoIdAndStatusOrderByDataCriacaoDesc(
                plataforma,
                projeto.getId(),
                StatusAcaoPendente.PENDENTE
            )
            .map(Optional::of)
            .orElse(Optional.empty());
        if (existente.isPresent()) {
            return existente.get();
        }

        validarERegistrarMensagem(plataforma, projeto.getId(), null, tipo, textoCliente, modo, projeto.getTitulo());

        return acaoPendenteRepository.save(AcaoPendente.builder()
            .plataforma(plataforma)
            .projetoId(projeto.getId())
            .tipo(tipo)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(modo)
            .tituloProjeto(projeto.getTitulo())
            .resumo(resumo)
            .payloadJson(payloadJson)
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build());
    }

    synchronized AcaoPendente criarOuRecuperarAcaoConversa(
        ConversaResumo conversa,
        String resumo,
        String payloadJson,
        String textoCliente,
        ModoSistema modo
    ) {
        PlataformaFreela plataforma = PlataformaProjetoHelper.plataformaOuPadrao(conversa.getPlataforma());
        Optional<AcaoPendente> existente = acaoPendenteRepository
            .findFirstByPlataformaAndConversaIdAndTipoAndStatusOrderByDataCriacaoDesc(
                plataforma,
                conversa.getIdConversa(),
                TipoAcaoPendente.RESPONDER_CONVERSA,
                StatusAcaoPendente.PENDENTE
            )
            .map(Optional::of)
            .orElse(Optional.empty());
        if (existente.isPresent()) {
            return existente.get();
        }

        boolean permitido = validarERegistrarMensagem(
            plataforma,
            conversa.getIdProjeto(),
            conversa.getIdConversa(),
            TipoAcaoPendente.RESPONDER_CONVERSA,
            textoCliente,
            modo,
            conversa.getNomeProjeto()
        );
        if (!permitido) {
            return null;
        }

        return acaoPendenteRepository.save(AcaoPendente.builder()
            .plataforma(plataforma)
            .projetoId(conversa.getIdProjeto())
            .conversaId(conversa.getIdConversa())
            .tipo(TipoAcaoPendente.RESPONDER_CONVERSA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(modo)
            .tituloProjeto(conversa.getNomeProjeto())
            .resumo(resumo)
            .payloadJson(payloadJson)
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build());
    }

    synchronized AcaoPendente criarOuRecuperarAcaoConversaRevisaoHumana(
        ConversaResumo conversa,
        String resumo,
        String payloadJson,
        ModoSistema modo
    ) {
        PlataformaFreela plataforma = PlataformaProjetoHelper.plataformaOuPadrao(conversa.getPlataforma());
        Optional<AcaoPendente> existente = acaoPendenteRepository
            .findFirstByPlataformaAndConversaIdAndTipoAndStatusOrderByDataCriacaoDesc(
                plataforma,
                conversa.getIdConversa(),
                TipoAcaoPendente.RESPONDER_CONVERSA,
                StatusAcaoPendente.PENDENTE
            )
            .map(Optional::of)
            .orElse(Optional.empty());
        if (existente.isPresent()) {
            return existente.get();
        }

        hooks.registrarInteracao(
            plataforma,
            conversa.getIdProjeto(),
            conversa.getNomeProjeto(),
            TipoInteracao.REAVALIACAO_SOLICITADA,
            "Rascunho de conversa preservado para revisao humana mesmo apos reprovação de qualidade.",
            modo
        );

        return acaoPendenteRepository.save(AcaoPendente.builder()
            .plataforma(plataforma)
            .projetoId(conversa.getIdProjeto())
            .conversaId(conversa.getIdConversa())
            .tipo(TipoAcaoPendente.RESPONDER_CONVERSA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(modo)
            .tituloProjeto(conversa.getNomeProjeto())
            .resumo(resumo)
            .payloadJson(payloadJson)
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build());
    }

    void notificarAcaoPendente(AcaoPendente acao) {
        String textoCliente = AutoAtendimentoPayloadHelper.extrairMensagemPayloadSeguro(objectMapper, acao.getPayloadJson());
        boolean conversa = acao != null && acao.getTipo() == TipoAcaoPendente.RESPONDER_CONVERSA;
        if (textoCliente.isBlank() && !conversa) {
            log.warn(
                "Acao pendente {} nao sera notificada no Telegram porque nao possui texto ao cliente.",
                acao != null ? acao.getId() : null
            );
            return;
        }
        if (textoCliente.isBlank()) {
            // Conversa sem rascunho automatico ainda deve chegar ao operador para decisao
            // manual (Refazer/Robotico); o formatter exibe a mensagem do cliente (gatilho).
            log.info(
                "Acao pendente {} de conversa sem rascunho da IA; notificando operador para decisao manual.",
                acao.getId()
            );
        }
        String nomeCliente = AutoAtendimentoPayloadHelper.extrairNomeClientePayloadSeguro(objectMapper, acao.getPayloadJson());
        String mensagem = AcaoPendenteNotificationFormatter.montarMensagem(
            acao,
            textoCliente,
            nomeCliente,
            AutoAtendimentoPayloadHelper.lerPayloadSeguro(objectMapper, acao.getPayloadJson()),
            telegramMessageFormatter
        );

        Long telegramMessageId = telegramService.enviarAcaoPendenteComBotoes(
            telegramMessageFormatter.limitNotification(mensagem),
            acao.getId()
        );
        registrarMensagemTelegramDaAcao(acao, telegramMessageId);
    }

    void apagarMensagemTelegramDaAcao(AcaoPendente acao) {
        Long telegramMessageId = AutoAtendimentoPayloadHelper.extrairTelegramMessageIdPayloadSeguro(
            objectMapper,
            acao != null ? acao.getPayloadJson() : null
        );
        if (telegramMessageId == null || telegramMessageId <= 0) {
            return;
        }
        telegramService.apagarMensagemDaConta(telegramMessageId);
    }

    boolean validarERegistrarMensagem(
        PlataformaFreela plataforma,
        Long projetoId,
        Long conversaId,
        TipoAcaoPendente tipo,
        String textoCliente,
        ModoSistema modo,
        String tituloProjeto
    ) {
        MensagemClienteGuardService.ContextoMensagemCliente contexto =
            new MensagemClienteGuardService.ContextoMensagemCliente(
                plataforma,
                projetoId,
                conversaId,
                tipo,
                false
            );
        MensagemClienteGuardService.ValidacaoRascunho validacao =
            mensagemClienteGuardService.validarRascunho(contexto, textoCliente);

        QualidadeRascunhoDTO qualidade = qualidadeRascunhoService.avaliar(
            textoCliente,
            validacao.resultado().name(),
            validacao.motivo()
        );
        if (validacao.permitido() && qualidade.bloqueante()) {
            validacao = new MensagemClienteGuardService.ValidacaoRascunho(
                com.freelasatende.model.enums.ResultadoValidacaoMensagem.BLOQUEADO_TOM_SISTEMA,
                "Qualidade operacional exige revisao: " + qualidade.getComentario(),
                validacao.fingerprint(),
                validacao.textoPreview()
            );
        }

        mensagemClienteGuardService.registrarRascunho(contexto, textoCliente, validacao);
        if (validacao.permitido()) {
            return true;
        }

        String motivo = validacao.motivo() != null ? validacao.motivo() : validacao.resultado().name();
        hooks.registrarInteracao(contexto.plataforma(), projetoId, tituloProjeto, TipoInteracao.MENSAGEM_ERRO,
            "Mensagem ao cliente exige revisao antes de envio: " + motivo, modo);
        return false;
    }

    void descartarRascunhosPendentes(AcaoPendente acao) {
        if (acao == null) {
            return;
        }
        MensagemClienteGuardService.ContextoMensagemCliente contexto =
            new MensagemClienteGuardService.ContextoMensagemCliente(
                acao.getPlataforma(),
                acao.getProjetoId(),
                acao.getConversaId(),
                acao.getTipo(),
                true
            );
        mensagemClienteGuardService.descartarRascunhosPendentes(contexto);
    }

    private void registrarMensagemTelegramDaAcao(AcaoPendente acao, Long telegramMessageId) {
        if (acao == null || telegramMessageId == null || telegramMessageId <= 0) {
            return;
        }
        try {
            acao.setPayloadJson(AutoAtendimentoPayloadHelper.registrarTelegramMessageIdPayload(
                objectMapper,
                acao.getPayloadJson(),
                telegramMessageId
            ));
            acao.setDataAtualizacao(LocalDateTime.now());
            acaoPendenteRepository.save(acao);
        } catch (Exception e) {
            log.debug("Nao foi possivel registrar message_id Telegram da acao {}: {}", acao.getId(), e.getMessage());
        }
    }
}
