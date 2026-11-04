package com.freelasatende.service.impl;

import com.freelasatende.model.dto.PoliticaOperacionalResultadoDTO;
import com.freelasatende.model.dto.QualidadeRascunhoDTO;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.enums.MotivoAprendizado;
import com.freelasatende.service.interfaceServico.AprendizadoService;
import com.freelasatende.service.interfaceServico.ModoEnsinamentoPolicyService;
import com.freelasatende.service.interfaceServico.QualidadeRascunhoService;

final class AutoAtendimentoAprendizadoHelper {

    private final AprendizadoService aprendizadoService;
    private final QualidadeRascunhoService qualidadeRascunhoService;
    private final ModoEnsinamentoPolicyService modoEnsinamentoPolicyService;

    AutoAtendimentoAprendizadoHelper(
        AprendizadoService aprendizadoService,
        QualidadeRascunhoService qualidadeRascunhoService,
        ModoEnsinamentoPolicyService modoEnsinamentoPolicyService
    ) {
        this.aprendizadoService = aprendizadoService;
        this.qualidadeRascunhoService = qualidadeRascunhoService;
        this.modoEnsinamentoPolicyService = modoEnsinamentoPolicyService;
    }

    void registrarFeedbackAprendizado(AcaoPendente acao, boolean aprovado, String comentario, String textoOriginal) {
        if (acao.getProjetoId() == null) {
            return;
        }

        registrarAprendizadoDetalhado(
            acao,
            aprovado,
            comentario,
            textoOriginal,
            null,
            aprovado ? "APROVACAO" : "RECUSA"
        );
    }

    void registrarAprendizadoDetalhado(
        AcaoPendente acao,
        Boolean aprovado,
        String comentario,
        String textoOriginal,
        String textoReescrito,
        String origem
    ) {
        aprendizadoService.registrarDecisaoDetalhada(
            acao.getProjetoId(),
            acao.getTituloProjeto(),
            aprovado,
            acao.getTipo(),
            acao.getModoOrigem(),
            comentario,
            MotivoAprendizado.fromTexto(comentario),
            textoOriginal,
            textoReescrito,
            origem
        );
    }

    PoliticaOperacionalResultadoDTO avaliarPoliticaParaAprovacao(
        AcaoPendente acao,
        String comentario,
        String textoCliente,
        String statusGuardiao,
        String motivoGuardiao
    ) {
        QualidadeRascunhoDTO qualidade = qualidadeRascunhoService.avaliar(
            textoCliente,
            statusGuardiao,
            motivoGuardiao
        );
        return modoEnsinamentoPolicyService.avaliar(
            acao,
            textoCliente,
            true,
            comentario,
            statusGuardiao,
            motivoGuardiao,
            qualidade
        );
    }
}
