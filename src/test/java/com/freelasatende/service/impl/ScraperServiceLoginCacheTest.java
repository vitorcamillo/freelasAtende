package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.FreelasConfig;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScraperServiceLoginCacheTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void verificarLoginNaoReaproveitaCacheQuandoCookiesMudam() throws Exception {
        FakeAutenticacaoService autenticacao = new FakeAutenticacaoService();
        AtomicInteger chamadasDashboard = new AtomicInteger();
        server = iniciarServidorDashboard(autenticacao, chamadasDashboard);

        FreelasConfig config = new FreelasConfig();
        config.setUrlBase("http://127.0.0.1:" + server.getAddress().getPort());

        ScraperServiceImpl scraper = new ScraperServiceImpl(autenticacao, config, new ObjectMapper());
        ReflectionTestUtils.setField(scraper, "loginCacheMillis", 120_000L);

        autenticacao.setLogado(false);
        assertFalse(scraper.verificarLogin());
        assertEquals(1, chamadasDashboard.get());

        autenticacao.setLogado(true);
        autenticacao.atualizarCookies(Map.of("JSESSIONID", "sessao-renovada"));

        assertTrue(scraper.verificarLogin());
        assertEquals(2, chamadasDashboard.get());
    }

    private HttpServer iniciarServidorDashboard(
        FakeAutenticacaoService autenticacao,
        AtomicInteger chamadasDashboard
    ) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/dashboard", exchange -> {
            chamadasDashboard.incrementAndGet();
            String marcador = autenticacao.isLogado()
                ? "<main>Dashboard autenticado</main>"
                : "<form id=\"frmEfetuarLogin\">Faça seu login</form>";
            byte[] body = (marcador + " conteudo".repeat(220)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        httpServer.start();
        return httpServer;
    }

    private static class FakeAutenticacaoService implements AutenticacaoService {
        private final AtomicLong versao = new AtomicLong();
        private Map<String, String> cookies = new HashMap<>();
        private boolean logado;

        boolean isLogado() {
            return logado;
        }

        void setLogado(boolean logado) {
            this.logado = logado;
        }

        void atualizarCookies(Map<String, String> cookies) {
            this.cookies = new HashMap<>(cookies);
            versao.incrementAndGet();
        }

        @Override
        public boolean verificarSessaoValida() {
            return logado;
        }

        @Override
        public void renovarSessaoSeNecessario() {
        }

        @Override
        public void fazerLogin(String email, String senha) {
        }

        @Override
        public void carregarCookiesJson(String jsonCookies) {
            versao.incrementAndGet();
        }

        @Override
        public void salvarCookies() {
        }

        @Override
        public boolean carregarCookies() {
            return !cookies.isEmpty();
        }

        @Override
        public Map<String, String> obterCookies() {
            return new HashMap<>(cookies);
        }

        @Override
        public long versaoCookies() {
            return versao.get();
        }
    }
}
