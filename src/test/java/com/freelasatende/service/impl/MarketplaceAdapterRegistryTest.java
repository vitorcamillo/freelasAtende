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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketplaceAdapterRegistryTest {

    @Test
    void resolveAdapterFakePorPlataforma() {
        MarketplaceAdapter fake = new FakeMarketplaceAdapter();
        MarketplaceAdapterRegistry registry = new MarketplaceAdapterRegistry(List.of(fake));

        assertSame(fake, registry.para(PlataformaFreela.TESTE));
        assertEquals(1, registry.ativos().size());
        assertEquals("Projeto fake", registry.para(PlataformaFreela.TESTE).buscarProjetosNovos().get(0).getTitulo());
    }

    @Test
    void freelas99AdapterPreservaDelegacaoDoScraperAtual() {
        ScraperService scraperService = mock(ScraperService.class);
        Projeto projeto = Projeto.builder().id(10L).titulo("Projeto 99").build();
        when(scraperService.buscarProjetosNovos()).thenReturn(List.of(projeto));
        when(scraperService.verificarLogin()).thenReturn(true);

        MarketplaceAdapter adapter = new Freelas99MarketplaceAdapter(scraperService);

        assertEquals(PlataformaFreela.FREELAS_99, adapter.plataforma());
        assertEquals(List.of(projeto), adapter.buscarProjetosNovos());
        assertEquals(true, adapter.verificarLogin());
        verify(scraperService).buscarProjetosNovos();
        verify(scraperService).verificarLogin();
    }

    @Test
    void normalizaErroParaPlataformaSemAdapter() {
        MarketplaceAdapterRegistry registry = new MarketplaceAdapterRegistry(List.of(new FakeMarketplaceAdapter()));

        MarketplaceAdapterException ex = assertThrows(
            MarketplaceAdapterException.class,
            () -> registry.para(PlataformaFreela.FREELAS_99)
        );

        assertEquals(PlataformaFreela.FREELAS_99, ex.getPlataforma());
        assertEquals(MarketplaceAdapterErrorType.PLATAFORMA_NAO_SUPORTADA, ex.getType());
    }

    @Test
    void filtraAdaptersAtivosPorConfiguracao() {
        ScraperService scraperService = mock(ScraperService.class);
        MarketplaceAdapterRegistry registry = new MarketplaceAdapterRegistry(
            List.of(new Freelas99MarketplaceAdapter(scraperService), new FakeMarketplaceAdapter()),
            "TESTE"
        );

        assertEquals(List.of(PlataformaFreela.TESTE), registry.ativos().stream()
            .map(MarketplaceAdapter::plataforma)
            .toList());
    }

    @Test
    void falhaRapidoQuandoMarketplaceConfiguradoNaoExiste() {
        ScraperService scraperService = mock(ScraperService.class);

        assertThrows(IllegalArgumentException.class, () -> new MarketplaceAdapterRegistry(
            List.of(new Freelas99MarketplaceAdapter(scraperService)),
            "WORKNAA"
        ));
    }

    private static class FakeMarketplaceAdapter implements MarketplaceAdapter {

        @Override
        public PlataformaFreela plataforma() {
            return PlataformaFreela.TESTE;
        }

        @Override
        public MarketplaceCapabilities capabilities() {
            return new MarketplaceCapabilities(true, false, false, false, false, false, false);
        }

        @Override
        public List<Projeto> buscarProjetosNovos() {
            return List.of(Projeto.builder()
                .plataforma(PlataformaFreela.TESTE)
                .id(1_000_000_000_010L)
                .idExterno(10L)
                .titulo("Projeto fake")
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
