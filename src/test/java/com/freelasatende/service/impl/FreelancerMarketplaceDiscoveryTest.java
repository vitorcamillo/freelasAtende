package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.FreelancerConfig;
import com.freelasatende.model.entity.Projeto;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreelancerMarketplaceDiscoveryTest {

    @TempDir
    Path tempDir;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void buscaProjetosAtivosComCookiesEMapeiaRespostaAninhada() throws Exception {
        AtomicReference<String> caminhoRecebido = new AtomicReference<>();
        AtomicReference<String> cookieRecebido = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/projects/0.1/projects/active/", exchange -> {
            caminhoRecebido.set(exchange.getRequestURI().toString());
            cookieRecebido.set(exchange.getRequestHeaders().getFirst("Cookie"));
            byte[] body = ("""
                {"status":"success","result":{"projects":[{
                  "id":40670382,
                  "title":"API Spring Boot",
                  "description":"Integrar APIs Java",
                  "budget":{"minimum":30.0,"maximum":250.0},
                  "time_submitted":1787670742,
                  "seo_url":"java/API-Spring-Boot"
                },{
                  "id":40670539,
                  "title":"Projeto exclusivo",
                  "description":"Somente preferenciais",
                  "status":"active",
                  "type":"fixed",
                  "budget":{"minimum":100.0,"maximum":500.0},
                  "upgrades":{"pf_only":true}
                }]}}
                """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        Path cookies = tempDir.resolve("freelancer-cookies.json");
        Files.writeString(cookies, "{\"freelancer_live_session\":\"sessao-teste\"}");

        FreelancerConfig config = new FreelancerConfig();
        config.setUrlBase("http://127.0.0.1:" + server.getAddress().getPort());
        config.setCookiesArquivo(cookies.toString());
        config.setDiscoveryQuery("query=java+spring+boot&limit=20");

        FreelancerClient client = new FreelancerClient(config, new ObjectMapper());
        FreelancerMarketplaceAdapter adapter = new FreelancerMarketplaceAdapter(config, client);

        List<Projeto> projetos = adapter.buscarProjetosNovos();

        assertEquals(1, projetos.size());
        assertEquals("API Spring Boot", projetos.get(0).getTitulo());
        assertEquals("Integrar APIs Java", projetos.get(0).getDescricao());
        assertEquals("250.0", projetos.get(0).getOrcamento().toPlainString());
        assertEquals("http://127.0.0.1:" + server.getAddress().getPort()
            + "/projects/java/API-Spring-Boot", projetos.get(0).getUrlProjeto());
        assertTrue(caminhoRecebido.get().startsWith(
            "/api/projects/0.1/projects/active/?query=java+spring+boot&limit=20"));
        assertTrue(cookieRecebido.get().contains("freelancer_live_session=sessao-teste"));
    }
}
