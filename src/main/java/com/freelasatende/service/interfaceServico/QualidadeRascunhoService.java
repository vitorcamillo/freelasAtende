package com.freelasatende.service.interfaceServico;

import com.freelasatende.model.dto.QualidadeRascunhoDTO;

public interface QualidadeRascunhoService {

    QualidadeRascunhoDTO avaliar(String texto);

    QualidadeRascunhoDTO avaliar(String texto, String statusGuardiao, String motivoGuardiao);
}
