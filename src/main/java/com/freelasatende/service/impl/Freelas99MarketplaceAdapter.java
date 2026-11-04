package com.freelasatende.service.impl;

import com.freelasatende.model.dto.ConexoesDisponiveis;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.Cliente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import com.freelasatende.service.interfaceServico.MarketplaceCapabilities;
import com.freelasatende.service.interfaceServico.ScraperService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
class Freelas99MarketplaceAdapter implements MarketplaceAdapter {

    private final ScraperService scraperService;

    @Override
    public PlataformaFreela plataforma() {
        return PlataformaFreela.FREELAS_99;
    }

    @Override
    public MarketplaceCapabilities capabilities() {
        return MarketplaceCapabilities.completa();
    }

    @Override
    public List<Projeto> buscarProjetosNovos() {
        return scraperService.buscarProjetosNovos();
    }

    @Override
    public Projeto buscarDetalhesProjeto(Long id, String slug) {
        return scraperService.buscarDetalhesProjeto(id, slug);
    }

    @Override
    public Cliente buscarCliente(Long id) {
        return scraperService.buscarCliente(id);
    }

    @Override
    public boolean enviarPergunta(Long projetoId, String mensagem) {
        return scraperService.enviarPergunta(projetoId, mensagem);
    }

    @Override
    public boolean enviarPergunta(Projeto projeto, String mensagem) {
        return enviarPergunta(projeto.getId(), mensagem);
    }

    @Override
    public Proposta enviarProposta(Projeto projeto, Proposta proposta) {
        return scraperService.enviarProposta(projeto, proposta);
    }

    @Override
    public boolean cancelarProposta(Long projetoId) {
        return scraperService.cancelarProposta(projetoId);
    }

    @Override
    public List<ConversaResumo> listarConversasInbox() {
        return scraperService.listarConversasInbox();
    }

    @Override
    public List<MensagemConversa> listarMensagensConversa(Long conversaId) {
        return scraperService.listarMensagensConversa(conversaId);
    }

    @Override
    public List<MensagemConversa> listarMensagensConversa(Long conversaId, boolean marcarComoVisualizada) {
        return scraperService.listarMensagensConversa(conversaId, marcarComoVisualizada);
    }

    @Override
    public MensagemConversa enviarMensagemConversa(Long conversaId, String texto) {
        return scraperService.enviarMensagemConversa(conversaId, texto);
    }

    @Override
    public ConexoesDisponiveis obterConexoesDisponiveis() {
        return scraperService.obterConexoesDisponiveis();
    }

    @Override
    public boolean confirmarMfaSessao(Long sessaoId, String codigo) {
        return scraperService.confirmarMfaSessao(sessaoId, codigo);
    }

    @Override
    public boolean verificarLogin() {
        return scraperService.verificarLogin();
    }
}
