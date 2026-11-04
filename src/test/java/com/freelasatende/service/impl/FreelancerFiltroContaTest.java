package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.FreelancerConfig;
import com.freelasatende.model.entity.Projeto;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Filtro do discovery do Freelancer.com: descarta antes da IA os projetos em
 * que a API de bids recusaria o lance da conta. Valores tirados de projetos
 * reais recusados em producao em 27/09/2026.
 */
class FreelancerFiltroContaTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final AtomicReference<String> discoveryQuery = new AtomicReference<>();
    private final AtomicInteger leiturasPerfil = new AtomicInteger();
    private volatile String respostaPerfil =
        "{\"status\":\"success\",\"result\":{\"id\":1,\"jobs\":[{\"id\":3},{\"id\":7},{\"id\":9}]}}";
    private volatile int statusBid = 200;
    private volatile String respostaBid = "{\"status\":\"success\"}";

    @BeforeEach
    void subirServidor() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/projects/0.1/projects/active/", exchange -> {
            discoveryQuery.set(exchange.getRequestURI().getQuery());
            responder(exchange, 200, """
                {"status":"success","result":{"projects":[
                  {"id":1,"title":"Grande USD","status":"active","type":"fixed",
                   "budget":{"minimum":1000.0,"maximum":2500.0},
                   "currency":{"code":"USD","exchange_rate":1.0},"jobs":[{"id":3}]},
                  {"id":2,"title":"Grande em GBP","status":"active","type":"fixed",
                   "budget":{"minimum":1500.0,"maximum":2000.0},
                   "currency":{"code":"GBP","exchange_rate":1.32505},"jobs":[{"id":3}]},
                  {"id":3,"title":"Sem skill em comum","status":"active","type":"fixed",
                   "budget":{"minimum":12500.0,"maximum":37500.0},
                   "currency":{"code":"INR","exchange_rate":0.010436},"jobs":[{"id":17},{"id":20}]},
                  {"id":4,"title":"Escrow","status":"active","type":"fixed","is_escrow_project":true,
                   "budget":{"minimum":30.0,"maximum":250.0},
                   "currency":{"code":"USD","exchange_rate":1.0},"jobs":[{"id":3}]},
                  {"id":5,"title":"Elegivel","status":"active","type":"fixed",
                   "budget":{"minimum":250.0,"maximum":750.0},
                   "currency":{"code":"USD","exchange_rate":1.0},"jobs":[{"id":3},{"id":17}]},
                  {"id":6,"title":"Grande em INR abaixo do limite","status":"active","type":"fixed",
                   "budget":{"minimum":75000.0,"maximum":150000.0},
                   "currency":{"code":"INR","exchange_rate":0.010436},"jobs":[]}
                ]}}
                """);
        });
        server.createContext("/api/users/0.1/self/", exchange -> {
            leiturasPerfil.incrementAndGet();
            responder(exchange, 200, respostaPerfil);
        });
        server.createContext("/api/projects/0.1/bids/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            responder(exchange, statusBid, respostaBid);
        });
        server.start();
    }

    @AfterEach
    void pararServidor() {
        server.stop(0);
    }

    private static void responder(com.sun.net.httpserver.HttpExchange exchange, int status, String corpo)
            throws java.io.IOException {
        byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private FreelancerClient criarClient(FreelancerConfig config) throws Exception {
        Path cookies = tempDir.resolve("freelancer-cookies.json");
        Files.writeString(cookies, "{}");
        config.setUrlBase("http://127.0.0.1:" + server.getAddress().getPort());
        config.setCookiesArquivo(cookies.toString());
        config.setDiscoveryQuery("user_role=freelancer&per_page=20");
        config.setOauthToken("token-teste");
        config.setBidderId(94462910L);
        return new FreelancerClient(config, new ObjectMapper());
    }

    private static List<String> titulos(List<Projeto> projetos) {
        return projetos.stream().map(Projeto::getTitulo).toList();
    }

    @Test
    @DisplayName("Conta nao verificada: descarta US$ 2.500+, escrow e projeto sem skill em comum")
    void descartaProjetosQueAContaNaoConsegueDisputar() throws Exception {
        FreelancerConfig config = new FreelancerConfig();
        FreelancerClient client = criarClient(config);

        List<Projeto> projetos = new FreelancerMarketplaceAdapter(config, client).buscarProjetosNovos();

        assertEquals(List.of("Elegivel", "Grande em INR abaixo do limite"), titulos(projetos));
        assertTrue(discoveryQuery.get().contains("job_details=true"));
    }

    @Test
    @DisplayName("Conta verificada e com escrow libera os projetos grandes e o de escrow")
    void contaVerificadaComEscrowLiberaProjetos() throws Exception {
        FreelancerConfig config = new FreelancerConfig();
        config.setVerified(true);
        config.setEscrowVinculado(true);
        FreelancerClient client = criarClient(config);

        List<Projeto> projetos = new FreelancerMarketplaceAdapter(config, client).buscarProjetosNovos();

        assertEquals(List.of("Grande USD", "Grande em GBP", "Escrow", "Elegivel", "Grande em INR abaixo do limite"),
            titulos(projetos));
    }

    @Test
    @DisplayName("Sem conseguir ler as skills do perfil, nao filtra por skill")
    void semPerfilNaoFiltraPorSkill() throws Exception {
        respostaPerfil = "{\"status\":\"success\",\"result\":{\"id\":1,\"jobs\":[]}}";
        FreelancerConfig config = new FreelancerConfig();
        config.setVerified(true);
        config.setEscrowVinculado(true);
        FreelancerClient client = criarClient(config);

        List<Projeto> projetos = new FreelancerMarketplaceAdapter(config, client).buscarProjetosNovos();

        assertTrue(titulos(projetos).contains("Sem skill em comum"));
    }

    @Test
    @DisplayName("Skills do perfil ficam em cache entre ciclos")
    void skillsDoPerfilFicamEmCache() throws Exception {
        FreelancerConfig config = new FreelancerConfig();
        FreelancerClient client = criarClient(config);
        FreelancerMarketplaceAdapter adapter = new FreelancerMarketplaceAdapter(config, client);

        adapter.buscarProjetosNovos();
        adapter.buscarProjetosNovos();

        assertEquals(1, leiturasPerfil.get());
    }

    @Test
    @DisplayName("Sem lances na conta, pausa o discovery do Freelancer.com")
    void semLancesPausaDiscovery() throws Exception {
        statusBid = 400;
        respostaBid = "{\"status\":\"error\",\"message\":\"You have used all of your bids.\","
            + "\"error_code\":\"ProjectExceptionCodes.BID_LIMIT_EXCEEDED\"}";
        FreelancerConfig config = new FreelancerConfig();
        FreelancerClient client = criarClient(config);
        FreelancerMarketplaceAdapter adapter = new FreelancerMarketplaceAdapter(config, client);
        assertFalse(client.lancesBloqueados());

        assertThrows(MarketplaceAdapterException.class,
            () -> client.enviarPergunta(5L, "Ola", new java.math.BigDecimal("250")));

        assertTrue(client.lancesBloqueados());
        discoveryQuery.set(null);
        assertTrue(adapter.buscarProjetosNovos().isEmpty());
        assertEquals(null, discoveryQuery.get());
    }

    @Test
    @DisplayName("Recusa de lance por outro motivo nao pausa o discovery")
    void outraRecusaNaoPausaDiscovery() throws Exception {
        statusBid = 403;
        respostaBid = "{\"status\":\"error\",\"message\":\"You must have the required skills to bid on this project.\"}";
        FreelancerConfig config = new FreelancerConfig();
        FreelancerClient client = criarClient(config);

        assertThrows(MarketplaceAdapterException.class,
            () -> client.enviarPergunta(5L, "Ola", new java.math.BigDecimal("250")));

        assertFalse(client.lancesBloqueados());
    }
}

