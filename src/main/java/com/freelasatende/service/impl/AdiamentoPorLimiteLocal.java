package com.freelasatende.service.impl;

import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.repository.AcaoPendenteRepository;

import java.util.List;

/**
 * Projeto cuja abordagem inicial foi adiada pelo limite diario LOCAL de conexoes: a
 * avaliacao ja registrou interacao conclusiva (PROJETO_APROVADO), mas nada foi enviado.
 * O historico o trataria como processado; aqui ele volta a ser elegivel ate um envio
 * ser aprovado ou executado.
 */
final class AdiamentoPorLimiteLocal {

    private AdiamentoPorLimiteLocal() {
    }

    static boolean pendente(AcaoPendenteRepository repositorio, Projeto projeto) {
        PlataformaFreela plataforma = PlataformaProjetoHelper.plataformaOuPadrao(projeto);
        boolean adiado = repositorio.findByPlataformaAndProjetoIdAndTipoAndStatusOrderByDataCriacaoDesc(
                plataforma, projeto.getId(), TipoAcaoPendente.ENVIAR_PERGUNTA, StatusAcaoPendente.ERRO)
            .stream()
            .anyMatch(acao -> AutoAtendimentoProjetoAbordagemInicial.OBSERVACAO_LIMITE_LOCAL
                .equals(acao.getObservacaoUsuario()));
        return adiado && repositorio.countByPlataformaAndProjetoIdAndTipoAndStatusIn(
            plataforma, projeto.getId(), TipoAcaoPendente.ENVIAR_PERGUNTA,
            List.of(StatusAcaoPendente.APROVADA, StatusAcaoPendente.EXECUTADA)) == 0;
    }
}
