package com.freelasatende.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControleApiAuthInterceptorTest {

    @Test
    void devePermitirOrigemLocalSemToken() throws Exception {
        ControleApiAuthInterceptor interceptor = new ControleApiAuthInterceptor("");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
    }

    @Test
    void deveBloquearOrigemRemotaSemToken() throws Exception {
        ControleApiAuthInterceptor interceptor = new ControleApiAuthInterceptor("");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean permitido = interceptor.preHandle(request, response, new Object());

        assertEquals(false, permitido);
        assertEquals(403, response.getStatus());
    }

    @Test
    void devePermitirOrigemRemotaComHeaderToken() throws Exception {
        ControleApiAuthInterceptor interceptor = new ControleApiAuthInterceptor("segredo");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-Control-Token", "segredo");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void devePermitirOrigemRemotaComBearerToken() throws Exception {
        ControleApiAuthInterceptor interceptor = new ControleApiAuthInterceptor("segredo");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("Authorization", "Bearer segredo");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void deveBloquearXForwardedForRemotoMesmoComRemoteAddrLocal() throws Exception {
        ControleApiAuthInterceptor interceptor = new ControleApiAuthInterceptor("");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.10, 127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean permitido = interceptor.preHandle(request, response, new Object());

        assertEquals(false, permitido);
        assertEquals(403, response.getStatus());
    }

    @Test
    void devePermitirRotaSseDeLogsWithQueryParamTokenValido() throws Exception {
        ControleApiAuthInterceptor interceptor = new ControleApiAuthInterceptor("segredo");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.setRequestURI("/api/dashboard/logs/stream");
        request.setParameter("token", "segredo");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
    }

    @Test
    void deveBloquearRotaSseComQueryParamTokenErrado() throws Exception {
        ControleApiAuthInterceptor interceptor = new ControleApiAuthInterceptor("segredo");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.setRequestURI("/api/dashboard/logs/stream");
        request.setParameter("token", "valor-errado");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean permitido = interceptor.preHandle(request, response, new Object());

        assertEquals(false, permitido);
        assertEquals(403, response.getStatus());
    }

    @Test
    void deveBloquearOutraRotaComQueryParamTokenMesmoValido() throws Exception {
        ControleApiAuthInterceptor interceptor = new ControleApiAuthInterceptor("segredo");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.setRequestURI("/api/dashboard/stats");
        request.setParameter("token", "segredo");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean permitido = interceptor.preHandle(request, response, new Object());

        assertEquals(false, permitido);
        assertEquals(403, response.getStatus());
    }

    @Test
    void deveBloquearRotaSseSemQueryParamQuandoTokenNaoConfigurado() throws Exception {
        ControleApiAuthInterceptor interceptor = new ControleApiAuthInterceptor("");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.setRequestURI("/api/dashboard/logs/stream");
        request.setParameter("token", "qualquer");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean permitido = interceptor.preHandle(request, response, new Object());

        assertEquals(false, permitido);
        assertEquals(403, response.getStatus());
    }
}
