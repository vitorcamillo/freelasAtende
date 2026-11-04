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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketplaceGatewayTest {

    @Test
    void agregaInboxEProjetosDosMarketplacesAtivos() {
        MarketplaceGateway gateway = new MarketplaceGateway(new MarketplaceAdapterRegistry(List.of(
            new FakeAdapter(PlataformaFreela.FREELAS_99, 10L),
            new FakeAdapter(PlataformaFreela.WORKANA, 20L)
        ), "*"));

        assertEquals(List.of(PlataformaFreela.FREELAS_99, PlataformaFreela.WORKANA),
            gateway.listarConversasInbox().stream().map(ConversaResumo::getPlataforma).toList());
        assertEquals(List.of(PlataformaFreela.FREELAS_99, PlataformaFreela.WORKANA),
            gateway.buscarProjetosNovos().stream().map(Projeto::getPlataforma).toList());
        assertEquals(PlataformaFreela.WORKANA, gateway.listarMensagensConversa(20L).get(0).getPlataforma());
    }

    private record FakeAdapter(PlataformaFreela plataforma, Long conversaId) implements MarketplaceAdapter {

        @Override
        public MarketplaceCapabilities capabilities() {
            return new MarketplaceCapabilities(true, true, false, false, false, false, false);
        }

        @Override
        public List<Projeto> buscarProjetosNovos() {
            return List.of(Projeto.builder()
                .plataforma(plataforma)
                .id(conversaId)
                .titulo("Projeto " + plataforma.key())
                .build());
        }

        @Override
        public Projeto buscarDetalhesProjeto(Long id, String slug) {
            return null;
        }

        @Override
        public Cliente buscarCliente(Long id) {
            return null;
        }

        @Override
        public boolean enviarPergunta(Long projetoId, String mensagem) {
            return false;
        }

        @Override
        public Proposta enviarProposta(Projeto projeto, Proposta proposta) {
            return null;
        }

        @Override
        public boolean cancelarProposta(Long projetoId) {
            return false;
        }

        @Override
        public List<ConversaResumo> listarConversasInbox() {
            return List.of(ConversaResumo.builder()
                .plataforma(plataforma)
                .idConversa(conversaId)
                .idProjeto(conversaId)
                .nomeProjeto("Projeto " + plataforma.key())
                .build());
        }

        @Override
        public List<MensagemConversa> listarMensagensConversa(Long conversaId) {
            return listarMensagensConversa(conversaId, false);
        }

        @Override
        public List<MensagemConversa> listarMensagensConversa(Long conversaId, boolean marcarComoVisualizada) {
            return List.of(MensagemConversa.builder()
                .plataforma(plataforma)
                .idConversa(conversaId)
                .id(conversaId)
                .texto("mensagem")
                .build());
        }

        @Override
        public MensagemConversa enviarMensagemConversa(Long conversaId, String texto) {
            return null;
        }

        @Override
        public ConexoesDisponiveis obterConexoesDisponiveis() {
            return ConexoesDisponiveis.builder().build();
        }

        @Override
        public boolean confirmarMfaSessao(Long sessaoId, String codigo) {
            return false;
        }

        @Override
        public boolean verificarLogin() {
            return true;
        }
    }
}
