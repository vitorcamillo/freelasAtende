package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.UpworkConfig;
import com.freelasatende.config.WorkanaConfig;
import com.freelasatende.config.FreelancerConfig;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.model.dto.MensagemConversa;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MarketplaceProposalClientsTest {

    @TempDir Path tempDir;
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test
    void upworkPreChecaElegibilidadeEEnviaMutationAtual() throws Exception {
        AtomicReference<String> mutation = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/oauth2/token/subordinate/v3/proposals-id", e ->
            responder(e, 200, "window.cb({\"token\":\"temporario\"})"));
        server.createContext("/api/graphql/v1", e -> {
            String alias = e.getRequestURI().getRawQuery();
            String body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (alias.contains("preApply")) responder(e, 200, "{\"data\":{\"preApplyCheck\":{\"passed\":true}}}");
            else if (alias.contains("loadOpening")) responder(e, 200, "{\"data\":{\"proposalsOpenings\":{\"opening\":{\"id\":\"open-1\",\"job\":{\"info\":{\"type\":\"fixed\"},\"budget\":{\"amount\":400}}},\"occupation\":{\"id\":\"occ-1\"},\"questions\":{\"questions\":[]}}}} ");
            else if (alias.contains("loadJobDetails")) responder(e, 200, "{\"data\":{\"fetchJobDetailsAndContext\":{\"engagementDurations\":[],\"opening\":{\"engagementDuration\":{\"type\":\"one_to_three_months\"}}}}}");
            else if (alias.contains("teamsCombined")) responder(e, 200, "{\"data\":{\"jobProposalsTeamsCombined\":{\"freelancer\":{\"person\":{\"id\":\"person-1\",\"legacyId\":\"123\"}}}}}");
            else { mutation.set(body); responder(e, 200, "{\"data\":{\"createProposal\":{\"success\":true,\"newProposalId\":\"p-1\"}}}"); }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        Path cookies = tempDir.resolve("up.json");
        Files.writeString(cookies, "{\"current_organization_uid\":\"org-1\",\"session\":\"ok\"}");
        UpworkConfig config = new UpworkConfig();
        config.setUrlBase(base); config.setAuthUrlBase(base); config.setProposalsClientId("proposals-id");
        config.setCookiesArquivo(cookies.toString());
        ObjectMapper mapper = new ObjectMapper();
        UpworkClient baseClient = new UpworkClient(config, mapper);
        UpworkProposalClient client = new UpworkProposalClient(config, mapper, baseClient);

        client.enviar(Projeto.builder().urlProjeto(base + "/jobs/~022081785343887882413/").build(),
            Proposta.builder().valorProposto(new BigDecimal("400")).mensagem("Proposta tecnica").build());

        assertTrue(mutation.get().contains("createProposal"));
        assertTrue(mutation.get().contains("Proposta tecnica"));
        assertTrue(mutation.get().contains("person-1"));
        assertFalse(mutation.get().contains("\"version\""));
        assertTrue(mutation.get().contains("\"estimatedDuration\":\"one_to_three_months\""));
    }

    @Test
    void workanaDescobreNomesAtuaisDoFormularioEEnviaValorPrazoEMensagem() throws Exception {
        AtomicReference<String> body = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/messages/bid/projeto/", e -> responder(e, 200, """
            <form action="/messages/index/projeto/vitor">
              <input name="bid[amount]" value=""><input name="bid[delivery_time]" value="7">
              <textarea name="message[content]"></textarea>
            </form>
            """));
        server.createContext("/messages/index/projeto/vitor", e -> {
            body.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            responder(e, 200, "ok");
        });
        server.start();
        Path cookies = tempDir.resolve("workana.json"); Files.writeString(cookies, "{\"session\":\"ok\"}");
        WorkanaConfig config = new WorkanaConfig();
        config.setUrlBase("http://127.0.0.1:" + server.getAddress().getPort());
        config.setCookiesArquivo(cookies.toString());
        WorkanaProposalClient client = new WorkanaProposalClient(config, new ObjectMapper());

        client.enviar(Projeto.builder().urlProjeto(config.getUrlBase() + "/job/projeto").build(),
            Proposta.builder().valorProposto(new BigDecimal("900")).mensagem("Entrega completa").build());

        assertTrue(body.get().contains("bid%5Bamount%5D=900"));
        assertTrue(body.get().contains("bid%5Bdelivery_time%5D=7"));
        assertTrue(body.get().contains("message%5Bcontent%5D=Entrega+completa"));
    }

    @Test
    void freelancerPreChecaRestricoesEEnviaOfertaCompletaSomenteQuandoElegivel() throws Exception {
        AtomicReference<String> body = new AtomicReference<>("");
        AtomicReference<String> oauth = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/projects/0.1/projects/42/", e -> responder(e, 200, """
            {"status":"success","result":{"id":42,"status":"active","type":"fixed",
            "budget":{"minimum":100,"maximum":500},"upgrades":{"pf_only":false,"qualified":false}}}
            """));
        server.createContext("/api/projects/0.1/bids/", e -> {
            body.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            oauth.set(e.getRequestHeaders().getFirst("freelancer-oauth-v1"));
            responder(e, 200, "{\"status\":\"success\",\"result\":{\"id\":7}}");
        });
        server.start();
        Path cookies = tempDir.resolve("freelancer.json");
        Files.writeString(cookies, "{\"freelancer_live_session\":\"ok\"}");
        FreelancerConfig config = new FreelancerConfig();
        config.setUrlBase("http://127.0.0.1:" + server.getAddress().getPort());
        config.setCookiesArquivo(cookies.toString());
        config.setOauthToken("oauth-token");
        config.setBidderId(25832245L);
        FreelancerClient client = new FreelancerClient(config, new ObjectMapper());

        client.enviarProposta(42L, Proposta.builder().valorProposto(new BigDecimal("250"))
            .mensagem("Consigo entregar").build());

        assertTrue(body.get().contains("\"project_id\":42"));
        assertTrue(body.get().contains("\"amount\":250"));
        assertTrue(body.get().contains("\"period\":7"));
        assertTrue(body.get().contains("\"milestone_percentage\":100"));
        assertTrue(body.get().contains("Consigo entregar"));
        assertEquals("oauth-token", oauth.get());
    }

    @Test
    void freelancerLeMensagensComContratoAtualDaApi() throws Exception {
        AtomicReference<String> query = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/messages/0.1/messages", e -> {
            query.set(e.getRequestURI().getRawQuery());
            responder(e, 200, """
                {"status":"success","result":{"messages":[{"id":91,"thread_id":81,
                "message":"Hello Vitor","time_created":1787679000,"from_user":123}],
                "users":{"123":{"username":"client-one"}}}}
                """);
        });
        server.start();
        Path cookies = tempDir.resolve("freelancer-inbox.json");
        Files.writeString(cookies, "{\"freelancer_live_session\":\"ok\"}");
        FreelancerConfig config = new FreelancerConfig();
        config.setUrlBase("http://127.0.0.1:" + server.getAddress().getPort());
        config.setCookiesArquivo(cookies.toString());
        FreelancerClient client = new FreelancerClient(config, new ObjectMapper());

        List<MensagemConversa> mensagens = client.listarMensagensConversa(81L);

        assertEquals(1, mensagens.size());
        assertEquals("Hello Vitor", mensagens.get(0).getTexto());
        assertTrue(query.get().contains("threads%5B%5D=81"));
        assertTrue(query.get().contains("user_details=true"));
        assertFalse(query.get().contains("thread_details=true"));
    }

    @Test
    void freelancerBloqueiaProjetoPreferencialAntesDeEnviarOferta() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/projects/0.1/projects/43/", e -> responder(e, 200, """
            {"status":"success","result":{"id":43,"status":"active","type":"fixed",
            "budget":{"minimum":100,"maximum":500},"upgrades":{"pf_only":true}}}
            """));
        server.start();
        Path cookies = tempDir.resolve("freelancer-blocked.json");
        Files.writeString(cookies, "{\"freelancer_live_session\":\"ok\"}");
        FreelancerConfig config = new FreelancerConfig();
        config.setUrlBase("http://127.0.0.1:" + server.getAddress().getPort());
        config.setCookiesArquivo(cookies.toString());
        FreelancerClient client = new FreelancerClient(config, new ObjectMapper());

        MarketplaceAdapterException erro = assertThrows(MarketplaceAdapterException.class,
            () -> client.enviarProposta(43L, Proposta.builder().valorProposto(new BigDecimal("250"))
                .mensagem("Consigo entregar").build()));

        assertEquals(MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL, erro.getType());
        assertTrue(erro.getMessage().contains("Preferencial"));
    }

    private void responder(com.sun.net.httpserver.HttpExchange e, int status, String texto) throws java.io.IOException {
        byte[] bytes = texto.getBytes(StandardCharsets.UTF_8);
        e.sendResponseHeaders(status, bytes.length); e.getResponseBody().write(bytes); e.close();
    }
}
