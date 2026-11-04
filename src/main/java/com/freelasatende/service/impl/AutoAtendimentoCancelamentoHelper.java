package com.freelasatende.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.NivelAutonomia;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.service.interfaceServico.HistoricoService;
import com.freelasatende.service.interfaceServico.TelegramService;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
final class AutoAtendimentoCancelamentoHelper {

    interface Hooks {
        void registrarInteracao(
            PlataformaFreela plataforma,
            Long projetoId,
            String tituloProjeto,
            TipoInteracao tipo,
            String conteudo,
            ModoSistema modo
        );

        void notificarAcaoPendente(AcaoPendente acao);
    }

    private final HistoricoService historicoService;
    private final AcaoPendenteRepository acaoPendenteRepository;
    private final TelegramService telegramService;
    private final ObjectMapper objectMapper;
    private final Hooks hooks;

    AutoAtendimentoCancelamentoHelper(
        HistoricoService historicoService,
        AcaoPendenteRepository acaoPendenteRepository,
        TelegramService telegramService,
        ObjectMapper objectMapper,
        Hooks hooks
    ) {
        this.historicoService = historicoService;
        this.acaoPendenteRepository = acaoPendenteRepository;
        this.telegramService = telegramService;
        this.objectMapper = objectMapper;
        this.hooks = hooks;
    }

    void cancelarPropostasAntigas(int dias) {
        try {
            log.info("Iniciando rotina de cashback de conexões...");
            LocalDateTime limite = LocalDateTime.now().minusDays(dias);

            List<Interacao> recentes = historicoService.obterInteracoesRecentes();
            List<Interacao> enviadas = recentes.stream()
                .filter(i -> i.getTipo() == TipoInteracao.PROPOSTA_ENVIADA && i.getData().isBefore(limite))
                .toList();

            int pendentes = 0;
            for (Interacao enviada : enviadas) {
                Long projetoId = enviada.getProjetoId();
                if (projetoId == null) {
                    continue;
                }

                boolean teveResposta = recentes.stream()
                    .anyMatch(i -> i.getProjetoId() != null && i.getProjetoId().equals(projetoId)
                        && i.getTipo() == TipoInteracao.FEEDBACK_RECEBIDO);

                if (!teveResposta) {
                    log.info(
                        "Criando pendencia para cancelar proposta do projeto {} (sem resposta ha mais de {} dias)",
                        projetoId,
                        dias
                    );
                    AcaoPendente acao = criarAcaoPendenteCancelamento(enviada, dias);
                    if (acao != null) {
                        pendentes++;
                        hooks.registrarInteracao(enviada.getPlataforma(), projetoId, enviada.getProjetoTitulo(), TipoInteracao.CONVERSA_ARQUIVADA,
                            "Cancelamento sugerido por inatividade; aguardando aprovacao humana", ModoSistema.REGULAR);
                        hooks.notificarAcaoPendente(acao);
                    }
                }
            }

            if (pendentes > 0) {
                telegramService.enviarNotificacao("Cashback: " + pendentes + " propostas antigas viraram pendencia de cancelamento.");
            } else {
                log.info("Nenhuma proposta antiga pendente de cancelamento.");
            }

        } catch (Exception e) {
            log.error("Erro no cashback de conexões: {}", e.getMessage());
        }
    }

    private AcaoPendente criarAcaoPendenteCancelamento(Interacao enviada, int dias) throws JsonProcessingException {
        Optional<AcaoPendente> existente = acaoPendenteRepository
            .findFirstByPlataformaAndProjetoIdAndStatusOrderByDataCriacaoDesc(
                enviada.getPlataforma(),
                enviada.getProjetoId(),
                StatusAcaoPendente.PENDENTE
            );
        if (existente.isPresent()) {
            return null;
        }

        String motivo = "Sem resposta do cliente ha mais de " + dias + " dias";
        return acaoPendenteRepository.save(AcaoPendente.builder()
            .plataforma(enviada.getPlataforma())
            .projetoId(enviada.getProjetoId())
            .tipo(TipoAcaoPendente.CANCELAR_PROPOSTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto(enviada.getProjetoTitulo())
            .resumo("Revisar cancelamento de proposta antiga | " + motivo)
            .payloadJson(objectMapper.writeValueAsString(CancelamentoPayload.builder()
                .plataforma(enviada.getPlataforma())
                .projetoId(enviada.getProjetoId())
                .projetoIdExterno(PlataformaProjetoHelper.plataformaOuPadrao(enviada.getPlataforma()).idExterno(enviada.getProjetoId()))
                .tituloProjeto(enviada.getProjetoTitulo())
                .motivo(motivo)
                .nivelAutonomia(NivelAutonomia.PERGUNTA_DONO)
                .entendimentoIa("Proposta antiga sem resposta detectada; IA sugere revisar cancelamento para recuperar conexao.")
                .informacoesConfirmadas(List.of(
                    "Projeto: " + AutoAtendimentoTextHelper.valorTexto(enviada.getProjetoTitulo()),
                    motivo
                ))
                .informacoesPendentes(List.of("Confirmar se ainda vale aguardar resposta do cliente."))
                .sabeFazer(List.of("identificar proposta antiga", "sugerir cancelamento seguro", "manter decisao pendente"))
                .duvidasInternas(List.of("confirmar se ainda vale aguardar resposta do cliente"))
                .perguntasParaCliente(List.of())
                .riscosDecisao(List.of("cancelar cedo demais pode perder oportunidade em andamento"))
                .proximaAcaoSugerida("Aprovar cancelamento ou manter proposta ativa.")
                .perguntaParaUsuario("Quer cancelar esta proposta antiga ou ainda vale esperar?")
                .regraAprendizadoSugerida("Proposta antiga sem resposta deve virar revisao humana antes de cancelar.")
                .build()))
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build());
    }
}
