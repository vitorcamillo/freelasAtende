package com.freelasatende.service.impl;

import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.model.enums.PlataformaFreela;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TesteReadOnlyMarketplaceAdapterTest {

    private final TesteReadOnlyMarketplaceAdapter adapter = new TesteReadOnlyMarketplaceAdapter();

    @Test
    void listaProjetoDemoComIdentidadeDaPlataformaTeste() {
        List<Projeto> projetos = adapter.buscarProjetosNovos();

        assertEquals(1, projetos.size());
        Projeto projeto = projetos.get(0);
        assertEquals(PlataformaFreela.TESTE, projeto.getPlataforma());
        assertEquals(10L, projeto.getIdExterno());
        assertEquals(PlataformaFreela.TESTE.idInterno(10L), projeto.getId());
        assertTrue(adapter.capabilities().discovery());
        assertTrue(adapter.capabilities().inbox());
        assertFalse(adapter.capabilities().propostas());
    }

    @Test
    void operacoesDeEscritaFalhamFechadas() {
        assertUnsupported(() -> adapter.enviarPergunta(10L, "texto"));
        assertUnsupported(() -> adapter.enviarProposta(Projeto.builder().id(10L).build(), Proposta.builder().build()));
        assertUnsupported(() -> adapter.cancelarProposta(10L));
        assertUnsupported(() -> adapter.enviarMensagemConversa(20L, "texto"));
        assertUnsupported(() -> adapter.buscarCliente(50L));
        assertUnsupported(() -> adapter.confirmarMfaSessao(1L, "123456"));
    }

    @Test
    void registryPodeAtivarSomenteAdapterTeste() {
        MarketplaceAdapterRegistry registry = new MarketplaceAdapterRegistry(List.of(adapter), "TESTE");

        assertEquals(List.of(PlataformaFreela.TESTE), registry.ativos().stream()
            .map(item -> item.plataforma())
            .toList());
        assertEquals(PlataformaFreela.TESTE, registry.para(PlataformaFreela.TESTE).plataforma());
    }

    private void assertUnsupported(UnsupportedCall call) {
        MarketplaceAdapterException ex = assertThrows(MarketplaceAdapterException.class, call::execute);

        assertEquals(PlataformaFreela.TESTE, ex.getPlataforma());
        assertEquals(MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL, ex.getType());
    }

    @FunctionalInterface
    private interface UnsupportedCall {
        void execute();
    }
}
