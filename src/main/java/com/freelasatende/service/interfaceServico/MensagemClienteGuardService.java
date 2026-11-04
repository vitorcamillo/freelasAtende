package com.freelasatende.service.interfaceServico;

import com.freelasatende.model.entity.MensagemClienteRegistro;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.ResultadoValidacaoMensagem;
import com.freelasatende.model.enums.TipoAcaoPendente;

public interface MensagemClienteGuardService {

    ValidacaoRascunho validarRascunho(ContextoMensagemCliente contexto, String texto);

    MensagemClienteRegistro registrarRascunho(ContextoMensagemCliente contexto, String texto, ValidacaoRascunho validacao);

    MensagemClienteRegistro registrarEnvio(ContextoMensagemCliente contexto, String texto);

    MensagemClienteRegistro registrarEnvioSimulado(ContextoMensagemCliente contexto, String texto);

    void descartarRascunhosPendentes(ContextoMensagemCliente contexto);

    record ContextoMensagemCliente(
        PlataformaFreela plataforma,
        Long projetoId,
        Long conversaId,
        TipoAcaoPendente tipo,
        boolean permitirOverride
    ) {
        public ContextoMensagemCliente {
            if (plataforma == null) {
                throw new IllegalArgumentException("Contexto de mensagem ao cliente exige plataforma explicita");
            }
        }
    }

    record ValidacaoRascunho(
        ResultadoValidacaoMensagem resultado,
        String motivo,
        String fingerprint,
        String textoPreview
    ) {
        public boolean permitido() {
            return resultado == ResultadoValidacaoMensagem.PERMITIDO;
        }
    }
}
