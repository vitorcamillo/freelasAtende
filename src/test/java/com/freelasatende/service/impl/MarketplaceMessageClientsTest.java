package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.FreelancerBrConfig;
import com.freelasatende.config.FreelancerConfig;
import com.freelasatende.config.UpworkConfig;
import com.freelasatende.model.dto.MensagemConversa;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketplaceMessageClientsTest {

    @TempDir Path tempDir;
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void upworkResolveSalaParticipanteEPublicaStoryAtual() throws Exception {
        AtomicReference<String> enviado = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/oauth2/token/subordinate/v3/messages-id",
            e -> responder(e, 200, "window.cb({\"token\":\"temporario\"})"));
        server.createContext("/api/v3/rooms/rooms", e -> {
            String path = e.getRequestURI().getPath();
            if (path.endsWith("/users")) {
                responder(e, 200, "{\"users\":[{\"orgId\":\"org-1\",\"userId\":\"user-1\"}]}");
            } else if (path.endsWith("/stories")) {
                enviado.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                responder(e, 200, "{}");
            } else {
                responder(e, 200, "{\"rooms\":[{\"roomId\":\"room-abc\",\"roomName\":\"Projeto\"}]}");
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        Path cookies = tempDir.resolve("upwork.json");
        Files.writeString(cookies, "{\"current_organization_uid\":\"org-1\",\"session\":\"ok\"}");
        UpworkConfig config = new UpworkConfig();
        config.setUrlBase(base);
        config.setAuthUrlBase(base);
        config.setMessagesClientId("messages-id");
        config.setCookiesArquivo(cookies.toString());
        UpworkClient client = new UpworkClient(config, new ObjectMapper());
        long conversaId = UpworkInboxMapper.idEstavel("room-abc");

        MensagemConversa mensagem = client.enviarMensagemConversa(conversaId, "Vamos continuar por aqui.");

        assertTrue(mensagem.getEnviadaPorMim());
        assertTrue(enviado.get().contains("\"roomId\":\"room-abc\""));
        assertTrue(enviado.get().contains("\"actionType\":\"eo:post\""));
        assertTrue(enviado.get().contains("Vamos continuar por aqui."));
    }

    @Test
    void freelancerPublicaMensagemNoEndpointOficialDaThread() throws Exception {
        AtomicReference<String> enviado = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/messages/0.1/threads/81/messages/", e -> {
            enviado.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            responder(e, 200, "{\"status\":\"success\"}");
        });
        server.start();
        Path cookies = tempDir.resolve("freelancer.json");
        Files.writeString(cookies, "{\"freelancer_live_session\":\"ok\"}");
        FreelancerConfig config = new FreelancerConfig();
        config.setUrlBase("http://127.0.0.1:" + server.getAddress().getPort());
        config.setCookiesArquivo(cookies.toString());
        config.setOauthToken("oauth-token");
        FreelancerClient client = new FreelancerClient(config, new ObjectMapper());

        MensagemConversa mensagem = client.enviarMensagemConversa(81L, "Mensagem de retorno");

        assertTrue(mensagem.getEnviadaPorMim());
        assertTrue(enviado.get().contains("message=Mensagem+de+retorno"));
    }

    @Test
    void freelancerBidUsaMinimoRealDoProjetoQuandoOrcamentoInsuficiente() throws Exception {
        AtomicReference<String> corpoBid = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Detalhes do projeto: minimo real 600, bem acima do orcamento informado (0).
        server.createContext("/api/projects/0.1/projects/555/", e -> responder(e, 200, """
            {"result":{"budget":{"minimum":600,"maximum":900}}}
            """));
        server.createContext("/api/projects/0.1/bids/", e -> {
            corpoBid.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            responder(e, 200, "{\"status\":\"success\"}");
        });
        server.start();
        Path cookies = tempDir.resolve("freelancer.json");
        Files.writeString(cookies, "{\"freelancer_live_session\":\"ok\"}");
        FreelancerConfig config = new FreelancerConfig();
        config.setUrlBase("http://127.0.0.1:" + server.getAddress().getPort());
        config.setCookiesArquivo(cookies.toString());
        config.setOauthToken("oauth-token");
        FreelancerClient client = new FreelancerClient(config, new ObjectMapper());

        boolean enviado = client.enviarPergunta(555L, "Ola! Tenho interesse.", java.math.BigDecimal.ZERO);

        assertTrue(enviado);
        // O bid precisa carregar o minimo do projeto (600) e tipos numericos reais.
        assertTrue(corpoBid.get().contains("\"amount\":600"), corpoBid.get());
        assertTrue(corpoBid.get().contains("\"milestone_percentage\":50"), corpoBid.get());
        assertTrue(corpoBid.get().contains("\"bidder_id\":"), corpoBid.get());
    }

    @Test
    void freelancerClassificaDestinatarioSemPermissaoComoConversaInacessivel() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/messages/0.1/threads/81/messages/", e -> responder(e, 403, """
            {"status":"error","message":"You are no longer able to message this user",
             "errorcode":"AuthorizationExceptionCodes.PERMISSIONSONDENIED"}
            """));
        server.start();
        Path cookies = tempDir.resolve("freelancer.json");
        Files.writeString(cookies, "{\"freelancer_live_session\":\"ok\"}");
        FreelancerConfig config = new FreelancerConfig();
        config.setUrlBase("http://127.0.0.1:" + server.getAddress().getPort());
        config.setCookiesArquivo(cookies.toString());
        config.setOauthToken("oauth-token");
        FreelancerClient client = new FreelancerClient(config, new ObjectMapper());

        MarketplaceAdapterException exception = assertThrows(
            MarketplaceAdapterException.class,
            () -> client.enviarMensagemConversa(81L, "Mensagem de retorno")
        );

        assertEquals(MarketplaceAdapterErrorType.CONVERSA_INACESSIVEL, exception.getType());
    }

    @Test
    void freelancerBrRenovaCsrfERespondeConversaMapeada() throws Exception {
        AtomicReference<String> enviado = new AtomicReference<>("");
        String chave = "conv-composite";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/account/inbox", e -> {
            if (e.getRequestMethod().equals("POST")) {
                enviado.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                responder(e, 200, "{\"success\":true}");
            } else {
                responder(e, 200, """
                    <div class="message-card" data-conversation-id="c1" data-timestamp="1787640574">
                      <div class="contact-block"><h4>Cliente</h4></div>
                      <a href="/account/inbox/conv-composite"><h4 class="card-title">Projeto</h4>
                      <p class="message-text">Ola</p></a>
                    </div>
                    """);
            }
        });
        server.createContext("/csrfresh", e ->
            responder(e, 200, "{\"token\":\"csrf-token\",\"xsrf\":\"xsrf-cookie\"}"));
        server.start();
        Path cookies = tempDir.resolve("freelancer-br.json");
        Files.writeString(cookies, "{\"freelancer_live_session\":\"ok\",\"cf_clearance\":\"ok\"}");
        FreelancerBrConfig config = new FreelancerBrConfig();
        config.setUrlBase("http://127.0.0.1:" + server.getAddress().getPort());
        config.setCookiesArquivo(cookies.toString());
        ObjectMapper mapper = new ObjectMapper();
        FreelancerBrClient client =
            new FreelancerBrClient(config, mapper, new FreelancerBrHttp(config, mapper));
        long conversaId = FreelancerBrInboxMapper.idEstavel(chave);

        client.listarConversasInbox();
        MensagemConversa mensagem = client.enviarMensagemConversa(conversaId, "Resposta BR");

        assertEquals(conversaId, mensagem.getIdConversa());
        assertTrue(enviado.get().contains("name=\"message\""));
        assertTrue(enviado.get().contains("Resposta BR"));
    }

    private void responder(HttpExchange exchange, int status, String texto) throws java.io.IOException {
        byte[] bytes = texto.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
