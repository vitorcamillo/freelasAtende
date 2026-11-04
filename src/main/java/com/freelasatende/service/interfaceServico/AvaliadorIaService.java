package com.freelasatende.service.interfaceServico;

import com.freelasatende.model.dto.AvaliacaoResultado;
import com.freelasatende.model.dto.QualidadeRascunhoDTO;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;

import java.util.List;
import java.util.Map;

/**
 * Interface para serviço de avaliação por IA.
 */
public interface AvaliadorIaService {

    /**
     * Avalia se somos capazes de atender o projeto.
     */
    boolean somosCapazesDeAtender(Projeto projeto);

    /**
     * Avalia o projeto e retorna resultado com motivo de recusa, se aplicável.
     */
    AvaliacaoResultado avaliarProjeto(Projeto projeto);

    /**
     * Refaz a avaliacao ignorando decisao em cache, para revisao operacional explicita.
     */
    AvaliacaoResultado reavaliarProjeto(Projeto projeto);

    /**
     * Gera dúvidas sobre o projeto para consultar o usuário.
     */
    List<String> gerarDuvidas(Projeto projeto);

    /**
     * Sugere uma proposta de valor para o projeto.
     */
    Proposta sugerirProposta(Projeto projeto);

    /**
     * Gera o texto da proposta com base no projeto e no valor sugerido.
     */
    String gerarTextoProposta(Projeto projeto, Proposta proposta);

    /**
     * Sugere uma resposta para uma conversa com o cliente.
     */
    String sugerirRespostaConversa(Projeto projeto, String historicoConversa, String ultimaMensagemCliente);

    /**
     * Reescreve um rascunho pendente usando orientação humana e contexto já coletado.
     */
    String reescreverRascunhoComOrientacao(String contexto, String rascunhoAtual, String orientacaoUsuario);

    /**
     * Audita rascunho ao cliente com a IA configurada.
     */
    QualidadeRascunhoDTO auditarRascunhoCliente(String contexto, String texto);

    /**
     * Otimiza a memória de contexto para evitar estouro de tokens
     */
    void otimizarMemoriaContexto();

    /**
     * Retorna diagnostico detalhado da memoria da IA para observabilidade.
     */
    Map<String, Object> obterDiagnosticoMemoriaIa();

    /**
     * Salva regras consolidadas editadas manualmente pelo usuário.
     */
    void salvarRegrasConsolidadas(String novasRegras);
}
