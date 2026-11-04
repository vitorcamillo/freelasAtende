package com.freelasatende.service.interfaceServico;

import com.freelasatende.model.entity.Cliente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.model.dto.ConexoesDisponiveis;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;

import java.util.List;

/**
 * Interface para serviço de scraping do 99Freelas.
 */
public interface ScraperService {

    /**
     * Busca novos projetos na plataforma.
     */
    List<Projeto> buscarProjetosNovos();

    /**
     * Busca detalhes de um projeto específico.
     */
    Projeto buscarDetalhesProjeto(Long id, String slug);

    /**
     * Busca informações de um cliente.
     */
    Cliente buscarCliente(Long id);

    /**
     * Envia uma pergunta ao cliente sobre um projeto.
     */
    boolean enviarPergunta(Long projetoId, String mensagem);

    /**
     * Envia proposta para um projeto usando a API interna capturada.
     */
    Proposta enviarProposta(Projeto projeto, Proposta proposta);

    /**
     * Cancela uma proposta enviada.
     */
    boolean cancelarProposta(Long projetoId);

    /**
     * Lista conversas da inbox da conta logada.
     */
    List<ConversaResumo> listarConversasInbox();

    /**
     * Lista mensagens de uma conversa.
     */
    List<MensagemConversa> listarMensagensConversa(Long conversaId);

    /**
     * Lista mensagens de uma conversa, controlando se a plataforma deve marcar como visualizada.
     */
    List<MensagemConversa> listarMensagensConversa(Long conversaId, boolean marcarComoVisualizada);

    /**
     * Envia uma mensagem em uma conversa já existente.
     */
    MensagemConversa enviarMensagemConversa(Long conversaId, String texto);

    /**
     * Lê o estoque real de conexões na conta logada.
     */
    ConexoesDisponiveis obterConexoesDisponiveis();

    /**
     * Confirma um MFA de sessão quando a plataforma exigir.
     */
    boolean confirmarMfaSessao(Long sessaoId, String codigo);

    /**
     * Verifica se ainda está logado.
     */
    boolean verificarLogin();
}
