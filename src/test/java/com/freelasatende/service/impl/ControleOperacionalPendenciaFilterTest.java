package com.freelasatende.service.impl;

import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControleOperacionalPendenciaFilterTest {

    @Test
    void ocultaAbordagemInicialAutomaticaPendente() {
        AcaoPendente acao = AcaoPendente.builder()
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .resumo("Abordagem inicial automatica | Ja foi atendido?")
            .build();

        assertFalse(ControleOperacionalPendenciaFilter.visivelAoOperador(acao));
    }

    @Test
    void mantemPerguntaManualPendenteVisivel() {
        AcaoPendente acao = AcaoPendente.builder()
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .resumo("Pergunta inicial pendente | envio direto indisponivel")
            .build();

        assertTrue(ControleOperacionalPendenciaFilter.visivelAoOperador(acao));
    }
}
