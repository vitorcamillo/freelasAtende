package com.freelasatende.service.impl;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.freelasatende.service.impl.WorkanaErrorClassifier.tipoErroEnvio;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkanaErrorClassifierTest {

    @Test
    void tipoErroEnvioClassificaLimiteConexoesComAcentos() {
        MarketplaceAdapterErrorType tipo = tipoErroEnvio(new Resposta(
            403,
            """
                Você não tem mais conexões disponíveis.
                Contatos semanais disponíveis esgotados.
                Plano de benefícios recomendado.
                """
        ));

        assertEquals(MarketplaceAdapterErrorType.LIMITE_CONEXOES, tipo);
    }

    @Test
    void tipoErroEnvioClassificaVariacaoLimiteConexoes() {
        // Teste para novo padrão: "nao tem mais conexoes" (sem "disponiveis")
        MarketplaceAdapterErrorType tipo1 = tipoErroEnvio(new Resposta(
            403,
            "Você não tem mais conexões"
        ));
        assertEquals(MarketplaceAdapterErrorType.LIMITE_CONEXOES, tipo1);

        // Teste para novo padrão: "limite semanal"
        MarketplaceAdapterErrorType tipo2 = tipoErroEnvio(new Resposta(
            403,
            "Você atingiu o limite semanal de contatos"
        ));
        assertEquals(MarketplaceAdapterErrorType.LIMITE_CONEXOES, tipo2);

        // Teste para novo padrão: "aumente seu plano"
        MarketplaceAdapterErrorType tipo3 = tipoErroEnvio(new Resposta(
            403,
            "Aumente seu plano para continuar"
        ));
        assertEquals(MarketplaceAdapterErrorType.LIMITE_CONEXOES, tipo3);
    }

    @Test
    void tipoErroEnvioMantemAcessoNegadoComoOperacaoIndisponivel() {
        MarketplaceAdapterErrorType tipo = tipoErroEnvio(new Resposta(
            403,
            "Acesso Negado Voce nao possui autorizacao para acessar esse conteudo"
        ));

        assertEquals(MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL, tipo);
    }

    private record Resposta(int statusCode, String body) implements HttpResponse<String> {

        @Override
        public HttpRequest request() {
            return HttpRequest.newBuilder(URI.create("http://workana.local")).build();
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.<String, List<String>>of(), (nome, valor) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("http://workana.local");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
