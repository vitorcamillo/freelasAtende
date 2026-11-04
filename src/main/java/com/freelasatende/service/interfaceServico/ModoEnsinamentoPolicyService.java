package com.freelasatende.service.interfaceServico;

import com.freelasatende.model.dto.PoliticaOperacionalResultadoDTO;
import com.freelasatende.model.dto.QualidadeRascunhoDTO;
import com.freelasatende.model.entity.AcaoPendente;

public interface ModoEnsinamentoPolicyService {

    PoliticaOperacionalResultadoDTO avaliar(
        AcaoPendente acao,
        String textoCliente,
        boolean aprovacaoHumana,
        String motivoAprendizado,
        String statusGuardiao,
        String motivoBloqueio,
        QualidadeRascunhoDTO qualidade
    );
}
