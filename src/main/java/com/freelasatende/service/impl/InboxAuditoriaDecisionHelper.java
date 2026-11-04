package com.freelasatende.service.impl;

import com.freelasatende.model.dto.AcaoControleDTO;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.InboxConversaAuditoriaDTO;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.AcaoPendente;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class InboxAuditoriaDecisionHelper {

    private InboxAuditoriaDecisionHelper() {
    }

    static String decisaoIaAuditoria(String status, boolean acionavel, AcaoControleDTO acaoControle) {
        if (acaoControle != null) {
            if (acaoControle.getPerguntaParaUsuario() != null && !acaoControle.getPerguntaParaUsuario().isBlank()) {
                return "PERGUNTOU_AO_DONO";
            }
            if (acaoControle.getTextoClientePreview() != null && !acaoControle.getTextoClientePreview().isBlank()) {
                return "PREPAROU_SEM_ENVIAR";
            }
            return "PENDENTE_REVISAO";
        }
        if ("ULTIMA_MINHA".equals(status)) {
            return "AGUARDAR_CLIENTE";
        }
        if ("SEM_TEXTO_UTIL".equals(status)) {
            return "IGNORAR_SEM_TEXTO";
        }
        if (acionavel) {
            return "ACIONAVEL_READ_ONLY";
        }
        return "IGNORAR_SEM_GATILHO";
    }

    static String perguntaReadOnlyAuditoria(String status) {
        if ("ULTIMA_CLIENTE_ACIONAVEL".equals(status)) {
            return "Criar uma pendencia de ensino para esta conversa antes de qualquer resposta ao cliente?";
        }
        return null;
    }

    static Map<String, Object> painelCenariosAuditoria(List<InboxConversaAuditoriaDTO> conversas) {
        List<InboxConversaAuditoriaDTO> itens = conversas == null ? List.of() : conversas;
        long perguntouAoDono = itens.stream()
            .filter(item -> item.getPerguntaParaUsuario() != null && !item.getPerguntaParaUsuario().isBlank())
            .count();
        long ensinou = itens.stream()
            .filter(item -> item.getSinais() != null && item.getSinais().contains("ensino_respondido"))
            .count();
        long respondeu = itens.stream()
            .filter(item -> item.getRascunhoPendente() != null && !item.getRascunhoPendente().isBlank())
            .count();
        long bloqueou = itens.stream()
            .filter(item -> "BLOQUEAR".equalsIgnoreCase(item.getDecisaoQualidade()))
            .count();
        long vazouContexto = itens.stream()
            .filter(item -> item.getSinais() != null && item.getSinais().contains("vazou_contexto"))
            .count();
        long pendente = itens.stream()
            .filter(InboxConversaAuditoriaDTO::isPendenciaExistente)
            .count();

        Map<String, Object> painel = new LinkedHashMap<>();
        painel.put("lido", itens.size());
        painel.put("perguntouAoDono", perguntouAoDono);
        painel.put("ensinou", ensinou);
        painel.put("respondeu", respondeu);
        painel.put("bloqueou", bloqueou);
        painel.put("vazouContexto", vazouContexto);
        painel.put("pendente", pendente);
        return painel;
    }

    static boolean mensagemVisivelAuditoria(MensagemConversa mensagem) {
        return mensagem != null
            && !Boolean.TRUE.equals(mensagem.getExcluida())
            && !Boolean.TRUE.equals(mensagem.getSistema())
            && mensagem.getTexto() != null
            && !mensagem.getTexto().isBlank();
    }

    static boolean mensagemAcionavelAuditoria(MensagemConversa mensagem) {
        return mensagemVisivelAuditoria(mensagem) && Boolean.FALSE.equals(mensagem.getEnviadaPorMim());
    }

    static String autorAuditoria(MensagemConversa mensagem) {
        if (mensagem == null) {
            return "SEM_TEXTO";
        }
        if (Boolean.TRUE.equals(mensagem.getEnviadaPorMim())) {
            return "EU";
        }
        if (Boolean.FALSE.equals(mensagem.getEnviadaPorMim())) {
            return "CLIENTE";
        }
        return "DESCONHECIDO";
    }

    static String statusAuditoria(MensagemConversa ultima, boolean acionavel, boolean pendente) {
        if (pendente) {
            return "PENDENCIA_ABERTA";
        }
        if (ultima == null) {
            return "SEM_TEXTO_UTIL";
        }
        if (acionavel) {
            return "ULTIMA_CLIENTE_ACIONAVEL";
        }
        if (Boolean.TRUE.equals(ultima.getEnviadaPorMim())) {
            return "ULTIMA_MINHA";
        }
        return "IGNORADA";
    }

    static String motivoAuditoria(String status) {
        return switch (status) {
            case "PENDENCIA_ABERTA" -> "Ja existe pendencia aguardando decisao humana.";
            case "SEM_TEXTO_UTIL" -> "Nenhuma mensagem util visivel foi encontrada.";
            case "ULTIMA_CLIENTE_ACIONAVEL" -> "Ultima mensagem util e do cliente; deve virar revisao humana.";
            case "ULTIMA_MINHA" -> "Ultima mensagem util foi sua; nao responde para evitar duplicidade.";
            default -> "Conversa lida, mas sem gatilho seguro para resposta.";
        };
    }

    static List<String> sinaisAuditoria(MensagemConversa ultima, List<MensagemConversa> mensagens, boolean pendente) {
        List<String> sinais = new ArrayList<>();
        if (mensagens.isEmpty()) {
            sinais.add("sem_mensagens");
        }
        if (ultima == null) {
            sinais.add("sem_texto_util");
        } else if (Boolean.TRUE.equals(ultima.getEnviadaPorMim())) {
            sinais.add("ultima_mensagem_minha");
        } else if (Boolean.FALSE.equals(ultima.getEnviadaPorMim())) {
            sinais.add("ultima_mensagem_cliente");
        }
        if (pendente) {
            sinais.add("pendencia_aberta");
        }
        return sinais;
    }

    static String analiseAuditoria(
        ConversaResumo conversa,
        int totalMensagens,
        String status,
        Optional<AcaoPendente> pendente
    ) {
        String titulo = conversa.getNomeProjeto() != null ? conversa.getNomeProjeto() : "projeto sem titulo";
        if ("PENDENCIA_ABERTA".equals(status)) {
            return "Li a conversa \"" + titulo + "\" com " + totalMensagens
                + " mensagens. Ja existe pendencia #" + pendente.map(AcaoPendente::getId).orElse(null)
                + ", entao nao criei resposta nova.";
        }
        if ("ULTIMA_CLIENTE_ACIONAVEL".equals(status)) {
            return "Li a conversa \"" + titulo + "\" com " + totalMensagens
                + " mensagens. A ultima fala util e do cliente e deve ser tratada como gatilho para revisao humana.";
        }
        if ("ULTIMA_MINHA".equals(status)) {
            return "Li a conversa \"" + titulo + "\" com " + totalMensagens
                + " mensagens. A ultima fala util foi sua, entao a IA nao deve responder agora.";
        }
        if ("SEM_TEXTO_UTIL".equals(status)) {
            return "Li a conversa \"" + titulo + "\", mas nao encontrei texto util visivel para responder.";
        }
        return "Li a conversa \"" + titulo + "\" e nao encontrei gatilho seguro para acao.";
    }
}
