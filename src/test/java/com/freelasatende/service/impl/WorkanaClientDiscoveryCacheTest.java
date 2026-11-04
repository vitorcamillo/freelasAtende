package com.freelasatende.service.impl;

import com.freelasatende.config.WorkanaConfig;
import com.freelasatende.model.entity.Projeto;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.freelasatende.service.impl.WorkanaClientTestSupport.client;
import static com.freelasatende.service.impl.WorkanaClientTestSupport.configFor;
import static com.freelasatende.service.impl.WorkanaClientTestSupport.responder;
import static com.freelasatende.service.impl.WorkanaClientTestSupport.responderJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Valida o snapshot de uso único entre verificação de login e discovery. */
class WorkanaClientDiscoveryCacheTest {

    private static final String LISTAGEM = """
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
        """;

    @TempDir
    Path tempDir;

    private HttpServer server;

    @AfterEach
    void pararServidor() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void cadaVerificacaoFazRequestEDiscoveryConsomeSnapshotUmaVez() throws Exception {
        AtomicInteger requisicoes = iniciarServidor((numero, exchange) -> responderJson(exchange, 200, LISTAGEM));
        WorkanaMarketplaceAdapter adapter = adapter(configComCookies());

        assertTrue(adapter.verificarLogin());
        List<Projeto> primeiroCiclo = adapter.buscarProjetosNovos();
        assertEquals(1, requisicoes.get(), "login e discovery do ciclo compartilham uma listagem");
        assertEquals(1, primeiroCiclo.size());
        assertEquals("Ajustes em bot Python", primeiroCiclo.get(0).getTitulo());

        assertTrue(adapter.verificarLogin());
        adapter.buscarProjetosNovos();
        assertEquals(2, requisicoes.get(), "uma nova verificação sempre consulta uma listagem fresca");

        adapter.buscarProjetosNovos();
        assertEquals(3, requisicoes.get(), "snapshot já consumido exige discovery fresco");
    }

    @Test
    void discoveryDiretoSempreConsultaSemArmarSnapshot() throws Exception {
        AtomicInteger requisicoes = iniciarServidor((numero, exchange) -> responderJson(exchange, 200, LISTAGEM));
        WorkanaClient client = client(configComCookies());

        client.buscarProjetosJson();
        client.buscarProjetosJson();

        assertEquals(2, requisicoes.get(), "discovery direto não guarda resultado para outra chamada");
    }

    @Test
    void trocaDeSessaoEntreVerificacaoEDiscoveryDescartaSnapshot() throws Exception {
        AtomicInteger requisicoes = iniciarServidor((numero, exchange) -> responderJson(exchange, 200, LISTAGEM));
        Path cookies = tempDir.resolve("workana-cookies.json");
        Files.writeString(cookies, "{\"_workana_session\": \"sessao-a\"}");
        WorkanaConfig config = configFor(server, cookies);
        WorkanaMarketplaceAdapter adapter = adapter(config);

        assertTrue(adapter.verificarLogin());
        Files.writeString(cookies, "{\"_workana_session\": \"sessao-b\"}");
        adapter.buscarProjetosNovos();

        assertEquals(2, requisicoes.get(), "snapshot vinculado a outra sessão não pode ser consumido");
    }

    @Test
    void mudancaDeConfiguracaoInvalidaSnapshot() throws Exception {
        AtomicInteger requisicoes = iniciarServidor((numero, exchange) -> responderJson(exchange, 200, LISTAGEM));
        WorkanaConfig config = configComCookies();
        WorkanaMarketplaceAdapter adapter = adapter(config);

        assertTrue(adapter.verificarLogin());
        config.setDiscoveryQuery("category=design&language=pt");
        adapter.buscarProjetosNovos();

        assertEquals(2, requisicoes.get(), "query alterada invalida o snapshot anterior");
    }

    @Test
    void ttlEhMonotonicoEExpiracaoForcaNovaListagem() throws Exception {
        AtomicInteger requisicoes = iniciarServidor((numero, exchange) -> responderJson(exchange, 200, LISTAGEM));
        WorkanaConfig config = configComCookies();
        WorkanaClient client = client(config);
        WorkanaMarketplaceAdapter adapter = adapter(client, config);
        AtomicLong nanos = new AtomicLong(100L);
        client.configurarRelogioDiscovery(nanos::get);

        assertTrue(adapter.verificarLogin());
        nanos.addAndGet(10_000_000_000L);
        adapter.buscarProjetosNovos();

        assertEquals(2, requisicoes.get(), "snapshot expirado não pode ser reutilizado");
    }

    @Test
    void falhaAposSucessoLimpaSnapshotAntigo() throws Exception {
        AtomicInteger requisicoes = iniciarServidor((numero, exchange) -> {
            if (numero == 2) {
                responder(exchange, 500, "erro interno");
            } else {
                responderJson(exchange, 200, LISTAGEM);
            }
        });
        WorkanaConfig config = configComCookies();
        WorkanaClient client = client(config);
        WorkanaMarketplaceAdapter adapter = adapter(client, config);

        assertTrue(adapter.verificarLogin());
        assertThrows(MarketplaceAdapterException.class, client::buscarProjetosJson);
        adapter.buscarProjetosNovos();
        assertEquals(3, requisicoes.get(), "a falha não deve permitir servir o snapshot do login");
    }

    @Test
    void falhaAoCarregarCookiesLimpaSnapshotEPropagaErroDoMarketplace() throws Exception {
        AtomicInteger requisicoes = iniciarServidor((numero, exchange) -> responderJson(exchange, 200, LISTAGEM));
        Path cookies = tempDir.resolve("workana-cookies.json");
        Files.writeString(cookies, "{\"_workana_session\": \"sessao-a\"}");
        WorkanaConfig config = configFor(server, cookies);
        WorkanaClient client = client(config);
        WorkanaMarketplaceAdapter adapter = adapter(client, config);

        assertTrue(adapter.verificarLogin());
        Files.writeString(cookies, "json invalido");
        MarketplaceAdapterException erro = assertThrows(
            MarketplaceAdapterException.class, client::buscarProjetosJsonParaDiscovery
        );
        assertEquals(MarketplaceAdapterErrorType.FALHA_PARSE, erro.getType());

        Files.writeString(cookies, "{\"_workana_session\": \"sessao-a\"}");
        adapter.buscarProjetosNovos();
        assertEquals(2, requisicoes.get(), "falha ao ler cookies não pode preservar snapshot anterior");
    }

    @Test
    void snapshotDeOutraThreadNuncaEhEntregue() throws Exception {
        AtomicInteger requisicoes = iniciarServidor((numero, exchange) -> responderJson(exchange, 200, LISTAGEM));
        WorkanaMarketplaceAdapter adapter = adapter(configComCookies());

        assertTrue(adapter.verificarLogin());
        Thread outraThread = new Thread(adapter::buscarProjetosNovos, "discovery-outra-thread");
        outraThread.start();
        outraThread.join(10_000L);
        assertFalse(outraThread.isAlive(), "discovery da outra thread deve terminar");
        assertEquals(2, requisicoes.get(), "snapshot da thread de login não pode ser entregue a outra thread");

        adapter.buscarProjetosNovos();
        assertEquals(3, requisicoes.get(), "snapshot descartado não pode ser consumido depois pela thread original");
    }

    private WorkanaConfig configComCookies() throws Exception {
        Path cookies = tempDir.resolve("workana-cookies.json");
        Files.writeString(cookies, "{\"_workana_session\": \"sessao-a\"}");
        return configFor(server, cookies);
    }

    private WorkanaMarketplaceAdapter adapter(WorkanaConfig config) {
        WorkanaClient client = client(config);
        return adapter(client, config);
    }

    private WorkanaMarketplaceAdapter adapter(WorkanaClient client, WorkanaConfig config) {
        return new WorkanaMarketplaceAdapter(client, new WorkanaProjectParser(config), null);
    }

    private AtomicInteger iniciarServidor(Handler handler) throws Exception {
        AtomicInteger requisicoes = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jobs", exchange -> handler.responder(requisicoes.incrementAndGet(), exchange));
        server.start();
        return requisicoes;
    }

    @FunctionalInterface
    private interface Handler {
        void responder(int numero, com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException;
    }
}
