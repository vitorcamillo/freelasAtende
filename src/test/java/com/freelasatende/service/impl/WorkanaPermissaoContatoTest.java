package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.freelasatende.service.impl.WorkanaClientTestSupport.client;
import static com.freelasatende.service.impl.WorkanaClientTestSupport.configFor;
import static com.freelasatende.service.impl.WorkanaClientTestSupport.responder;
import static com.freelasatende.service.impl.WorkanaClientTestSupport.responderJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cobre a checagem de /workers/permissions/make_bid_action, o mesmo endpoint que o site
 * consulta ao clicar em "Faca uma pergunta". O corpo usado aqui e a resposta real capturada
 * da Workana para uma conta com as conexoes da semana esgotadas.
 */
class WorkanaPermissaoContatoTest {

    private static final String PLAN_WALL_SEM_CONEXOES = """
        {"extraClasses":"modal-plan-wall no-connects",
         "title":"Poxa, voc\\u00ea n\\u00e3o tem mais conex\\u00f5es dispon\\u00edveis",
         "subtitle":"Voc\\u00ea enviou todas as conex\\u00f5es poss\\u00edveis da semana",
         "acceptLabel":{"url":"https://www.workana.com/plans","text":"Melhore seu plano"}}
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
    @DisplayName("Plan wall com no-connects vira LIMITE_CONEXOES sem tentar o formulario")
    void planWallSemConexoesInterrompeAntesDoFormulario() throws Exception {
        AtomicBoolean formularioAcessado = new AtomicBoolean(false);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/workers/permissions/make_bid_action", exchange ->
            responderJson(exchange, 403, PLAN_WALL_SEM_CONEXOES));
        server.createContext("/messages/bid/", exchange -> {
            formularioAcessado.set(true);
            responder(exchange, 200, "<html><body>form</body></html>");
        });
        server.start();

        WorkanaClient client = client(configFor(server, cookies()));

        MarketplaceAdapterException exception = assertThrows(
            MarketplaceAdapterException.class,
            () -> client.enviarPergunta("projeto-x", "Ja foi atendido?")
        );

        assertEquals(MarketplaceAdapterErrorType.LIMITE_CONEXOES, exception.getType());
        assertTrue(exception.getMessage().contains("conex"), exception.getMessage());
        assertFalse(formularioAcessado.get(), "nao deve gastar request no formulario quando ja foi negado");
    }

    @Test
    @DisplayName("Negacao sem no-connects vira OPERACAO_INDISPONIVEL")
    void negacaoGenericaViraOperacaoIndisponivel() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/workers/permissions/make_bid_action", exchange ->
            responderJson(exchange, 403, """
                {"extraClasses":"modal-plan-wall","title":"Acao indisponivel para sua conta"}
                """));
        server.start();

        MarketplaceAdapterException exception = assertThrows(
            MarketplaceAdapterException.class,
            () -> client(configFor(server, cookies())).enviarPergunta("projeto-y", "Ja foi atendido?")
        );

        assertEquals(MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL, exception.getType());
        assertTrue(exception.getMessage().contains("Acao indisponivel"), exception.getMessage());
    }

    @Test
    @DisplayName("Permissao concedida deixa o envio seguir normalmente")
    void permissaoConcedidaSegueParaOFormulario() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/workers/permissions/make_bid_action", exchange ->
            responderJson(exchange, 200, """
                {"allowed":true}
                """));
        server.createContext("/messages/bid/", exchange -> responder(exchange, 200, """
            <html><body>
              <form action="/messages/index/projeto-z/worker" method="post">
                <input name="csrf_name" value="tok"/>
                <textarea name="message[content]"></textarea>
              </form>
            </body></html>
            """));
        server.createContext("/messages/index/", exchange -> responder(exchange, 200, "ok"));
        server.start();

        assertTrue(client(configFor(server, cookies())).enviarPergunta("projeto-z", "Ja foi atendido?"));
    }

    @Test
    @DisplayName("Falha na consulta de permissao nao bloqueia o envio (falha aberta)")
    void falhaNaConsultaDePermissaoNaoBloqueia() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/messages/bid/", exchange -> responder(exchange, 200, """
            <html><body>
              <form action="/messages/index/projeto-w/worker" method="post">
                <textarea name="message[content]"></textarea>
              </form>
            </body></html>
            """));
        server.createContext("/messages/index/", exchange -> responder(exchange, 200, "ok"));
        server.start();

        assertTrue(client(configFor(server, cookies())).enviarPergunta("projeto-w", "Ja foi atendido?"));
    }

    @Test
    @DisplayName("Analisador extrai o motivo legivel do plan wall real")
    void analisadorExtraiMotivoDoPlanWallReal() {
        WorkanaPermissaoContato permissao =
            WorkanaPermissaoContato.analisar(403, PLAN_WALL_SEM_CONEXOES, new ObjectMapper());

        assertFalse(permissao.permitido());
        assertTrue(permissao.semConexoes());
        assertTrue(permissao.motivo().contains("conex"), permissao.motivo());
    }

    @Test
    @DisplayName("Corpo inesperado nega sem afirmar falta de conexoes")
    void corpoInesperadoNegaSemAfirmarFaltaDeConexoes() {
        ObjectMapper mapper = new ObjectMapper();

        WorkanaPermissaoContato vazio = WorkanaPermissaoContato.analisar(403, "", mapper);
        WorkanaPermissaoContato html = WorkanaPermissaoContato.analisar(403, "<html>Just a moment</html>", mapper);
        WorkanaPermissaoContato ok = WorkanaPermissaoContato.analisar(200, "", mapper);

        assertFalse(vazio.permitido());
        assertFalse(vazio.semConexoes());
        assertFalse(html.permitido());
        assertFalse(html.semConexoes());
        assertTrue(ok.permitido());
    }

    private Path cookies() throws Exception {
        Path arquivo = tempDir.resolve("workana-cookies.json");
        if (!Files.exists(arquivo)) {
            Files.writeString(arquivo, """
                {"workana_session": "sessao"}
                """);
        }
        return arquivo;
    }
}
