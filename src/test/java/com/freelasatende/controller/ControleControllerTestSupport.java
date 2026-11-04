package com.freelasatende.controller;

import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.AprendizadoRepository;
import com.freelasatende.repository.ConfiguracaoRepository;
import com.freelasatende.repository.InboxAuditoriaRegistroRepository;
import com.freelasatende.repository.InboxConversaAuditoriaRegistroRepository;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.repository.MensagemClienteRegistroRepository;
import com.freelasatende.repository.ProjetoRepository;
import com.freelasatende.service.impl.TelegramServiceImpl;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.ScraperService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
    "scheduling.enabled=false",
    "ia.memoria-arquivo=target/test-memoria-controle.json",
    "real.write.enabled=true",
    "real.write.simulate=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ControleControllerTestSupport {

    protected static final Path MEMORIA_CONTROLE = Path.of("target/test-memoria-controle.json");
    protected static final Path AUDITORIA_INBOX_DIR = Path.of("target/copiloto-seguro-inbox");
    protected static final Path AUDITORIA_INBOX_HTML = AUDITORIA_INBOX_DIR.resolve("index.html");
    protected static final Path AUDITORIA_INBOX_JSON = AUDITORIA_INBOX_DIR.resolve("auditoria.json");
    protected static final Path AUDITORIA_INBOX_RESUMO = AUDITORIA_INBOX_DIR.resolve("resumo.json");

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected AcaoPendenteRepository acaoPendenteRepository;

    @Autowired
    protected MensagemClienteRegistroRepository mensagemClienteRegistroRepository;

    @Autowired
    protected InboxAuditoriaRegistroRepository inboxAuditoriaRegistroRepository;

    @Autowired
    protected InboxConversaAuditoriaRegistroRepository inboxConversaAuditoriaRegistroRepository;

    @Autowired
    protected ConfiguracaoRepository configuracaoRepository;

    @Autowired
    protected InteracaoRepository interacaoRepository;

    @Autowired
    protected ProjetoRepository projetoRepository;

    @Autowired
    protected AprendizadoRepository aprendizadoRepository;

    @MockBean
    protected TelegramServiceImpl telegramService;

    @MockBean
    protected ScraperService scraperService;

    @MockBean
    protected AutenticacaoService autenticacaoService;

    @MockBean
    protected GerenciadorConexoesService gerenciadorConexoesService;

    @MockBean
    protected AvaliadorIaService avaliadorIaService;

    @BeforeEach
    @Transactional
    void setUp() {
        limparEstado();
        reset(telegramService, scraperService, autenticacaoService, gerenciadorConexoesService, avaliadorIaService);

        when(autenticacaoService.verificarSessaoValida()).thenReturn(true);
        when(scraperService.verificarLogin()).thenReturn(true);
        when(gerenciadorConexoesService.getStatusConexoes()).thenReturn("900 conexões disponíveis");
        // O envio real agora reserva a conexao antes (update atomico); a fixture
        // libera a reserva para os cenarios exercitarem o caminho de envio.
        when(gerenciadorConexoesService.reservarConexao()).thenReturn(true);
        when(avaliadorIaService.obterDiagnosticoMemoriaIa()).thenReturn(Map.of("saude", "OK"));
        when(avaliadorIaService.reescreverRascunhoComOrientacao(anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(avaliadorIaService.reavaliarProjeto(any()))
            .thenReturn(com.freelasatende.model.dto.AvaliacaoResultado.builder().capaz(true).build());
        when(scraperService.enviarPergunta(anyLong(), anyString())).thenReturn(true);
    }

    @AfterEach
    @Transactional
    void tearDown() {
        limparEstado();
    }

    protected AcaoPendente salvarAcao(String mensagem) {
        return acaoPendenteRepository.save(AcaoPendente.builder()
            .projetoId(44L)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Landing page")
            .resumo("Confirmar prazo antes de responder")
            .payloadJson("""
                {
                  "projetoId":44,
                  "tituloProjeto":"Landing page",
                  "mensagem":"%s",
                  "nivelAutonomia":"PERGUNTA_CLIENTE",
                  "entendimentoIa":"Projeto possivel, mas falta confirmar prazo.",
                  "analiseIa":"Li o projeto e encontrei falta de prazo.",
                  "opcoesIa":["Responder a IA antes de enviar"],
                  "atitudeSugerida":"Conversar com voce antes de perguntar ao cliente.",
                  "conversaEnsinoObrigatoria":true,
                  "informacoesConfirmadas":["Titulo: Landing page"],
                  "informacoesPendentes":["Confirmar prazo antes da proposta."],
                  "sabeFazer":["rascunhar pergunta curta"],
                  "duvidasInternas":["confirmar se prazo e requisito bloqueante"],
                  "perguntasParaCliente":["Consegue confirmar o prazo ideal?"],
                  "riscosDecisao":["proposta sem prazo pode ficar imprecisa"],
                  "proximaAcaoSugerida":"Perguntar ao cliente se voce aprovar.",
                  "perguntaParaUsuario":"Essa pergunta esta certa para este projeto?",
                  "regraAprendizadoSugerida":"Quando faltar prazo, perguntar antes da proposta."
                }
                """.formatted(mensagem))
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build());
    }

    protected AcaoPendente salvarAcaoConversa() {
        return acaoPendenteRepository.save(AcaoPendente.builder()
            .projetoId(44L)
            .conversaId(77L)
            .tipo(TipoAcaoPendente.RESPONDER_CONVERSA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.ENSINAMENTO)
            .tituloProjeto("Landing page")
            .resumo("Responder cliente")
            .payloadJson("""
                {
                  "conversaId":77,
                  "projetoId":44,
                  "tituloProjeto":"Landing page",
                  "mensagem":"Oi, consigo seguir por aqui.",
                  "mensagemGatilhoId":102,
                  "mensagemGatilhoTexto":"Consegue confirmar o prazo?",
                  "mensagemGatilhoAutor":"CLIENTE",
                  "mensagemGatilhoNome":"Cliente",
                  "idCliente":1613360,
                  "nomeCliente":"Cliente",
                  "idFreelancer":269409,
                  "nomeFreelancer":"Vitor Camillo",
                  "conversaEnsinoObrigatoria":true,
                  "duvidasInternas":["confirmar se o prazo precisa ser perguntado antes de responder"],
                  "perguntaParaUsuario":"Essa resposta esta certa para este cliente?",
                  "regraAprendizadoSugerida":"Quando cliente pedir prazo, validar antes de prometer.",
                  "ultimasMensagensMinhas":[{"id":101,"autor":"EU","nome":"Vitor Camillo","texto":"Oi, estou avaliando.","dataCriacaoMillis":1000}],
                  "ultimasMensagensCliente":[{"id":102,"autor":"CLIENTE","nome":"Cliente","texto":"Consegue confirmar o prazo?","dataCriacaoMillis":2000}],
                  "mensagensIgnoradasResumo":["1 mensagem minha detectada: entra no contexto, mas nao aciona resposta."],
                  "autoriaConfiavel":true,
                  "motivoAutoria":"Autoria definida por idFreelancer/idCliente da conversa."
                }
                """)
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build());
    }

    private void limparEstado() {
        inboxConversaAuditoriaRegistroRepository.deleteAll();
        inboxAuditoriaRegistroRepository.deleteAll();
        mensagemClienteRegistroRepository.deleteAll();
        acaoPendenteRepository.deleteAll();
        aprendizadoRepository.deleteAll();
        configuracaoRepository.deleteAll();
        interacaoRepository.deleteAll();
        projetoRepository.deleteAll();
        try {
            Files.deleteIfExists(MEMORIA_CONTROLE);
            Files.deleteIfExists(AUDITORIA_INBOX_HTML);
            Files.deleteIfExists(AUDITORIA_INBOX_JSON);
            Files.deleteIfExists(AUDITORIA_INBOX_RESUMO);
        } catch (Exception ignored) {
        }
    }
}
