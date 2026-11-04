package com.freelasatende.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prova por testes o bypass de origem local por cabecalho forjado:
 * um cliente REMOTO que envia X-Forwarded-For/X-Real-IP com valor local
 * passa no ControleApiAuthInterceptor como se fosse origem local.
 *
 * Casos bloqueados hoje estao marcados com @Disabled("ACHADO-FA-SEC-01-n: ...")
 * porque descrevem o comportamento CORRETO, ainda nao implementado.
 */
class ControleApiAuthInterceptorSpoofingTest {

    private static final String IP_REMOTO = "203.0.113.10";
    private static final String TOKEN_TESTE = "segredo-de-teste";

    @Test
    @DisplayName("X-Forwarded-For forjado com 127.0.0.1 nao autoriza origem remota")
    void xForwardedForLocalForjadoNaoAutorizaOrigemRemota() throws Exception {
        ControleApiAuthInterceptor interceptor = new ControleApiAuthInterceptor("");
        MockHttpServletRequest request = requisicaoRemota();
        request.addHeader("X-Forwarded-For", "127.0.0.1");

        assertOrigemRemotaBloqueada(interceptor, request);
    }

    @Test
    @DisplayName("Proxy acrecentando IP real apos o forjado nao autoriza origem remota")
    void proxyAcrecentandoIpRealNaoAutorizaOrigemRemota() throws Exception {
        ControleApiAuthInterceptor interceptor = new ControleApiAuthInterceptor("");
        MockHttpServletRequest request = requisicaoRemota();
        request.addHeader("X-Forwarded-For", "127.0.0.1, " + IP_REMOTO);

        assertOrigemRemotaBloqueada(interceptor, request);
    }

    @Test
    @DisplayName("X-Forwarded-For forjado com ::1 nao autoriza origem remota")
    void xForwardedForIpv6LocalForjadoNaoAutorizaOrigemRemota() throws Exception {
        ControleApiAuthInterceptor interceptor = new ControleApiAuthInterceptor("");
        MockHttpServletRequest request = requisicaoRemota();
        request.addHeader("X-Forwarded-For", "::1");

        assertOrigemRemotaBloqueada(interceptor, request);
    }

    @Test
    @DisplayName("X-Forwarded-For forjado com localhost nao autoriza origem remota")
    void xForwardedForNomeHostLocalForjadoNaoAutorizaOrigemRemota() throws Exception {
        ControleApiAuthInterceptor interceptor = new ControleApiAuthInterceptor("");
        MockHttpServletRequest request = requisicaoRemota();
        request.addHeader("X-Forwarded-For", "localhost");

        assertOrigemRemotaBloqueada(interceptor, request);
    }

    @Test
    @DisplayName("X-Real-IP forjado com 127.0.0.1 nao autoriza origem remota")
    void xRealIpLocalForjadoNaoAutorizaOrigemRemota() throws Exception {
        ControleApiAuthInterceptor interceptor = new ControleApiAuthInterceptor("");
        MockHttpServletRequest request = requisicaoRemota();
        request.addHeader("X-Real-IP", "127.0.0.1");

        assertOrigemRemotaBloqueada(interceptor, request);
    }

    @Test
    @DisplayName("X-Forwarded-For com IP remoto na primeira posicao nao autoriza origem remota")
    void xForwardedForRemotoNaoAutorizaOrigemRemota() throws Exception {
        ControleApiAuthInterceptor interceptor = new ControleApiAuthInterceptor("");
        MockHttpServletRequest request = requisicaoRemota();
        request.addHeader("X-Forwarded-For", IP_REMOTO);

        assertOrigemRemotaBloqueada(interceptor, request);
    }

    @Test
    @DisplayName("Token configurado vazio rejeita X-Control-Token vazio")
    void tokenConfiguradoVazioRejeitaHeaderTokenVazio() throws Exception {
        ControleApiAuthInterceptor interceptor = new ControleApiAuthInterceptor("");
        MockHttpServletRequest request = requisicaoRemota();
        request.addHeader("X-Control-Token", "");

        assertOrigemRemotaBloqueada(interceptor, request);
    }

    @Test
    @DisplayName("Token errado com prefixo Bearer nao autoriza origem remota")
    void bearerTokenErradoNaoAutorizaOrigemRemota() throws Exception {
        ControleApiAuthInterceptor interceptor = new ControleApiAuthInterceptor(TOKEN_TESTE);
        MockHttpServletRequest request = requisicaoRemota();
        request.addHeader("Authorization", "Bearer valor-errado");

        assertOrigemRemotaBloqueada(interceptor, request);
    }

    @Test
    @DisplayName("Bearer sem espaco apos o prefixo nao autoriza origem remota")
    void bearerSemEspacoNaoAutorizaOrigemRemota() throws Exception {
        ControleApiAuthInterceptor interceptor = new ControleApiAuthInterceptor(TOKEN_TESTE);
        MockHttpServletRequest request = requisicaoRemota();
        request.addHeader("Authorization", "Bearer");

        assertOrigemRemotaBloqueada(interceptor, request);
    }

    @Test
    @DisplayName("Bearer sem valor apos o espaco nao autoriza origem remota")
    void bearerSemValorNaoAutorizaOrigemRemota() throws Exception {
        ControleApiAuthInterceptor interceptor = new ControleApiAuthInterceptor(TOKEN_TESTE);
        MockHttpServletRequest request = requisicaoRemota();
        request.addHeader("Authorization", "Bearer ");

        assertOrigemRemotaBloqueada(interceptor, request);
    }

    @Test
    @DisplayName("Origem local legitima sem cabecalhos continua permitida")
    void origemLocalLegitimaSemCabecalhosEPermitida() throws Exception {
        ControleApiAuthInterceptor interceptor = new ControleApiAuthInterceptor("");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
    }

    private MockHttpServletRequest requisicaoRemota() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(IP_REMOTO);
        return request;
    }

    private void assertOrigemRemotaBloqueada(
        ControleApiAuthInterceptor interceptor,
        MockHttpServletRequest request
    ) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean permitido = interceptor.preHandle(request, response, new Object());

        assertEquals(false, permitido, "Origem remota sem token valido deve ser bloqueada");
        assertEquals(403, response.getStatus());
    }
}
