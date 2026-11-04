package com.freelasatende.service.interfaceServico;

import com.freelasatende.model.dto.ConexoesDisponiveis;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.Cliente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.model.enums.PlataformaFreela;

import java.util.List;

/**
 * Contrato estável para integrar plataformas de freelas sem vazar detalhes do scraper.
 */
public interface MarketplaceAdapter {

    PlataformaFreela plataforma();

    MarketplaceCapabilities capabilities();

    List<Projeto> buscarProjetosNovos();

    Projeto buscarDetalhesProjeto(Long id, String slug);

    Cliente buscarCliente(Long id);

    boolean enviarPergunta(Long projetoId, String mensagem);

    default boolean enviarPergunta(Projeto projeto, String mensagem) {
        return enviarPergunta(projeto.getId(), mensagem);
    }

    Proposta enviarProposta(Projeto projeto, Proposta proposta);

    boolean cancelarProposta(Long projetoId);

    List<ConversaResumo> listarConversasInbox();

    List<MensagemConversa> listarMensagensConversa(Long conversaId);

    List<MensagemConversa> listarMensagensConversa(Long conversaId, boolean marcarComoVisualizada);

    MensagemConversa enviarMensagemConversa(Long conversaId, String texto);

    ConexoesDisponiveis obterConexoesDisponiveis();

    boolean confirmarMfaSessao(Long sessaoId, String codigo);

    boolean verificarLogin();
}
