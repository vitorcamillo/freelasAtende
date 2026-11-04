package com.freelasatende.service.impl;

import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;

final class ControleOperacionalPendenciaFilter {

    private static final String RESUMO_ABORDAGEM_INICIAL_AUTOMATICA = "Abordagem inicial automatica | ";

    private ControleOperacionalPendenciaFilter() {
    }

    static boolean visivelAoOperador(AcaoPendente acao) {
        return !abordagemInicialAutomaticaPendente(acao);
    }

    private static boolean abordagemInicialAutomaticaPendente(AcaoPendente acao) {
        return acao != null
            && acao.getStatus() == StatusAcaoPendente.PENDENTE
            && acao.getTipo() == TipoAcaoPendente.ENVIAR_PERGUNTA
            && acao.getResumo() != null
            && acao.getResumo().startsWith(RESUMO_ABORDAGEM_INICIAL_AUTOMATICA);
    }
}
