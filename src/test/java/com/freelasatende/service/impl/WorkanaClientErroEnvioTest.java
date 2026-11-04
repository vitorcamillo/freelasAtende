package com.freelasatende.service.impl;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.freelasatende.service.impl.WorkanaClientTestSupport.client;
import static com.freelasatende.service.impl.WorkanaClientTestSupport.configFor;
import static com.freelasatende.service.impl.WorkanaClientTestSupport.responder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkanaClientErroEnvioTest {

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
    void enviarPerguntaClassificaLimiteSemanalDeConexoesWorkana() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/messages/bid/projeto-sem-conexoes/", exchange ->
            responder(exchange, 403, """
                Poxa, voce nao tem mais conexoes disponiveis.
                Voce enviou todas as conexoes possiveis da semana.
                Melhore seu plano.
                """));
        server.start();

        Path cookies = tempDir.resolve("workana-cookies.json");
        Files.writeString(cookies, """
            {"_workana_session": "sessao"}
            """);

        WorkanaClient client = client(configFor(server, cookies));

        MarketplaceAdapterException exception = assertThrows(
            MarketplaceAdapterException.class,
            () -> client.enviarPergunta("projeto-sem-conexoes", "Pergunta segura")
        );

        assertEquals(MarketplaceAdapterErrorType.LIMITE_CONEXOES, exception.getType());
        assertTrue(exception.getMessage().contains("conexoes"));
    }
}
