package com.freelasatende.controller;

import com.freelasatende.service.interfaceServico.ControleOperacionalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova o bypass por cabecalho forjado no wiring real: interceptor registrado
 * em /api/controle/** via WebMvcConfig + ControleController.
 * O servico de controle e mockado para verificar que ele NUNCA e acionado
 * por uma requisicao remota que deveria ser bloqueada.
 */
class ControleApiSpoofingWiringTest extends ControleControllerTestSupport {

    private static final String IP_REMOTO = "203.0.113.10";

    @MockBean
    private ControleOperacionalService controleOperacionalService;

    @Test
    @DisplayName("Aprovacao remota sem token e sem cabecalhos e bloqueada sem acionar o servico")
    void aprovacaoRemotaSemTokenEBloqueadaSemAcionarServico() throws Exception {
        mockMvc.perform(post("/api/controle/acoes/{id}/aprovar", 1L)
                .with(request -> {
                    request.setRemoteAddr(IP_REMOTO);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comentario\":\"tentativa remota sem token\"}"))
            .andExpect(status().isForbidden());

        verify(controleOperacionalService, never()).aprovarAcao(anyLong(), anyString());
    }

    @Test
    @DisplayName("Aprovacao remota com X-Forwarded-For forjado deve ser bloqueada sem acionar o servico")
    void aprovacaoRemotaComXForwardedForForjadoDeveSerBloqueada() throws Exception {
        mockMvc.perform(post("/api/controle/acoes/{id}/aprovar", 1L)
                .with(request -> {
                    request.setRemoteAddr(IP_REMOTO);
                    return request;
                })
                .header("X-Forwarded-For", "127.0.0.1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comentario\":\"bypass por X-Forwarded-For forjado\"}"))
            .andExpect(status().isForbidden());

        verify(controleOperacionalService, never()).aprovarAcao(anyLong(), anyString());
    }

    @Test
    @DisplayName("Aprovacao local legitima alcanca o servico de controle (controle do wiring)")
    void aprovacaoLocalLegitimaAlcancaServicoDeControle() throws Exception {
        mockMvc.perform(post("/api/controle/acoes/{id}/aprovar", 1L)
                .with(request -> {
                    request.setRemoteAddr("127.0.0.1");
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comentario\":\"aprovacao local legitima\"}"))
            .andExpect(status().isOk());

        verify(controleOperacionalService).aprovarAcao(anyLong(), anyString());
    }
}
