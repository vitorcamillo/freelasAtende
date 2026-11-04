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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
class WorkanaMarketplaceAdapter implements MarketplaceAdapter {

    private static final MarketplaceCapabilities CAPABILITIES = new MarketplaceCapabilities(
        true,
        true,
        true,
        true,
        false,
        false,
        false
    );

    private final WorkanaClient client;
    private final WorkanaProjectParser parser;
    private final WorkanaProposalClient proposalClient;

    @Override
    public PlataformaFreela plataforma() {
        return PlataformaFreela.WORKANA;
    }

    @Override
    public MarketplaceCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public List<Projeto> buscarProjetosNovos() {
        return parser.parseProjetos(client.buscarProjetosJsonParaDiscovery());
    }

    @Override
    public Projeto buscarDetalhesProjeto(Long id, String slug) {
        return proposalClient.buscarDetalhes(id, slug);
    }

    @Override
    public Cliente buscarCliente(Long id) {
        throw unsupported("buscar cliente");
    }

    @Override
    public boolean enviarPergunta(Long projetoId, String mensagem) {
        throw unsupported("enviar pergunta sem contexto do projeto");
    }

    @Override
    public boolean enviarPergunta(Projeto projeto, String mensagem) {
        return client.enviarPergunta(slugProjeto(projeto), MarketplaceHttpContract.mensagemInicialWorkana(mensagem));
    }

    @Override
    public Proposta enviarProposta(Projeto projeto, Proposta proposta) {
        proposalClient.enviar(projeto, proposta);
        proposta.setStatus("ENVIADA");
        proposta.setDataProposta(java.time.LocalDateTime.now());
        proposta.setFoiMinha(true);
        return proposta;
    }

    @Override
    public boolean cancelarProposta(Long projetoId) {
        throw unsupported("cancelar proposta");
    }

    @Override
    public List<ConversaResumo> listarConversasInbox() {
        return client.listarConversasInbox();
    }

    @Override
    public List<MensagemConversa> listarMensagensConversa(Long conversaId) {
        return client.listarMensagensConversa(conversaId);
    }

    @Override
    public List<MensagemConversa> listarMensagensConversa(Long conversaId, boolean marcarComoVisualizada) {
        return client.listarMensagensConversa(conversaId);
    }

    @Override
    public MensagemConversa enviarMensagemConversa(Long conversaId, String texto) {
        return client.enviarMensagemConversa(conversaId, texto);
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
        try {
            client.verificarLoginEPrepararDiscovery();
            return true;
        } catch (MarketplaceAdapterException e) {
            return false;
        }
    }

    private MarketplaceAdapterException unsupported(String operacao) {
        return new MarketplaceAdapterException(
            PlataformaFreela.WORKANA,
            MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL,
            "Adapter Workana; operacao indisponivel: " + operacao
        );
    }

    private String slugProjeto(Projeto projeto) {
        if (projeto == null) {
            throw new MarketplaceAdapterException(
                PlataformaFreela.WORKANA,
                MarketplaceAdapterErrorType.FALHA_ENVIO,
                "Projeto Workana ausente para envio de pergunta"
            );
        }
        String url = projeto.getUrlProjeto();
        if (url != null && !url.isBlank()) {
            int index = url.lastIndexOf("/job/");
            if (index >= 0) {
                String slug = url.substring(index + "/job/".length());
                int queryIndex = slug.indexOf('?');
                return queryIndex >= 0 ? slug.substring(0, queryIndex) : slug;
            }
        }
        throw new MarketplaceAdapterException(
            PlataformaFreela.WORKANA,
            MarketplaceAdapterErrorType.FALHA_ENVIO,
            "URL do projeto Workana ausente para extrair slug"
        );
    }
}
