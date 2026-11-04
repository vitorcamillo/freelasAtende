package com.freelasatende.service.impl;

import com.freelasatende.model.dto.ConexoesDisponiveis;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.Cliente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusProjeto;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import com.freelasatende.service.interfaceServico.MarketplaceCapabilities;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Component
class TesteReadOnlyMarketplaceAdapter implements MarketplaceAdapter {

    private static final MarketplaceCapabilities CAPABILITIES = new MarketplaceCapabilities(
        true,
        true,
        false,
        false,
        false,
        false,
        false
    );

    @Override
    public PlataformaFreela plataforma() {
        return PlataformaFreela.TESTE;
    }

    @Override
    public MarketplaceCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public List<Projeto> buscarProjetosNovos() {
        return List.of(projetoDemo(10L, "Landing page institucional TESTE"));
    }

    @Override
    public Projeto buscarDetalhesProjeto(Long id, String slug) {
        Long idExterno = PlataformaFreela.TESTE.idExterno(id);
        return projetoDemo(idExterno != null ? idExterno : 10L, "Projeto TESTE " + (slug == null ? "read-only" : slug));
    }

    @Override
    public Cliente buscarCliente(Long id) {
        throw unsupported("buscar cliente");
    }

    @Override
    public boolean enviarPergunta(Long projetoId, String mensagem) {
        throw unsupported("enviar pergunta");
    }

    @Override
    public Proposta enviarProposta(Projeto projeto, Proposta proposta) {
        throw unsupported("enviar proposta");
    }

    @Override
    public boolean cancelarProposta(Long projetoId) {
        throw unsupported("cancelar proposta");
    }

    @Override
    public List<ConversaResumo> listarConversasInbox() {
        return List.of();
    }

    @Override
    public List<MensagemConversa> listarMensagensConversa(Long conversaId) {
        return List.of();
    }

    @Override
    public List<MensagemConversa> listarMensagensConversa(Long conversaId, boolean marcarComoVisualizada) {
        return List.of();
    }

    @Override
    public MensagemConversa enviarMensagemConversa(Long conversaId, String texto) {
        throw unsupported("enviar mensagem de conversa");
    }

    @Override
    public ConexoesDisponiveis obterConexoesDisponiveis() {
        return ConexoesDisponiveis.builder()
            .total(0)
            .renovaveis(0)
            .naoExpiraveis(0)
            .build();
    }

    @Override
    public boolean confirmarMfaSessao(Long sessaoId, String codigo) {
        throw unsupported("confirmar MFA");
    }

    @Override
    public boolean verificarLogin() {
        return true;
    }

    private Projeto projetoDemo(Long idExterno, String titulo) {
        Long idInterno = PlataformaFreela.TESTE.idInterno(idExterno);
        return Projeto.builder()
            .plataforma(PlataformaFreela.TESTE)
            .id(idInterno)
            .idExterno(idExterno)
            .titulo(titulo)
            .descricao("Projeto deterministico de teste para validar adaptadores sem chamadas externas.")
            .descricaoCurta("Piloto read-only")
            .categoria("Programacao")
            .subcategoria("Sistemas web")
            .orcamento(BigDecimal.valueOf(1200))
            .numeroPropostas(0)
            .urlProjeto("teste://projetos/" + idExterno)
            .nomeCliente("Cliente TESTE")
            .clienteId(PlataformaFreela.TESTE.idInterno(50L))
            .dataCriacao(LocalDateTime.now())
            .status(StatusProjeto.NOVO)
            .foiProcessado(false)
            .build();
    }

    private MarketplaceAdapterException unsupported(String operacao) {
        return new MarketplaceAdapterException(
            PlataformaFreela.TESTE,
            MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL,
            "Adapter TESTE e read-only; operacao indisponivel: " + operacao
        );
    }
}
