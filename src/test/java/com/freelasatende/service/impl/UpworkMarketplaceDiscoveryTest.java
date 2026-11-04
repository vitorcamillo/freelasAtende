package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.UpworkConfig;
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

class UpworkMarketplaceDiscoveryTest {

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
    void buscaBestMatchesComCookiesEMapeiaCardsSsr() throws Exception {
        AtomicReference<String> caminhoRecebido = new AtomicReference<>();
        AtomicReference<String> cookieRecebido = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/nx/find-work/best-matches", exchange -> {
            caminhoRecebido.set(exchange.getRequestURI().toString());
            cookieRecebido.set(exchange.getRequestHeaders().getFirst("Cookie"));
            byte[] body = ("""
                <!doctype html><html><body>
                  <div data-test="job-tile-list">
                    <section data-ev-opening_uid="~01abc123" data-umq-ssr="1">
                      <a href="/jobs/~01abc123/?source=best_matches"><h2>Java Spring API</h2></a>
                      <span data-test="job-type">Fixed-price</span>
                      <span data-test="formatted-amount">$500.00</span>
                      <div data-test="job-description-text">Construir integracao robusta</div>
                    </section>
                  </div>
                </body></html>
                """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        Path cookies = tempDir.resolve("upwork-cookies.json");
        Files.writeString(cookies, "{\"master_access_token\":\"sessao-teste\"}");

        UpworkConfig config = new UpworkConfig();
        config.setUrlBase("http://127.0.0.1:" + server.getAddress().getPort());
        config.setCookiesArquivo(cookies.toString());

        UpworkClient client = new UpworkClient(config, new ObjectMapper());
        UpworkMarketplaceAdapter adapter = new UpworkMarketplaceAdapter(config, client, null);

        List<Projeto> projetos = adapter.buscarProjetosNovos();

        assertEquals(1, projetos.size());
        assertEquals("Java Spring API", projetos.get(0).getTitulo());
        assertEquals("Construir integracao robusta", projetos.get(0).getDescricao());
        assertEquals("500.00", projetos.get(0).getOrcamento().toPlainString());
        assertEquals("http://127.0.0.1:" + server.getAddress().getPort()
            + "/jobs/~01abc123/?source=best_matches", projetos.get(0).getUrlProjeto());
        assertEquals("/nx/find-work/best-matches", caminhoRecebido.get());
        assertTrue(cookieRecebido.get().contains("master_access_token=sessao-teste"));
    }

    @Test
    void extraiTodasAsVagasDoEstadoNuxtSemExecutarJavascript() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/nx/find-work/best-matches", exchange -> {
            byte[] body = ("""
                <!doctype html><html><body>
                  <div data-test="job-tile-list">
                    <section data-ev-opening_uid="~01abc123" data-umq-ssr="1">
                      <a href="/jobs/~01abc123/"><h2>Java Spring API</h2></a>
                      <div data-test="job-description-text">Card SSR</div>
                    </section>
                  </div>
                  <script>window.__NUXT__=(function(a,b){return {
                    feedBestMatch:{jobs:[
                      {uid:"1",title:"Java Spring API",ciphertext:"~01abc123",description:"Card completo",type:a},
                      {uid:"2",title:"Python Backend",ciphertext:"~02def456",description:"API e automacao",type:b}
                    ]}
                  }}("fixed","hourly"))</script>
                </body></html>
                """).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        Path cookies = tempDir.resolve("upwork-cookies.json");
        Files.writeString(cookies, "{\"master_access_token\":\"sessao-teste\"}");
        UpworkConfig config = new UpworkConfig();
        config.setUrlBase("http://127.0.0.1:" + server.getAddress().getPort());
        config.setCookiesArquivo(cookies.toString());
        UpworkMarketplaceAdapter adapter = new UpworkMarketplaceAdapter(
            config,
            new UpworkClient(config, new ObjectMapper()),
            null
        );

        List<Projeto> projetos = adapter.buscarProjetosNovos();

        assertEquals(2, projetos.size());
        assertEquals(List.of("Java Spring API", "Python Backend"),
            projetos.stream().map(Projeto::getTitulo).toList());
        assertEquals("Card SSR", projetos.get(0).getDescricao());
    }
}
