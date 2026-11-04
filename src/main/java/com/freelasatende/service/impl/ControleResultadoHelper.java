package com.freelasatende.service.impl;

import com.freelasatende.model.dto.AcaoControleDTO;
import com.freelasatende.model.dto.ControleAcaoResultadoDTO;

import java.util.Optional;
import java.util.function.Function;

class ControleResultadoHelper {

    private ControleResultadoHelper() {
    }

    static ControleAcaoResultadoDTO resultado(
        Long id,
        String mensagem,
        Function<Long, Optional<AcaoControleDTO>> buscarAcao
    ) {
        return ControleAcaoResultadoDTO.builder()
            .sucesso(sucesso(mensagem))
            .mensagem(mensagem)
            .acao(id != null ? buscarAcao.apply(id).orElse(null) : null)
            .build();
    }

    private static boolean sucesso(String mensagem) {
        if (mensagem == null) {
            return false;
        }
        String normalizada = mensagem.toLowerCase();
        // Fluxos de ensino gravam o aprendizado sem enviar nada ao cliente; a menção
        // a "nada foi enviado" nesses casos é informativa, não um erro da ação.
        if (normalizada.startsWith("contexto salvo") || normalizada.startsWith("resposta salva")) {
            return true;
        }
        return !normalizada.contains("erro")
            && !normalizada.contains("falha")
            && !normalizada.contains("falhou")
            && !normalizada.contains("bloqueado")
            && !normalizada.contains("bloqueada")
            && !normalizada.contains("obrigatório")
            && !normalizada.contains("obrigatorio")
            && !normalizada.contains("nada foi enviado")
            && !normalizada.contains("não encontrada")
            && !normalizada.contains("nao encontrada")
            && !normalizada.contains("não está pendente")
            && !normalizada.contains("nao esta pendente")
            && !normalizada.contains("nao esta ignorado")
            && !normalizada.contains("me mande")
            && !normalizada.contains("responda a dúvida")
            && !normalizada.contains("responda a duvida")
            && !normalizada.contains("cancelamento não usa")
            && !normalizada.contains("cancelamento nao usa");
    }
}
