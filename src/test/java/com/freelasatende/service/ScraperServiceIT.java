package com.freelasatende.service;

import com.freelasatende.model.dto.ConexoesDisponiveis;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.entity.Cliente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import com.freelasatende.service.interfaceServico.ScraperService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "scheduling.enabled=false")
public class ScraperServiceIT {

    @Autowired
    private ScraperService scraperService;

    @Autowired
    private AutenticacaoService autenticacaoService;

    @BeforeEach
    public void setup() {
        // Inicializa o AutenticacaoService para carregar os cookies de data/cookies.json
        autenticacaoService.carregarCookies();
    }

    @Test
    public void testVerificarLoginComCookiesSalvos() {
        boolean logado = scraperService.verificarLogin();
        assertTrue(logado, "O scraper deveria reconhecer que está logado usando os cookies salvos");
    }

    @Test
    public void testListarProjetosNovos() {
        List<Projeto> projetos = scraperService.buscarProjetosNovos();
        assertNotNull(projetos);
        assertFalse(projetos.isEmpty(), "Deveria encontrar pelo menos um projeto na página");
        
        System.out.println("Encontrados " + projetos.size() + " projetos.");
        Projeto primeiro = projetos.get(0);
        System.out.println("Primeiro projeto: " + primeiro.getTitulo() + " (ID: " + primeiro.getId() + ")");
        assertNotNull(primeiro.getId());
        assertNotNull(primeiro.getTitulo());
    }

    @Test
    public void testBuscarDetalhesProjeto() {
        Projeto base = obterPrimeiroProjeto();
        String slug = extrairSlug(base.getUrlProjeto(), base.getId());

        Projeto detalhes = scraperService.buscarDetalhesProjeto(base.getId(), slug);

        assertNotNull(detalhes, "Detalhes do projeto nao deveriam ser nulos");
        assertEquals(base.getId(), detalhes.getId());
        assertNotNull(detalhes.getTitulo(), "Titulo detalhado nao deveria ser nulo");
    }

    @Test
    public void testBuscarRankingClientePorProjeto() {
        Projeto base = obterPrimeiroProjeto();

        Cliente cliente = scraperService.buscarCliente(base.getId());

        assertNotNull(cliente, "Cliente nao deveria ser nulo");
        assertNotNull(cliente.getMediaNota(), "Media de nota deveria vir da API");
        assertTrue(cliente.getMediaNota() >= 0.0, "Media de nota deveria ser >= 0");
    }

    @Test
    public void testVerificarConexoesDisponiveis() {
        ConexoesDisponiveis conexoes = scraperService.obterConexoesDisponiveis();
        assertNotNull(conexoes);
        System.out.println("Conexões totais: " + conexoes.getTotal());
        System.out.println("Conexões renováveis: " + conexoes.getRenovaveis());
        assertTrue(conexoes.getTotal() > 0, "Deveria haver conexões disponíveis");
    }

    @Test
    public void testListarConversasInbox() {
        List<ConversaResumo> conversas = scraperService.listarConversasInbox();
        assertNotNull(conversas);
        System.out.println("Conversas encontradas: " + conversas.size());
        if (!conversas.isEmpty()) {
            ConversaResumo conversa = conversas.get(0);
            System.out.println("Primeira conversa com cliente ID " + conversa.getIdCliente() + " - " + conversa.getNomeCliente());
            assertNotNull(conversa.getIdConversa());
        }
    }

    private Projeto obterPrimeiroProjeto() {
        List<Projeto> projetos = scraperService.buscarProjetosNovos();
        assertNotNull(projetos);
        assertFalse(projetos.isEmpty(), "Precisa haver ao menos um projeto para validar o fluxo");
        return projetos.get(0);
    }

    private String extrairSlug(String urlProjeto, Long projetoId) {
        assertNotNull(urlProjeto, "URL do projeto nao deveria ser nula");
        int idx = urlProjeto.indexOf("/project/");
        assertTrue(idx >= 0, "URL do projeto deveria conter /project/");
        String trecho = urlProjeto.substring(idx + "/project/".length());
        String sufixo = "-" + projetoId;
        int fim = trecho.indexOf(sufixo);
        assertTrue(fim > 0, "Nao foi possivel extrair slug da URL");
        return trecho.substring(0, fim);
    }
}
