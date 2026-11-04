package com.freelasatende.service.interfaceServico;

import com.freelasatende.model.entity.Aprendizado;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.MotivoAprendizado;
import com.freelasatende.model.enums.TipoAcaoPendente;

import java.util.List;
import java.util.Map;

/**
 * Interface para serviço de aprendizado contínuo.
 */
public interface AprendizadoService {

    /**
     * Salva um novo aprendizado.
     */
    Aprendizado salvar(Aprendizado aprendizado);

    /**
     * Registra uma decisão do usuário.
     */
    void registrarDecisao(Long projetoId, String tituloProjeto,
                          Boolean foiAprovado, String motivo,
                          ModoSistema modoUsado, String feedback);

    void registrarDecisaoDetalhada(Long projetoId, String tituloProjeto,
                                   Boolean foiAprovado, TipoAcaoPendente tipoAcao,
                                   ModoSistema modoUsado, String feedback,
                                   MotivoAprendizado motivoAprendizado,
                                   String textoOriginal, String textoReescrito,
                                   String origemFeedback);

    /**
     * Busca regras aprendidas.
     */
    List<String> buscarRegrasAprendidas();

    /**
     * Busca histórico de decisões.
     */
    List<Aprendizado> buscarHistorico();

    /**
     * Atualiza regras aprendidas com base no feedback.
     */
    void atualizarRegras(String feedback);

    Map<String, Object> obterResumoOperacional();
}
