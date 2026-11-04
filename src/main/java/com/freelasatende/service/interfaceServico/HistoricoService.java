package com.freelasatende.service.interfaceServico;

import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.TipoInteracao;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Interface para servico de historico de interacoes.
 * Mantem registro de todas as conversas e decisoes.
 */
public interface HistoricoService {

    /**
     * Registra uma interacao no historico.
     */
    void registrarInteracao(Interacao interacao);

    /**
     * Busca historico de um projeto especifico.
     */
    List<Interacao> buscarPorProjeto(Long projetoId);

    default List<Interacao> buscarPorProjeto(PlataformaFreela plataforma, Long projetoId) {
        return buscarPorProjeto(projetoId);
    }

    /**
     * Busca ultimas N interacoes.
     */
    List<Interacao> buscarUltimas(int quantidade);

    /**
     * Busca interacoes por tipo.
     */
    List<Interacao> buscarPorTipo(TipoInteracao tipo);

    /**
     * Conta interacoes do dia.
     */
    int contarInteracoesDoDia();

    /**
     * Verifica se projeto ja foi processado.
     */
    boolean projetoJaProcessado(Long projetoId);

    default boolean projetoJaProcessado(PlataformaFreela plataforma, Long projetoId) {
        return projetoJaProcessado(projetoId);
    }

    /**
     * Busca interacoes recentes.
     */
    List<Interacao> obterInteracoesRecentes();
}
