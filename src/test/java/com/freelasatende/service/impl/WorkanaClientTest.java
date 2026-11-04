package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.freelasatende.config.WorkanaConfig;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.freelasatende.service.impl.WorkanaClientTestSupport.client;
import static com.freelasatende.service.impl.WorkanaClientTestSupport.configFor;
import static com.freelasatende.service.impl.WorkanaClientTestSupport.parseForm;
import static com.freelasatende.service.impl.WorkanaClientTestSupport.responder;
import static com.freelasatende.service.impl.WorkanaClientTestSupport.responderJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkanaClientTest {

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
    void buscarProjetosUsaDiscoveryQueryConfiguradaECookies() throws Exception {
        AtomicReference<String> queryRecebida = new AtomicReference<>("");
        AtomicReference<String> cookieRecebido = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jobs", exchange -> {
            queryRecebida.set(exchange.getRequestURI().getRawQuery());
            cookieRecebido.set(exchange.getRequestHeaders().getFirst("Cookie"));
            responderJson(exchange, 200, """
                {"results":{"results":[]}}
                """);
        });
        server.start();

        Path cookies = tempDir.resolve("workana-cookies.json");
        Files.writeString(cookies, """
            {
              "_workana_session": "sessao",
              "wk_locale": "pt"
            }
            """);

        WorkanaConfig config = configFor(server, cookies);
        config.setDiscoveryQuery("?category=it-programming&language=pt");
        WorkanaClient client = client(config);

        JsonNode root = client.buscarProjetosJson();

        assertEquals("category=it-programming&language=pt", queryRecebida.get());
        assertTrue(cookieRecebido.get().contains("_workana_session=sessao"));
        assertTrue(cookieRecebido.get().contains("wk_locale=pt"));
        assertTrue(root.path("results").path("results").isArray());
    }

    @Test
    void enviarPerguntaUsaFormComCsrfCookiesESlugDoProjeto() throws Exception {
        AtomicReference<String> corpoRecebido = new AtomicReference<>("");
        AtomicReference<String> cookieRecebido = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/messages/bid/projeto-workana/", exchange -> responder(exchange, 200, """
            <html><body>
              <form method="post" action="/messages/index/projeto-workana/vitor-camillo">
                <input type="hidden" name="csrf_value" value="csrf-token">
                <input type="hidden" name="csrf_name" value="csrf-name">
                <input type="hidden" name="dcst-input" value="dcst-token">
                <textarea name="message[content]"></textarea>
              </form>
            </body></html>
            """));
        server.createContext("/messages/index/projeto-workana/vitor-camillo", exchange -> {
            cookieRecebido.set(exchange.getRequestHeaders().getFirst("Cookie"));
            corpoRecebido.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            responder(exchange, 200, "ok");
        });
        server.start();

        Path cookies = tempDir.resolve("workana-cookies.json");
        Files.writeString(cookies, """
            {
              "_workana_session": "sessao",
              "wk_locale": "pt"
            }
            """);

        WorkanaClient client = client(configFor(server, cookies));

        assertTrue(client.enviarPergunta("projeto-workana", "Ja foi atendido?"));

        Map<String, String> form = parseForm(corpoRecebido.get());
        assertEquals("csrf-token", form.get("csrf_value"));
        assertEquals("csrf-name", form.get("csrf_name"));
        assertEquals("dcst-token", form.get("dcst-input"));
        assertEquals("true", form.get("first_message"));
        assertEquals("Ja foi atendido?", form.get("message[content]"));
        assertEquals("[]", form.get("jsonAttachments"));
        assertTrue(cookieRecebido.get().contains("_workana_session=sessao"));
        assertTrue(cookieRecebido.get().contains("wk_locale=pt"));
    }

    @Test
    void enviarPerguntaTambemUsaPostUrlVueQuandoPaginaNaoTemFormHtml() throws Exception {
        AtomicReference<String> corpoRecebido = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/messages/bid/projeto-vue/", exchange -> responder(exchange, 200, """
            <html><head>
              <meta name="csrf-token" content="csrf-name/csrf-token" />
            </head><body>
              <script>window.Workana = {"ajaxCSRFHeaderKey":"X-CSRF-TOKEN","ajaxCSRFToken":"csrf-header-token"};</script>
              <messages-bid :initial="{&quot;postUrl&quot;:&quot;\\/messages\\/index\\/projeto-vue\\/vitor-camillo&quot;}"></messages-bid>
            </body></html>
            """));
        server.createContext("/messages/index/projeto-vue/vitor-camillo", exchange -> {
            corpoRecebido.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            responder(exchange, 200, "ok");
        });
        server.start();

        Path cookies = tempDir.resolve("workana-cookies.json");
        Files.writeString(cookies, """
            {
              "_workana_session": "sessao",
              "dcstcookieii": "dcst-cookie-token"
            }
            """);

        WorkanaClient client = client(configFor(server, cookies));

        assertTrue(client.enviarPergunta("projeto-vue", "Pergunta pelo Vue"));

        Map<String, String> form = parseForm(corpoRecebido.get());
        assertEquals("csrf-name", form.get("csrf_name"));
        assertEquals("csrf-token", form.get("csrf_value"));
        assertEquals("dcst-cookie-token", form.get("dcst-input"));
        assertEquals("Pergunta pelo Vue", form.get("message[content]"));
    }

    @Test
    void enviarPerguntaIncluiStatusECorpoQuandoPostFalha() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/messages/bid/projeto-com-erro/", exchange -> responder(exchange, 200, """
            <html><body>
              <form method="post" action="/messages/index/projeto-com-erro/vitor-camillo">
                <textarea name="message[content]"></textarea>
              </form>
            </body></html>
            """));
        server.createContext("/messages/index/projeto-com-erro/vitor-camillo", exchange ->
            responder(exchange, 422, "<html><body>Mensagem deve ter pelo menos 100 caracteres</body></html>"));
        server.start();

        Path cookies = tempDir.resolve("workana-cookies.json");
        Files.writeString(cookies, """
            {"_workana_session": "sessao"}
            """);

        WorkanaClient client = client(configFor(server, cookies));

        MarketplaceAdapterException exception = assertThrows(
            MarketplaceAdapterException.class,
            () -> client.enviarPergunta("projeto-com-erro", "Pergunta curta")
        );

        assertTrue(exception.getMessage().contains("HTTP 422"));
        assertTrue(exception.getMessage().contains("Mensagem deve ter pelo menos 100 caracteres"));
    }

    @Test
    void enviarPerguntaClassifica403AcessoNegadoComoOperacaoIndisponivel() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/messages/bid/projeto-sem-acesso/", exchange -> responder(exchange, 200, """
            <html><body>
              <form method="post" action="/messages/index/projeto-sem-acesso/vitor-camillo">
                <textarea name="message[content]"></textarea>
              </form>
            </body></html>
            """));
        server.createContext("/messages/index/projeto-sem-acesso/vitor-camillo", exchange ->
            responder(exchange, 403, "Acesso Negado Voce nao possui autorizacao para acessar esse conteudo"));
        server.start();

        Path cookies = tempDir.resolve("workana-cookies.json");
        Files.writeString(cookies, """
            {"_workana_session": "sessao"}
            """);

        WorkanaClient client = client(configFor(server, cookies));

        MarketplaceAdapterException exception = assertThrows(
            MarketplaceAdapterException.class,
            () -> client.enviarPergunta("projeto-sem-acesso", "Pergunta segura")
        );

        assertEquals(MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL, exception.getType());
        assertTrue(exception.getMessage().contains("HTTP 403"));
    }

    @Test
    void enviarPerguntaClassifica403NoFormComoOperacaoIndisponivel() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/messages/bid/projeto-sem-form/", exchange ->
            responder(exchange, 403, "Acesso Negado Voce nao possui autorizacao para acessar esse conteudo"));
        server.start();

        Path cookies = tempDir.resolve("workana-cookies.json");
        Files.writeString(cookies, """
            {"_workana_session": "sessao"}
            """);

        WorkanaClient client = client(configFor(server, cookies));

        MarketplaceAdapterException exception = assertThrows(
            MarketplaceAdapterException.class,
            () -> client.enviarPergunta("projeto-sem-form", "Pergunta segura")
        );

        assertEquals(MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL, exception.getType());
        assertTrue(exception.getMessage().contains("HTTP 403"));
        assertTrue(exception.getMessage().contains("Acesso Negado"));
    }

    @Test
    void listarInboxMensagensEEnviarRespostaDaConversaWorkana() throws Exception {
        AtomicReference<String> corpoResposta = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/roaster/search/", exchange -> responderJson(exchange, 200, """
            [
              {
                "id": 3528955,
                "name": "Plataforma multi canal",
                "slug": "plataforma-multi-canal",
                "threads": [
                  {
                    "id": 40124670,
                    "has_unread": true,
                    "last_post_created": 210742411,
                    "messagePreview": "Ainda nao",
                    "url": "/messages/index/plataforma-multi-canal/vitor-camillo",
                    "roasterUrl": "/inbox/plataforma-multi-canal/vitor-camillo",
                    "other_party": {
                      "user_id": 4633912,
                      "company_id": 4629563,
                      "name": "Roni"
                    }
                  }
                  ,
                  {
                    "id": 40124671,
                    "has_unread": false,
                    "last_post_created": 210742400,
                    "messagePreview": "Conversa antiga",
                    "url": "/messages/index/conversa-antiga/vitor-camillo",
                    "roasterUrl": "/inbox/conversa-antiga/vitor-camillo",
                    "other_party": {
                      "user_id": 4633913,
                      "company_id": 4629564,
                      "name": "Cliente antigo"
                    }
                  }
                ]
              }
            ]
            """));
        server.createContext("/inbox/plataforma-multi-canal/vitor-camillo", exchange -> responder(exchange, 200, """
            <html><body>
              <inbox :initial="{&quot;messageIndexData&quot;:{
                &quot;postUrl&quot;:&quot;http://127.0.0.1:%d/messages/index/plataforma-multi-canal/vitor-camillo&quot;,
                &quot;pageTitle&quot;:&quot;Plataforma multi canal&quot;,
                &quot;thread&quot;:{&quot;id&quot;:40124670},
                &quot;otherParty&quot;:{&quot;name&quot;:&quot;Roni&quot;},
                &quot;messages&quot;:[
                  {&quot;id&quot;:210742368,&quot;threadId&quot;:40124670,&quot;classes&quot;:&quot;self&quot;,
                   &quot;isByHuman&quot;:true,&quot;isUnread&quot;:false,
                   &quot;author&quot;:{&quot;name&quot;:&quot;Vitor C.&quot;,&quot;identifier&quot;:1409051},
                   &quot;content&quot;:&quot;Ja foi atendido?&quot;},
                  {&quot;id&quot;:210742411,&quot;threadId&quot;:40124670,&quot;classes&quot;:&quot;other&quot;,
                   &quot;isByHuman&quot;:true,&quot;isUnread&quot;:true,
                   &quot;author&quot;:{&quot;name&quot;:&quot;Roni&quot;,&quot;identifier&quot;:4633912},
                   &quot;content&quot;:&quot;Ainda n&atilde;o&quot;}
                ]
              }}"></inbox>
            </body></html>
            """.formatted(server.getAddress().getPort())));
        server.createContext("/messages/index/plataforma-multi-canal/vitor-camillo", exchange -> {
            corpoResposta.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            responder(exchange, 200, "{\"status\":\"ok\"}");
        });
        server.start();

        Path cookies = tempDir.resolve("workana-cookies.json");
        Files.writeString(cookies, """
            {"_workana_session": "sessao"}
            """);

        WorkanaConfig config = configFor(server, cookies);
        config.setWorkerSlug("vitor-camillo");
        WorkanaClient client = client(config);

        List<ConversaResumo> conversas = client.listarConversasInbox();
        assertEquals(2, conversas.size());
        assertEquals(40124670L, conversas.get(0).getIdConversa());
        assertEquals("Roni", conversas.get(0).getNomeCliente());
        assertEquals("Ainda nao", conversas.get(0).getUltimaMensagem());
        assertFalse(conversas.get(0).getVisualizada());
        assertEquals(40124671L, conversas.get(1).getIdConversa());
        assertTrue(conversas.get(1).getVisualizada());

        List<MensagemConversa> mensagens = client.listarMensagensConversa(40124670L);
        assertEquals(2, mensagens.size());
        assertTrue(mensagens.get(0).getEnviadaPorMim());
        assertFalse(mensagens.get(1).getEnviadaPorMim());
        assertEquals("Ainda não", mensagens.get(1).getTexto());

        MensagemConversa enviada = client.enviarMensagemConversa(40124670L, "Vamos continuar por aqui.");

        assertTrue(enviada.getEnviadaPorMim());
        Map<String, String> form = parseForm(corpoResposta.get());
        assertEquals("Vamos continuar por aqui.", form.get("message[content]"));
        assertEquals("[]", form.get("jsonAttachments"));
        assertFalse(form.containsKey("first_message"));

        corpoResposta.set("");
        WorkanaClient clientAposRestart = client(config);
        MensagemConversa enviadaSemCacheLocal = clientAposRestart.enviarMensagemConversa(
            40124670L,
            "Resposta aprovada depois do restart."
        );

        assertTrue(enviadaSemCacheLocal.getEnviadaPorMim());
        Map<String, String> formAposRestart = parseForm(corpoResposta.get());
        assertEquals("Resposta aprovada depois do restart.", formAposRestart.get("message[content]"));
        assertFalse(formAposRestart.containsKey("first_message"));
    }

    @Test
    void listarMensagensTentaMessagesIndexQuandoInboxRetorna404() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/roaster/search/", exchange -> responderJson(exchange, 200, """
            [
              {
                "id": 3528955,
                "name": "Projeto fallback",
                "slug": "projeto-fallback",
                "threads": [
                  {
                    "id": 40130001,
                    "has_unread": true,
                    "last_post_created": 210742500,
                    "messagePreview": "Consegue me ajudar?",
                    "url": "/messages/index/projeto-fallback/vitor-camillo",
                    "roasterUrl": "/inbox/projeto-fallback/vitor-camillo",
                    "other_party": {
                      "user_id": 4634001,
                      "company_id": 4629001,
                      "name": "Cliente fallback"
                    }
                  }
                ]
              }
            ]
            """));
        server.createContext("/inbox/projeto-fallback/vitor-camillo", exchange ->
            responder(exchange, 404, "not found"));
        server.createContext("/messages/index/projeto-fallback/vitor-camillo", exchange -> responder(exchange, 200, """
            <html><body>
              <inbox :initial="{&quot;messageIndexData&quot;:{
                &quot;postUrl&quot;:&quot;http://127.0.0.1:%d/messages/index/projeto-fallback/vitor-camillo&quot;,
                &quot;pageTitle&quot;:&quot;Projeto fallback&quot;,
                &quot;thread&quot;:{&quot;id&quot;:40130001},
                &quot;otherParty&quot;:{&quot;name&quot;:&quot;Cliente fallback&quot;},
                &quot;messages&quot;:[
                  {&quot;id&quot;:210742501,&quot;threadId&quot;:40130001,&quot;classes&quot;:&quot;other&quot;,
                   &quot;isByHuman&quot;:true,&quot;isUnread&quot;:true,
                   &quot;author&quot;:{&quot;name&quot;:&quot;Cliente fallback&quot;,&quot;identifier&quot;:4634001},
                   &quot;content&quot;:&quot;Consegue me ajudar?&quot;}
                ]
              }}"></inbox>
            </body></html>
            """.formatted(server.getAddress().getPort())));
        server.start();

        Path cookies = tempDir.resolve("workana-cookies.json");
        Files.writeString(cookies, """
            {"_workana_session": "sessao"}
            """);

        WorkanaConfig config = configFor(server, cookies);
        config.setWorkerSlug("vitor-camillo");
        WorkanaClient client = client(config);

        List<ConversaResumo> conversas = client.listarConversasInbox();
        assertEquals(1, conversas.size());

        List<MensagemConversa> mensagens = client.listarMensagensConversa(40130001L);

        assertEquals(1, mensagens.size());
        assertEquals("Consegue me ajudar?", mensagens.get(0).getTexto());
        assertFalse(mensagens.get(0).getEnviadaPorMim());
    }

    @Test
    void listarMensagensClassifica404PersistenteComoConversaInacessivel() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/roaster/search/", exchange -> responderJson(exchange, 200, """
            [
              {
                "id": 3528956,
                "name": "Projeto removido",
                "slug": "projeto-removido",
                "threads": [
                  {
                    "id": 40130002,
                    "has_unread": true,
                    "last_post_created": 210742501,
                    "messagePreview": "Conversa removida",
                    "url": "/messages/index/projeto-removido/vitor-camillo",
                    "roasterUrl": "/inbox/projeto-removido/vitor-camillo",
                    "other_party": {
                      "user_id": 4634002,
                      "company_id": 4629002,
                      "name": "Cliente removido"
                    }
                  }
                ]
              }
            ]
            """));
        server.createContext("/inbox/projeto-removido/vitor-camillo", exchange ->
            responder(exchange, 404, "not found"));
        server.createContext("/messages/index/projeto-removido/vitor-camillo", exchange ->
            responder(exchange, 404, "not found"));
        server.start();

        Path cookies = tempDir.resolve("workana-cookies.json");
        Files.writeString(cookies, """
            {"_workana_session": "sessao"}
            """);

        WorkanaConfig config = configFor(server, cookies);
        config.setWorkerSlug("vitor-camillo");
        WorkanaClient client = client(config);

        List<ConversaResumo> conversas = client.listarConversasInbox();
        assertEquals(1, conversas.size());

        MarketplaceAdapterException exception = assertThrows(
            MarketplaceAdapterException.class,
            () -> client.listarMensagensConversa(40130002L)
        );

        assertEquals(MarketplaceAdapterErrorType.CONVERSA_INACESSIVEL, exception.getType());
        assertTrue(exception.getMessage().contains("40130002"));
        assertTrue(exception.getMessage().contains("HTTP 404"));
    }

}
