package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.FreelancerBrConfig;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.PlataformaFreela;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreelancerBrProjetoParserTest {

    private FreelancerBrProjetoParser parser() {
        return new FreelancerBrProjetoParser(new FreelancerBrConfig());
    }

    @Test
    void extraiProjetosDosLinks() {
        String html = """
            <html><body>
            <a href="https://freelancer.com.br/projetos/site-e-aplicativos/android/desenvolvimento-de-apps-3BOPS371">Desenvolvimento de Apps</a>
            <a href="https://freelancer.com.br/projetos/marketing-e-comunicacao/social-media/post-para-instagram-2LAOS270">Post para Instagram</a>
            <a href="https://freelancer.com.br/publicar-projeto">Publicar</a>
            </body></html>
            """;
        List<Projeto> projetos = parser().parsearProjetos(html);
        assertEquals(2, projetos.size());
        Projeto p1 = projetos.get(0);
        assertEquals(PlataformaFreela.FREELANCER_BR, p1.getPlataforma());
        assertEquals("Desenvolvimento de Apps", p1.getTitulo());
        assertTrue(p1.getUrlProjeto().endsWith("-3BOPS371"));
        assertTrue(p1.getIdExterno() > 0);
    }

    @Test
    void deduplicaMesmoProjetoComLinksRepetidos() {
        String html = """
            <a href="/projetos/cat/sub/projeto-x-9RWNS468"></a>
            <a href="/projetos/cat/sub/projeto-x-9RWNS468">Projeto X</a>
            """;
        List<Projeto> projetos = parser().parsearProjetos(html);
        assertEquals(1, projetos.size());
        assertEquals("Projeto X", projetos.get(0).getTitulo());
    }

    @Test
    void ignoraLinksQueNaoSaoProjetos() {
        String html = """
            <a href="/projetos">Projetos</a>
            <a href="/publicar-projeto">Publicar</a>
            <a href="/">Home</a>
            """;
        assertTrue(parser().parsearProjetos(html).isEmpty());
    }

    @Test
    void idNumericoEstavelEhDeterministicoEPositivo() {
        long a = FreelancerBrProjetoParser.idNumericoEstavel("3BOPS371");
        long b = FreelancerBrProjetoParser.idNumericoEstavel("3BOPS371");
        long c = FreelancerBrProjetoParser.idNumericoEstavel("2LAOS270");
        assertEquals(a, b);
        assertTrue(a > 0);
        assertFalse(a == c);
    }

    @Test
    void htmlVazioRetornaListaVazia() {
        assertTrue(parser().parsearProjetos("").isEmpty());
        assertTrue(parser().parsearProjetos(null).isEmpty());
    }

    @Test
    void parsearProjetosJsonMapeiaCamposDaApi() throws Exception {
        String json = """
            {"status":"success","result":{"projects":[
              {"id":40675238,"title":"Procura-se SOCIAL MEDIA","description":"Preciso de um social media.",
               "seo_url":"digital-marketing/Procura-SOCIAL-MEDIA","time_submitted":1787861123,
               "budget":{"minimum":30.0,"maximum":70.0}}
            ]}}
            """;
        List<Projeto> projetos = parser().parsearProjetosJson(new ObjectMapper().readTree(json));
        assertEquals(1, projetos.size());
        Projeto p = projetos.get(0);
        assertEquals(PlataformaFreela.FREELANCER_BR, p.getPlataforma());
        assertEquals(40675238L, p.getIdExterno());
        assertEquals("Procura-se SOCIAL MEDIA", p.getTitulo());
        // Link deve apontar para o dominio .com.br via seo_url.
        assertTrue(p.getUrlProjeto().equals(
            "https://www.freelancer.com.br/projects/digital-marketing/Procura-SOCIAL-MEDIA"));
        assertEquals(new BigDecimal("70.0"), p.getOrcamento());
    }

    @Test
    void parsearProjetosJsonSemSeoUrlUsaIdNumerico() throws Exception {
        String json = """
            {"status":"success","result":{"projects":[
              {"id":123456,"title":"Sem SEO","budget":{"minimum":10.0}}
            ]}}
            """;
        List<Projeto> projetos = parser().parsearProjetosJson(new ObjectMapper().readTree(json));
        assertEquals(1, projetos.size());
        assertTrue(projetos.get(0).getUrlProjeto()
            .equals("https://www.freelancer.com.br/projects/123456"));
        assertEquals(new BigDecimal("10.0"), projetos.get(0).getOrcamento());
    }

    @Test
    void parsearProjetosJsonNuloOuVazioRetornaListaVazia() throws Exception {
        assertTrue(parser().parsearProjetosJson(null).isEmpty());
        assertTrue(parser().parsearProjetosJson(
            new ObjectMapper().readTree("{\"result\":{\"projects\":[]}}")).isEmpty());
    }

    @Test
    void parsearDetalhesJsonMapeiaProjetoUnico() throws Exception {
        String json = """
            {"status":"success","result":{"id":40662792,"title":"Consultor",
             "description":"Descricao completa do projeto.",
             "seo_url":"supplier-sourcing/Consultor","budget":{"minimum":250.0,"maximum":750.0}}}
            """;
        Projeto p = parser().parsearDetalhesJson(new ObjectMapper().readTree(json), 40662792L);
        assertTrue(p != null);
        assertEquals(40662792L, p.getIdExterno());
        assertEquals("Consultor", p.getTitulo());
        assertTrue(p.getDescricao().contains("Descricao completa"));
        assertTrue(p.getUrlProjeto().endsWith("supplier-sourcing/Consultor"));
    }

    @Test
    void parsearDetalhesJsonNuloOuSemProjetoRetornaNull() throws Exception {
        assertTrue(parser().parsearDetalhesJson(null, 1L) == null);
        assertTrue(parser().parsearDetalhesJson(
            new ObjectMapper().readTree("{\"result\":{}}"), 1L) == null);
    }
}
