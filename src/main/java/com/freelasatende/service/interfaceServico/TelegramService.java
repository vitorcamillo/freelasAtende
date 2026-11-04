package com.freelasatende.service.interfaceServico;

import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.model.dto.ProjetoInteresseResumoDTO;

/**
 * Interface para serviço de notificações do Telegram.
 */
public interface TelegramService {

    /**
     * Envia uma notificação simples.
     */
    void enviarNotificacao(String mensagem);

    /**
     * Envia dúvidas sobre um projeto ao usuário.
     */
    void enviarDuvidas(Projeto projeto, String duvidas);

    /**
     * Pergunta se o usuário aprova uma proposta.
     */
    void perguntarAprovacao(Projeto projeto, Proposta proposta);

    /**
     * Notifica sobre projeto que pode ser atendido.
     */
    void notificarProjetoInteressante(Projeto projeto);

    /**
     * Notifica sobre projeto que pode ser atendido com leitura operacional da IA.
     */
    void notificarProjetoInteressante(Projeto projeto, ProjetoInteresseResumoDTO resumo);

    /**
     * Envia uma ação pendente com botões de aprovação/rejeição.
     */
    Long enviarAcaoPendenteComBotoes(String mensagem, Long acaoId);

    /**
     * Envia uma ação pendente sem botões de aprovação, para casos que exigem reescrita/ensino antes de enviar.
     */
    Long enviarAcaoPendenteParaRevisaoComBotoes(String mensagem, Long acaoId);

    /**
     * Apaga uma mensagem enviada no chat configurado do operador.
     */
    void apagarMensagemDaConta(long messageId);
}
