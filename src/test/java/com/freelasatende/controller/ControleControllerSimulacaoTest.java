package com.freelasatende.controller;

import com.freelasatende.model.entity.MensagemClienteRegistro;
import com.freelasatende.model.enums.StatusMensagemCliente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.AprendizadoRepository;
import com.freelasatende.repository.ConfiguracaoRepository;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.repository.MensagemClienteRegistroRepository;
import com.freelasatende.service.impl.TelegramServiceImpl;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.ScraperService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "scheduling.enabled=false",
    "ia.memoria-arquivo=target/test-memoria-controle-simulacao.json",
    "real.write.enabled=false",
    "real.write.simulate=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ControleControllerSimulacaoTest {

    private static final Path MEMORIA_CONTROLE = Path.of("target/test-memoria-controle-simulacao.json");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcaoPendenteRepository acaoPendenteRepository;

    @Autowired
    private MensagemClienteRegistroRepository mensagemClienteRegistroRepository;

    @Autowired
    private ConfiguracaoRepository configuracaoRepository;

    @Autowired
    private InteracaoRepository interacaoRepository;

    @Autowired
    private AprendizadoRepository aprendizadoRepository;

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
    @Transactional
    void setUp() {
        mensagemClienteRegistroRepository.deleteAll();
        acaoPendenteRepository.deleteAll();
        aprendizadoRepository.deleteAll();
        configuracaoRepository.deleteAll();
        interacaoRepository.deleteAll();
        try {
            Files.deleteIfExists(MEMORIA_CONTROLE);
        } catch (Exception ignored) {}
        reset(telegramService, scraperService, autenticacaoService, gerenciadorConexoesService, avaliadorIaService);

        when(autenticacaoService.verificarSessaoValida()).thenReturn(true);
        when(scraperService.verificarLogin()).thenReturn(true);
        when(gerenciadorConexoesService.getStatusConexoes()).thenReturn("900 conexoes disponiveis");
        when(avaliadorIaService.obterDiagnosticoMemoriaIa()).thenReturn(Map.of("saude", "OK"));
    }

    @AfterEach
    @Transactional
    void tearDown() {
        mensagemClienteRegistroRepository.deleteAll();
        acaoPendenteRepository.deleteAll();
        aprendizadoRepository.deleteAll();
        configuracaoRepository.deleteAll();
        interacaoRepository.deleteAll();
        try {
            Files.deleteIfExists(MEMORIA_CONTROLE);
        } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("Overview expõe simulação como operável sem escrita real")
    void overviewExpoeModoSimulacaoComoOperavelSemEscritaReal() throws Exception {
        mensagemClienteRegistroRepository.save(MensagemClienteRegistro.builder()
            .projetoId(77L)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .fingerprint("fingerprint-simulado")
            .textoPreview("Oi, voce consegue confirmar o prazo ideal?")
            .status(StatusMensagemCliente.SIMULADA)
            .motivoBloqueio("Envio simulado: nenhuma chamada real foi feita ao cliente")
            .data(LocalDateTime.now())
            .build());

        mockMvc.perform(get("/api/controle/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.podeOperar").value(true))
            .andExpect(jsonPath("$.escritaRealHabilitada").value(false))
            .andExpect(jsonPath("$.simulacaoEnvioHabilitada").value(true))
            .andExpect(jsonPath("$.motivoOperacao").value(org.hamcrest.Matchers.containsString("simulacao")))
            .andExpect(jsonPath("$.totalEnvios").value(0))
            .andExpect(jsonPath("$.totalEnviosSimulados").value(1))
            .andExpect(jsonPath("$.simulacoesRecentes[0].status").value("SIMULADA"));
    }

    @Test
    @DisplayName("Dashboard ops expõe trava ativa em H2/simulação")
    void dashboardOpsExpoeTravaAtivaEmSimulacao() throws Exception {
        mockMvc.perform(get("/api/dashboard/ops"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.modoSeguro").value(true))
            .andExpect(jsonPath("$.escrita.realHabilitada").value(false))
            .andExpect(jsonPath("$.escrita.simulacaoHabilitada").value(true))
            .andExpect(jsonPath("$.escrita.travaAtiva").value(true))
            .andExpect(jsonPath("$.alertas[0].nivel").value("OK"));
    }
}
