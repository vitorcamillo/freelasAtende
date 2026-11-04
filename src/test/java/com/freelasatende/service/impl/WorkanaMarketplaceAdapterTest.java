package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.WorkanaConfig;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.model.enums.PlataformaFreela;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkanaMarketplaceAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parserRetornaTodosProjetosComSlugValido() throws Exception {
        WorkanaConfig config = new WorkanaConfig();
        config.setMaxProjetos(10);
        WorkanaProjectParser parser = new WorkanaProjectParser(config);

        List<Projeto> projetos = parser.parseProjetos(objectMapper.readTree("""
            {
              "results": {
                "results": [
                  {
                    "slug": "especialista-em-midias-sociais",
                    "title": "<a>Especialista em Midias Sociais</a>",
                    "description": "Criacao de conteudo visual e video para Instagram.",
                    "shortDescription": "Marketing e video",
                    "authorName": "Cliente Marketing",
                    "budget": "R$ 2.500 - 5.000",
                    "totalBids": "Propostas: 17",
                    "skills": [
                      {"anchorText": "Marketing Online"},
                      {"anchorText": "Edicao de video"}
                    ]
                  },
                  {
                    "slug": "desenvolvimento-de-robo-para-coleta-de-dados",
                    "title": "<a>Desenvolvimento de robo para coleta de dados</a>",
                    "description": "Preciso de web scraping em marketplaces com Python e SQL.",
                    "shortDescription": "Robo Python",
                    "authorName": "Cliente Tech",
                    "budget": "R$ 1.000 - 3.000",
                    "totalBids": "Propostas: 5",
                    "skills": [
                      {"anchorText": "Python"},
                      {"anchorText": "Web Scraping"},
                      {"anchorText": "SQL"}
                    ]
                  },
                  {
                    "slug": "copywriting-para-landing-page",
                    "title": "<a>Copywriting para landing page</a>",
                    "description": "Texto persuasivo e conteudo para pagina de venda.",
                    "shortDescription": "Copy para landing page",
                    "authorName": "Cliente Copy",
                    "budget": "R$ 500 - 1.000",
                    "totalBids": "Propostas: 8",
                    "skills": [
                      {"anchorText": "Copywriting"},
                      {"anchorText": "Landing Page"}
                    ]
                  },
                  {
                    "slug": "api-para-wordpress",
                    "title": "<a>API para WordPress</a>",
                    "description": "Integrar API REST com plugin WordPress e banco MySQL.",
                    "shortDescription": "API WordPress",
                    "authorName": "Cliente API",
                    "budget": "R$ 1.500 - 2.500",
                    "totalBids": "Propostas: 3",
                    "skills": [
                      {"anchorText": "API"},
                      {"anchorText": "WordPress"},
                      {"anchorText": "MySQL"}
                    ]
                  }
                ]
              }
            }
            """));

        assertEquals(4, projetos.size());
        assertTrue(projetos.stream().allMatch(p -> p.getPlataforma() == PlataformaFreela.WORKANA));

        Projeto robo = projetos.stream()
            .filter(p -> p.getTitulo().equals("Desenvolvimento de robo para coleta de dados"))
            .findFirst().orElseThrow();
        assertEquals("Cliente Tech", robo.getNomeCliente());
        assertEquals("Programacao", robo.getCategoria());
        assertEquals(5, robo.getNumeroPropostas());
        assertEquals("1000", robo.getValorMinimo().toPlainString());
        assertEquals("3000", robo.getOrcamento().toPlainString());
        assertTrue(robo.getDescricao().contains("Skills Workana: Python, Web Scraping, SQL"));
        assertTrue(robo.getUrlProjeto().endsWith("/job/desenvolvimento-de-robo-para-coleta-de-dados"));
    }

    @Test
    void parserRetornaTodosProjetosSemFiltroDeEscopo() throws Exception {
        WorkanaConfig config = new WorkanaConfig();
        config.setMaxProjetos(20);
        WorkanaProjectParser parser = new WorkanaProjectParser(config);

        List<Projeto> projetos = parser.parseProjetos(objectMapper.readTree("""
            {
              "results": {
                "results": [
                  {
                    "slug": "desenvolvimento-de-projeto-arquitetonico",
                    "title": "Desenvolvimento de Projeto Arquitetônico",
                    "description": "Apoio em Arquitetura, Urbanismo, AutoCAD, Archicad e Rhino.",
                    "budget": "USD 50 - 100",
                    "totalBids": "Propostas: 0",
                    "skills": [{"anchorText": "Design de Interiores"}]
                  },
                  {
                    "slug": "servicos-de-atendimento-remoto",
                    "title": "Serviços de Atendimento e Organização Remota via Celular",
                    "description": "Assistente virtual para atendimento ao cliente, data entry e agenda.",
                    "budget": "USD 15 - 45 / hora",
                    "totalBids": "Propostas: 1",
                    "skills": [{"anchorText": "Assistente de administração"}]
                  },
                  {
                    "slug": "desenvolvimento-de-software-desktop-python",
                    "title": "Desenvolvimento de Software Desktop Python",
                    "description": "Converter planilha em software desktop com Python, banco de dados e testes.",
                    "budget": "USD 100 - 250",
                    "totalBids": "Propostas: 23",
                    "skills": [{"anchorText": "Python"}, {"anchorText": "Database"}, {"anchorText": "Git"}]
                  },
                  {
                    "slug": "engenharia-de-software-java",
                    "title": "Engenharia de Software Java",
                    "description": "Projeto backend com Java, Spring Boot, API REST e arquitetura de software.",
                    "budget": "USD 100 - 250",
                    "totalBids": "Propostas: 8",
                    "skills": [{"anchorText": "Java"}, {"anchorText": "Spring Boot"}, {"anchorText": "REST API"}]
                  }
                ]
              }
            }
            """));

        assertEquals(4, projetos.size());
        assertTrue(projetos.stream().allMatch(p -> p.getPlataforma() == PlataformaFreela.WORKANA));
        assertTrue(projetos.stream().anyMatch(p -> p.getTitulo().equals("Desenvolvimento de Projeto Arquitetônico")));
        assertTrue(projetos.stream().anyMatch(p -> p.getTitulo().equals("Desenvolvimento de Software Desktop Python")));
    }

    @Test
    void adapterWorkanaBuscaDiscoveryPerguntasInboxEEnviaProposta() throws Exception {
        WorkanaClient client = mock(WorkanaClient.class);
        WorkanaConfig config = new WorkanaConfig();
        WorkanaProjectParser parser = new WorkanaProjectParser(config);
        WorkanaProposalClient proposalClient = mock(WorkanaProposalClient.class);
        WorkanaMarketplaceAdapter adapter = new WorkanaMarketplaceAdapter(client, parser, proposalClient);

        when(client.buscarProjetosJsonParaDiscovery()).thenReturn(objectMapper.readTree("""
            {
              "results": {
                "results": [
                  {
                    "slug": "ajustes-em-bot-python",
                    "title": "<a>Ajustes em bot Python</a>",
                    "description": "Ajustar API, automacao e testes.",
                    "shortDescription": "Bot Python",
                    "authorName": "Cliente",
                    "budget": "R$ 500 - 1.500",
                    "totalBids": "Propostas: 2",
                    "skills": [{"anchorText": "Python"}, {"anchorText": "API"}]
                  }
                ]
              }
            }
            """));

        List<Projeto> projetos = adapter.buscarProjetosNovos();

        assertEquals(PlataformaFreela.WORKANA, adapter.plataforma());
        assertTrue(adapter.capabilities().discovery());
        assertTrue(adapter.capabilities().inbox());
        assertTrue(adapter.capabilities().perguntas());
        assertTrue(adapter.capabilities().propostas());
        assertFalse(adapter.capabilities().conexoes());
        assertEquals(1, projetos.size());
        assertEquals(PlataformaFreela.WORKANA, projetos.get(0).getPlataforma());

        when(client.enviarPergunta(eq("ajustes-em-bot-python"), eq("texto"))).thenReturn(true);
        assertTrue(adapter.enviarPergunta(projetos.get(0), "texto"));
        verify(client).enviarPergunta("ajustes-em-bot-python", "texto");
        when(client.listarConversasInbox()).thenReturn(List.of());
        assertEquals(List.of(), adapter.listarConversasInbox());
        verify(client).listarConversasInbox();
        adapter.listarMensagensConversa(10L);
        verify(client).listarMensagensConversa(10L);
        adapter.enviarMensagemConversa(10L, "texto");
        verify(client).enviarMensagemConversa(10L, "texto");
        assertUnsupported(() -> adapter.enviarPergunta(projetos.get(0).getId(), "texto"));
        Proposta proposta = Proposta.builder().mensagem("Proposta").build();
        assertEquals("ENVIADA", adapter.enviarProposta(projetos.get(0), proposta).getStatus());
        verify(proposalClient).enviar(projetos.get(0), proposta);
        assertUnsupported(() -> adapter.cancelarProposta(projetos.get(0).getId()));
    }

    @Test
    void abordagemInicialCurtaEhExpandidaParaMinimoAceitoPelaWorkana() {
        WorkanaClient client = mock(WorkanaClient.class);
        WorkanaMarketplaceAdapter adapter = new WorkanaMarketplaceAdapter(
            client,
            new WorkanaProjectParser(new WorkanaConfig()),
            null
        );
        Projeto projeto = Projeto.builder()
            .id(PlataformaFreela.WORKANA.idInterno(10L))
            .urlProjeto("https://www.workana.com/job/projeto-workana")
            .build();
        when(client.enviarPergunta(eq("projeto-workana"), argThat(texto -> texto.length() <= 100)))
            .thenReturn(true);

        assertTrue(adapter.enviarPergunta(projeto, "Ja foi atendido?"));

        verify(client).enviarPergunta(eq("projeto-workana"), argThat(texto ->
            texto.length() <= 100 && texto.contains("ainda esta em aberto")
                && texto.contains("posso lhe ajudar")
        ));
    }

    @Test
    void registryPodeAtivarWorkanaPorConfiguracao() {
        WorkanaClient client = mock(WorkanaClient.class);
        WorkanaMarketplaceAdapter adapter = new WorkanaMarketplaceAdapter(
            client,
            new WorkanaProjectParser(new WorkanaConfig()),
            null
        );
        MarketplaceAdapterRegistry registry = new MarketplaceAdapterRegistry(List.of(adapter), "WORKANA");

        assertEquals(List.of(PlataformaFreela.WORKANA), registry.ativos().stream()
            .map(item -> item.plataforma())
            .toList());
        assertEquals(PlataformaFreela.WORKANA, registry.para(PlataformaFreela.WORKANA).plataforma());
    }

    @Test
    void verificarLoginUsaDiscoveryComoHealthCheck() {
        WorkanaClient client = mock(WorkanaClient.class);
        WorkanaMarketplaceAdapter adapter = new WorkanaMarketplaceAdapter(
            client,
            new WorkanaProjectParser(new WorkanaConfig()),
            null
        );
        when(client.verificarLoginEPrepararDiscovery()).thenThrow(new MarketplaceAdapterException(
            PlataformaFreela.WORKANA,
            MarketplaceAdapterErrorType.FALHA_REDE,
            "indisponivel"
        ));

        assertFalse(adapter.verificarLogin());
    }

    private void assertUnsupported(UnsupportedCall call) {
        MarketplaceAdapterException ex = assertThrows(MarketplaceAdapterException.class, call::execute);

        assertEquals(PlataformaFreela.WORKANA, ex.getPlataforma());
        assertEquals(MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL, ex.getType());
    }

    @FunctionalInterface
    private interface UnsupportedCall {
        void execute();
    }
}
