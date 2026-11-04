package com.freelasatende.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.dto.AvaliacaoResultado;
import com.freelasatende.model.dto.ConexoesDisponiveis;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Cliente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.StatusMensagemCliente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.repository.MensagemClienteRegistroRepository;
import com.freelasatende.repository.ProjetoRepository;
import com.freelasatende.service.impl.AutoAtendimentoServiceImpl;
import com.freelasatende.service.interfaceServico.AprendizadoService;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.ScraperService;
import com.freelasatende.service.interfaceServico.TelegramService;
import com.freelasatende.service.interfaceServico.HistoricoService;
import com.freelasatende.service.impl.TelegramServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;

/**
 * Testes de integração para AutoAtendimentoService.
 * Usa SpringBootTest com escrita externa mockada.
 */
@SpringBootTest(properties = {
    "scheduling.enabled=false",
    "ia.memoria-arquivo=target/test-memoria-autoatendimento.json"
})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AutoAtendimentoServiceImplTestSupport {

    @Autowired
    protected AutoAtendimentoServiceImpl autoAtendimentoService;

    @MockBean
    protected ScraperService scraperService;

    @MockBean
    protected AvaliadorIaService avaliadorIaService;

    @MockBean
    protected TelegramServiceImpl telegramService;

    @MockBean
    protected GerenciadorConexoesService gerenciadorConexoesService;

    @Autowired
    protected HistoricoService historicoService;

    @Autowired
    protected AprendizadoService aprendizadoService;

    @Autowired
    protected AcaoPendenteRepository acaoPendenteRepository;

    @Autowired
    protected InteracaoRepository interacaoRepository;

    @Autowired
    protected MensagemClienteRegistroRepository mensagemClienteRegistroRepository;

    @Autowired
    protected ProjetoRepository projetoRepository;

    @Autowired
    protected ObjectMapper objectMapper;

    protected final AtomicLong sequencia = new AtomicLong(System.currentTimeMillis() % 10000);

    @DynamicPropertySource
    static void propriedadesSemCredenciaisExternas(DynamicPropertyRegistry registry) {
        registry.add("ia.api-key", () -> "");
        registry.add("ia.base-url", () -> "https://api.local.invalid");
        registry.add("ia.model", () -> "test-local");
        registry.add("freelas.email", () -> "");
        registry.add("freelas.senha", () -> "");
        registry.add("telegram.bot-token", () -> "");
        registry.add("telegram.chat-id", () -> "");
    }

    @BeforeEach
    @Transactional
    void prepararDados() {
        acaoPendenteRepository.deleteAll();
        interacaoRepository.deleteAll();
        mensagemClienteRegistroRepository.deleteAll();
        projetoRepository.deleteAll();
        reset(scraperService, avaliadorIaService, telegramService, gerenciadorConexoesService);
        ReflectionTestUtils.setField(autoAtendimentoService, "realWriteEnabled", false);
        ReflectionTestUtils.setField(autoAtendimentoService, "realWriteSimulateEnabled", false);

        // O envio real agora reserva a conexao antes (update atomico); a fixture
        // libera a reserva para os cenarios exercitarem o caminho de envio.
        when(gerenciadorConexoesService.reservarConexao()).thenReturn(true);

        when(scraperService.buscarDetalhesProjeto(anyLong(), nullable(String.class)))
            .thenAnswer(invocation -> {
                Long id = invocation.getArgument(0);
                Projeto projeto = projetoBase();
                projeto.setId(id);
                return projeto;
            });
        when(scraperService.buscarCliente(anyLong()))
            .thenReturn(Cliente.builder().id(7L).mediaNota(4.7).build());
        when(scraperService.obterConexoesDisponiveis())
            .thenReturn(ConexoesDisponiveis.builder().total(900).renovaveis(100).naoExpiraveis(800).build());
        when(avaliadorIaService.avaliarProjeto(any(Projeto.class)))
            .thenReturn(AvaliacaoResultado.builder().capaz(true).build());
        when(avaliadorIaService.reavaliarProjeto(any(Projeto.class)))
            .thenReturn(AvaliacaoResultado.builder().capaz(true).build());
        when(avaliadorIaService.gerarDuvidas(any(Projeto.class)))
            .thenReturn(List.of("Qual prazo ideal para publicar?"));
        when(avaliadorIaService.sugerirProposta(any(Projeto.class)))
            .thenAnswer(invocation -> {
                Projeto projeto = invocation.getArgument(0);
                return Proposta.builder()
                    .projetoId(projeto.getId())
                    .valorProposto(BigDecimal.valueOf(1200))
                    .valorMinimo(BigDecimal.valueOf(900))
                    .mediaMercado(BigDecimal.valueOf(1100))
                    .build();
            });
        when(avaliadorIaService.gerarTextoProposta(any(Projeto.class), any(Proposta.class)))
            .thenReturn("Oi, consigo cuidar dessa entrega com escopo e prazo bem alinhados pela plataforma.");
        when(avaliadorIaService.sugerirRespostaConversa(any(Projeto.class), anyString(), anyString()))
            .thenReturn("Oi, obrigado pelo retorno. Consigo seguir por aqui e te passo o próximo passo.");
        when(avaliadorIaService.reescreverRascunhoComOrientacao(anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(scraperService.enviarPergunta(anyLong(), anyString())).thenReturn(true);
        when(scraperService.enviarProposta(any(Projeto.class), any(Proposta.class)))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(scraperService.enviarMensagemConversa(anyLong(), anyString()))
            .thenReturn(MensagemConversa.builder().id(555L).idConversa(77L).enviadaPorMim(true).build());
        when(scraperService.cancelarProposta(anyLong())).thenReturn(true);
    }

    protected void habilitarEscritaRealMockada() {
        ReflectionTestUtils.setField(autoAtendimentoService, "realWriteEnabled", true);
        ReflectionTestUtils.setField(autoAtendimentoService, "realWriteSimulateEnabled", false);
    }

    protected void habilitarSimulacaoEnvio() {
        ReflectionTestUtils.setField(autoAtendimentoService, "realWriteEnabled", false);
        ReflectionTestUtils.setField(autoAtendimentoService, "realWriteSimulateEnabled", true);
    }

    @AfterEach
    @Transactional
    void limparDados() {
        mensagemClienteRegistroRepository.deleteAll();
        acaoPendenteRepository.deleteAll();
        interacaoRepository.deleteAll();
    }

    protected AcaoPendente acaoEnsino(
        TipoAcaoPendente tipo,
        StatusAcaoPendente status,
        String titulo,
        String mensagem
    ) {
        Long projetoId = sequencia.incrementAndGet();
        return acaoPendenteRepository.save(AcaoPendente.builder()
            .projetoId(projetoId)
            .tipo(tipo)
            .status(status)
            .modoOrigem(ModoSistema.ENSINAMENTO)
            .tituloProjeto(titulo)
            .resumo("Ação de ensino")
            .payloadJson("{\"projetoId\":" + projetoId
                + ",\"tituloProjeto\":\"" + titulo + "\""
                + ",\"perguntaParaUsuario\":\"Como devo responder?\""
                + ",\"regraAprendizadoSugerida\":\"Confirmar antes de falar com cliente\""
                + ",\"mensagem\":\"" + mensagem + "\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build());
    }

    protected AcaoPendente acaoEnsinoComContexto(String titulo, String contextoProjeto, String mensagem) {
        Long projetoId = sequencia.incrementAndGet();
        return acaoPendenteRepository.save(AcaoPendente.builder()
            .projetoId(projetoId)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.ENSINAMENTO)
            .tituloProjeto(titulo)
            .resumo("Ação de ensino com contexto")
            .payloadJson("{\"projetoId\":" + projetoId
                + ",\"tituloProjeto\":\"" + titulo + "\""
                + ",\"contextoProjeto\":\"Contexto exclusivo " + contextoProjeto + "\""
                + ",\"historicoConversa\":\"Histórico limpo " + contextoProjeto + "\""
                + ",\"mensagem\":\"" + mensagem + "\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build());
    }

    protected AcaoPendente acaoEnsinoMista(TipoAcaoPendente tipo, int indice) {
        Long projetoId = sequencia.incrementAndGet();
        Long conversaId = 70_000L + indice;
        Long mensagemGatilhoId = 80_000L + indice;
        String mensagem = "Oi, voce consegue confirmar o ponto " + indice + "?";
        if (tipo == TipoAcaoPendente.RESPONDER_CONVERSA) {
            when(scraperService.listarMensagensConversa(conversaId)).thenReturn(List.of(MensagemConversa.builder()
                .id(mensagemGatilhoId)
                .idConversa(conversaId)
                .texto("Mensagem atual do cliente " + indice)
                .dataCriacaoMillis(1000L + indice)
                .enviadaPorMim(false)
                .build()));
        }
        String payload = tipo == TipoAcaoPendente.RESPONDER_CONVERSA
            ? "{\"projetoId\":" + projetoId
                + ",\"conversaId\":" + conversaId
                + ",\"mensagemGatilhoId\":" + mensagemGatilhoId
                + ",\"tituloProjeto\":\"Stress misto " + indice + "\""
                + ",\"contextoProjeto\":\"CTX-STRESS-" + indice + "\""
                + ",\"mensagem\":\"" + mensagem + "\""
                + ",\"conversaEnsinoObrigatoria\":true}"
            : "{\"projetoId\":" + projetoId
                + ",\"tituloProjeto\":\"Stress misto " + indice + "\""
                + ",\"contextoProjeto\":\"CTX-STRESS-" + indice + "\""
                + ",\"mensagem\":\"" + mensagem + "\""
                + ",\"conversaEnsinoObrigatoria\":true}";
        return acaoPendenteRepository.save(AcaoPendente.builder()
            .projetoId(projetoId)
            .conversaId(tipo == TipoAcaoPendente.RESPONDER_CONVERSA ? conversaId : null)
            .tipo(tipo)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.ENSINAMENTO)
            .tituloProjeto("Stress misto " + indice)
            .resumo("Ação mista de ensino")
            .payloadJson(payload)
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build());
    }

    protected Projeto projetoBase() {
        return Projeto.builder()
            .id(1L)
            .titulo("Site institucional")
            .descricao("Preciso de um site para minha empresa")
            .categoria("Web")
            .urlProjeto("https://www.99freelas.com.br/project/site-institucional-1")
            .orcamento(BigDecimal.valueOf(1200))
            .dataCriacao(LocalDateTime.now())
            .build();
    }
}
