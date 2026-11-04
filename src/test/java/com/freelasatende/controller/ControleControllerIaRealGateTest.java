package com.freelasatende.controller;

import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.service.impl.TelegramServiceImpl;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.ScraperService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "scheduling.enabled=false",
    "real.ia.enabled=true",
    "ia.api-key=",
    "real.write.enabled=false",
    "real.write.simulate=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ControleControllerIaRealGateTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcaoPendenteRepository acaoPendenteRepository;

    @MockBean
    private TelegramServiceImpl telegramService;

    @MockBean
    private ScraperService scraperService;

    @MockBean
    private AutenticacaoService autenticacaoService;

    @MockBean
    private GerenciadorConexoesService gerenciadorConexoesService;

    @MockBean
    private AvaliadorIaService avaliadorIaService;

    @BeforeEach
    void setUp() {
        acaoPendenteRepository.deleteAll();
        reset(telegramService, scraperService, autenticacaoService, gerenciadorConexoesService, avaliadorIaService);
        when(avaliadorIaService.obterDiagnosticoMemoriaIa()).thenReturn(Map.of("apiKeyConfigurada", false));
        when(scraperService.listarConversasInbox()).thenReturn(List.of(ConversaResumo.builder()
            .idConversa(77L)
            .idProjeto(88L)
            .nomeProjeto("Projeto real")
            .build()));
        when(scraperService.listarMensagensConversa(77L, false)).thenReturn(List.of());
    }

    @Test
    @DisplayName("IA real sem API key aborta sincronização antes de criar ação")
    void iaRealSemApiKeyAbortaSincronizacaoAntesDeCriarAcao() throws Exception {
        mockMvc.perform(post("/api/controle/sincronizar-inbox"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sucesso").value(false))
            .andExpect(jsonPath("$.mensagem").value(org.hamcrest.Matchers.containsString("IA_API_KEY")));

        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
    }
}
