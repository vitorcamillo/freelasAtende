package com.freelasatende.service.impl;

import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.repository.InteracaoRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Transforma o aviso de liberacao de contato do marketplace em alerta no Telegram.
 *
 * <p>Quando o empregador nao responde ao interesse, o Freelancer divulga os dados
 * de contato dele numa mensagem de sistema. O fluxo de conversa descarta mensagem
 * de sistema, entao esse dado -- que permite procurar o cliente por fora da
 * plataforma -- sumia sem ninguem ver. Aqui ele e detectado, registrado uma unica
 * vez por mensagem e avisado ao operador.</p>
 */
@Slf4j
final class ContatoLiberadoNotificador {

    private ContatoLiberadoNotificador() {
    }

    static int notificar(
        ConversaResumo conversa,
        Projeto projeto,
        List<MensagemConversa> mensagens,
        ModoSistema modo,
        InteracaoRepository interacaoRepository,
        AutoAtendimentoConversaHandler.Hooks hooks
    ) {
        if (mensagens == null || mensagens.isEmpty() || conversa == null || projeto == null) {
            return 0;
        }
        PlataformaFreela plataforma = projeto.getPlataforma();
        int alertados = 0;
        for (MensagemConversa mensagem : mensagens) {
            if (!candidata(mensagem)) {
                continue;
            }
            var contato = ContatoLiberadoDetector.detectar(mensagem.getTexto());
            if (contato.isEmpty()) {
                continue;
            }
            String chave = chave(plataforma, conversa.getIdConversa(), mensagem.getId());
            if (interacaoRepository.existsByTipoAndConteudoContaining(TipoInteracao.MENSAGEM_RECEBIDA, chave)) {
                continue;
            }
            hooks.registrarInteracao(
                plataforma,
                projeto.getId(),
                projeto.getTitulo(),
                TipoInteracao.MENSAGEM_RECEBIDA,
                "Contato do cliente liberado pela plataforma. " + chave,
                modo
            );
            hooks.alertarContatoLiberado(AutoAtendimentoTextHelper.formatarContatoLiberado(
                plataforma,
                conversa.getNomeProjeto(),
                conversa.getNomeCliente(),
                contato.get()
            ));
            alertados++;
            log.info("[{}][CONTATO] dados de contato liberados na conversa {} enviados ao Telegram",
                plataforma == null ? "-" : plataforma.key(), conversa.getIdConversa());
        }
        return alertados;
    }

    static String chave(PlataformaFreela plataforma, Long conversaId, Long mensagemId) {
        return "contato-liberado|plataforma:" + (plataforma == null ? "-" : plataforma.key())
            + "|conversa:" + conversaId
            + "|mensagem:" + mensagemId;
    }

    /** Mensagem minha, apagada ou sem id nao vale: o id e a chave da idempotencia. */
    private static boolean candidata(MensagemConversa mensagem) {
        return mensagem != null
            && mensagem.getId() != null
            && !Boolean.TRUE.equals(mensagem.getEnviadaPorMim())
            && !Boolean.TRUE.equals(mensagem.getExcluida());
    }
}

