package com.freelasatende.config;

import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.service.impl.FreelancerMarketplaceAdapter;
import com.freelasatende.service.impl.UpworkMarketplaceAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OAuthMarketplaceConfigTest {

    @Test
    void upworkConfigMantemDefaults() {
        UpworkConfig config = new UpworkConfig();
        assertEquals("https://www.upwork.com", config.getUrlBase());
        assertEquals(20, config.getMaxProjetos());
        assertEquals("data/upwork-cookies.json", config.getCookiesArquivo());
    }

    @Test
    void freelancerConfigMantemDefaults() {
        FreelancerConfig config = new FreelancerConfig();
        assertEquals("https://www.freelancer.com", config.getUrlBase());
        assertEquals(20, config.getMaxProjetos());
        assertEquals("data/freelancer-cookies.json", config.getCookiesArquivo());
    }

    @Test
    void upworkAdapterVerificaLoginPorCookies() {
        UpworkConfig config = new UpworkConfig();
        config.setCookiesArquivo("/tmp/inexistente-cookies.json");
        UpworkMarketplaceAdapter adapter = new UpworkMarketplaceAdapter(config, null, null);
        assertFalse(adapter.verificarLogin());
        assertEquals(PlataformaFreela.UPWORK, adapter.plataforma());
        assertTrue(adapter.capabilities().propostas());
        assertTrue(adapter.capabilities().discovery());
        assertTrue(adapter.capabilities().inbox());
        // Upwork nao suporta pergunta pre-candidatura: a conversa nasce junto
        // com a proposta (endpoint REST antigo descontinuado; ver fix 96d1fa5).
        assertFalse(adapter.capabilities().perguntas());
    }

    @Test
    void freelancerAdapterVerificaLoginPorCookies() {
        FreelancerConfig config = new FreelancerConfig();
        config.setCookiesArquivo("/tmp/inexistente-cookies.json");
        FreelancerMarketplaceAdapter adapter = new FreelancerMarketplaceAdapter(config, null);
        assertFalse(adapter.verificarLogin());
        assertEquals(PlataformaFreela.FREELANCER, adapter.plataforma());
        assertTrue(adapter.capabilities().propostas());
        assertTrue(adapter.capabilities().discovery());
        assertTrue(adapter.capabilities().inbox());
        assertTrue(adapter.capabilities().perguntas());
    }
}
