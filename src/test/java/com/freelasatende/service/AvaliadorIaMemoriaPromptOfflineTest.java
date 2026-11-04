package com.freelasatende.service;

import com.freelasatende.model.dto.AvaliacaoResultado;
import com.freelasatende.model.entity.Aprendizado;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.MotivoAprendizado;
import com.freelasatende.model.enums.StatusRegraAprendida;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.repository.AprendizadoRepository;
import com.freelasatende.service.interfaceServico.AprendizadoService;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.IaProvider;
import com.freelasatende.service.interfaceServico.UrlFetcherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
    "scheduling.enabled=false",
    "ia.api-key=",
    "ia.base-url=https://api.local.invalid",
    "ia.model=test-local",
    "ia.memoria-arquivo=target/test-memoria-avaliador-prompt.json"
})
@ActiveProfiles("test")
@DisplayName("Avaliador IA: memoria aprendida entra no prompt offline")
class AvaliadorIaMemoriaPromptOfflineTest {

    private static final Path MEMORIA_TESTE = Path.of("target/test-memoria-avaliador-prompt.json");

    @Autowired
    private AvaliadorIaService avaliadorIaService;

    @Autowired
    private AprendizadoService aprendizadoService;

    @Autowired
    private AprendizadoRepository aprendizadoRepository;

    @MockBean
    private IaProvider iaProvider;

    @MockBean
    private UrlFetcherService urlFetcherService;

    @BeforeEach
    void setUp() throws Exception {
        aprendizadoRepository.deleteAll();
        Files.deleteIfExists(MEMORIA_TESTE);
        when(iaProvider.enviar(anyString(), anyInt())).thenReturn("SIM");
        when(iaProvider.getNome()).thenReturn("FakeIA");
        when(iaProvider.getModelo()).thenReturn("test-offline");
        when(iaProvider.estaDisponivel()).thenReturn(true);
        when(urlFetcherService.buscarContextoDeUrls(anyString())).thenReturn("");
    }

    @AfterEach
    void tearDown() throws Exception {
        aprendizadoRepository.deleteAll();
        Files.deleteIfExists(MEMORIA_TESTE);
    }

    @Test
    @DisplayName("Regra ensinada fica no JSON e no prompt enviado ao provider")
    void regraEnsinadaFicaNoJsonENoPromptEnviadoAoProvider() throws Exception {
        aprendizadoService.salvar(Aprendizado.builder()
            .data(LocalDateTime.now())
            .projetoId(81_001L)
            .tituloProjeto("Painel legado")
            .foiAprovado(true)
            .modoUsado(ModoSistema.ENSINAMENTO)
            .feedbackUsuario("Regra ensinada pelo usuario")
            .regrasAprendidas("Nunca enviar proposta antes de confirmar acesso ao painel legado.")
            .motivoAprendizado(MotivoAprendizado.ESCOPO_CLARO)
            .tipoAcao(TipoAcaoPendente.ENVIAR_PROPOSTA)
            .statusRegra(StatusRegraAprendida.CONFIRMADA)
            .origemFeedback("TESTE_PROMPT")
            .build());

        AvaliacaoResultado resultado = avaliadorIaService.avaliarProjeto(Projeto.builder()
            .id(81_100L)
            .titulo("Ajuste em painel legado")
            .descricao("Preciso ajustar uma tela em painel existente.")
            .categoria("Web, Mobile & Software")
            .subcategoria("Desenvolvimento Web")
            .orcamento(BigDecimal.valueOf(900))
            .build());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(iaProvider).enviar(promptCaptor.capture(), anyInt());

        String prompt = promptCaptor.getValue();
        String memoria = Files.readString(MEMORIA_TESTE);
        Map<String, Object> diagnostico = avaliadorIaService.obterDiagnosticoMemoriaIa();

        assertTrue(resultado.isCapaz());
        assertTrue(memoria.contains("Nunca enviar proposta antes de confirmar acesso ao painel legado."));
        assertTrue(prompt.contains("REGRAS DE AVALIAÇÃO CONSOLIDADAS PELO SEU APRENDIZADO"));
        assertTrue(prompt.contains("Nunca enviar proposta antes de confirmar acesso ao painel legado."));
        assertTrue(prompt.contains("Ajuste em painel legado"));
        assertEquals(MEMORIA_TESTE.toAbsolutePath().toString(), diagnostico.get("arquivoMemoriaPath"));
    }
}
