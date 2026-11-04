package com.freelasatende.service.interfaceServico;

import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;

import java.util.Collection;

public interface AutoAtendimentoService {

    void processarProjeto(Projeto projeto, ModoSistema modo);

    String reprocessarProjetoIgnorado(Long projetoId, ModoSistema modo);

    void sincronizarInbox(ModoSistema modo);

    void sincronizarInbox(ModoSistema modo, int limiteConversas);

    void sincronizarInbox(ModoSistema modo, int limiteConversas, Collection<PlataformaFreela> plataformas);

    String processarFeedback(Long acaoId, boolean aprovado, String comentario);

    String reescreverAcao(Long acaoId, String novoTexto, String comentario);

    String registrarContextoEnsino(Long acaoId, String orientacaoUsuario);

    String responderDuvidaIa(Long acaoId, String respostaUsuario, String regraConfirmada);

    long contarAcoesPendentes();

    void otimizarMemoriaContexto();

    void cancelarPropostasAntigas(int dias);
}
