package com.freelasatende.service;

import com.freelasatende.config.IaProviderFactory;
import com.freelasatende.model.dto.AvaliacaoResultado;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.HistoricoService;
import com.freelasatende.service.interfaceServico.IaProvider;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes de integração para AvaliadorIaService.
 * Usa SpringBootTest sem carregar .env para manter a suite comum deterministica.
 */
@SpringBootTest(properties = "scheduling.enabled=false")
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AvaliadorIaServiceImplTest {

    @Autowired
    private AvaliadorIaService avaliadorIaService;

    @Autowired
    private HistoricoService historicoService;

    @Autowired
    private IaProviderFactory iaProviderFactory;

    @MockBean
    private IaProvider iaProvider;

    @BeforeEach
    void configurarIaFake() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt())).thenReturn("SIM - projeto dentro do escopo");
        when(iaProvider.getNome()).thenReturn("FakeIA");
        when(iaProvider.getModelo()).thenReturn("test-offline");
        when(iaProvider.estaDisponivel()).thenReturn(true);
    }

    @Test
    @DisplayName("Deve retornar falso quando projeto for nulo")
    void deveRetornarFalsoQuandoProjetoForNulo() {
        boolean resultado = avaliadorIaService.somosCapazesDeAtender(null);
        assertFalse(resultado);
    }

    @Test
    @DisplayName("Deve aprovar site simples quando único bloqueio da IA for valor ausente")
    void deveAprovarSiteSimplesQuandoIaRecusaSomentePorValor() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt())).thenReturn("NAO - valor não informado");

        Projeto projeto = Projeto.builder()
            .id(1001L)
            .titulo("Site para empresa de festas infantis")
            .descricao("Mostrar endereço, botão de WhatsApp, formulário de contato e visual moderno.")
            .categoria("Web, Mobile & Software")
            .subcategoria("Desenvolvimento Web")
            .orcamento(null)
            .valorMinimo(BigDecimal.valueOf(50))
            .build();

        AvaliacaoResultado resultado = avaliadorIaService.avaliarProjeto(projeto);

        assertTrue(resultado.isCapaz());
        assertNull(resultado.getMotivoRecusa());
    }

    @Test
    @DisplayName("Deve guardar motivo curto quando IA aprova projeto")
    void deveGuardarMotivoCurtoQuandoIaAprovaProjeto() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt())).thenReturn("SIM - site institucional dentro do escopo");

        AvaliacaoResultado resultado = avaliadorIaService.avaliarProjeto(Projeto.builder()
            .id(1005L)
            .titulo("Site institucional")
            .descricao("Site institucional com páginas, formulário e botão de WhatsApp.")
            .categoria("Web, Mobile & Software")
            .subcategoria("Desenvolvimento Web")
            .orcamento(BigDecimal.valueOf(1200))
            .build());

        assertTrue(resultado.isCapaz());
        assertEquals("site institucional dentro do escopo", resultado.getMotivoInteresse());
    }

    @Test
    @DisplayName("Deve manter recusa quando valor ausente vier junto com falta de expertise")
    void deveManterRecusaQuandoValorAusenteVierComFaltaDeExpertise() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt()))
            .thenReturn("NAO - valor não informado e falta de expertise em Kotlin/Swift para apps nativos");

        Projeto projeto = Projeto.builder()
            .id(1002L)
            .titulo("Aplicativo mobile para motoristas")
            .descricao("Preciso de um app Android e iOS nativo para motoristas.")
            .categoria("Web, Mobile & Software")
            .subcategoria("Desenvolvimento Mobile, Kotlin, Swift")
            .orcamento(null)
            .valorMinimo(BigDecimal.valueOf(50))
            .build();

        AvaliacaoResultado resultado = avaliadorIaService.avaliarProjeto(projeto);

        assertFalse(resultado.isCapaz());
        assertTrue(resultado.getMotivoRecusa().contains("Kotlin/Swift"));
    }

    @Test
    @DisplayName("Deve liberar abordagem inicial para oportunidades web que ainda exigem qualificacao")
    void deveLiberarAbordagemInicialParaProjetosWebQueExigemQualificacao() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt()))
            .thenReturn("NAO - escopo complexo ainda precisa ser detalhado");

        List<Projeto> projetos = List.of(
            Projeto.builder()
                .id(1010L)
                .titulo("Desenvolvimento de site SaaS")
                .descricao("Preciso de um programador para desenvolver um site SaaS com assinatura recorrente.")
                .categoria("Web, Mobile & Software")
                .build(),
            Projeto.builder()
                .id(1011L)
                .titulo("Desenvolvimento de blog de noticias")
                .descricao("Finalizar pagina de noticias e artigos com blog, SEO e integracao de feeds RSS.")
                .categoria("Web, Mobile & Software")
                .build(),
            Projeto.builder()
                .id(1012L)
                .titulo("Integracao WordPress com Airbnb e Booking")
                .descricao("Realizar integracao em site WordPress desenvolvido com Elementor.")
                .categoria("Web, Mobile & Software")
                .build()
        );

        projetos.forEach(projeto ->
            assertTrue(
                avaliadorIaService.avaliarProjeto(projeto).isCapaz(),
                "deveria liberar triagem inicial do projeto " + projeto.getId()
            )
        );
    }

    @Test
    @DisplayName("Deve liberar abordagem inicial quando a única recusa for preço baixo")
    void deveLiberarAbordagemInicialQuandoUnicaRecusaForPrecoBaixo() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt()))
            .thenReturn("NAO - escopo técnico compatível, mas o orçamento de R$ 500 está abaixo do mínimo");

        List<Projeto> projetos = List.of(
            Projeto.builder()
                .id(1015L)
                .titulo("Chatbot com IA para WhatsApp")
                .descricao("Desenvolver chatbot em Python com integração à API do WhatsApp.")
                .categoria("TI e Programação")
                .orcamento(BigDecimal.valueOf(500))
                .build(),
            Projeto.builder()
                .id(1016L)
                .titulo("Integração de gateway de pagamento")
                .descricao("Trocar o provedor de pagamento e homologar a integração com a API.")
                .categoria("TI e Programação")
                .orcamento(BigDecimal.valueOf(1300))
                .build(),
            Projeto.builder()
                .id(1017L)
                .titulo("Implementação de e-commerce")
                .descricao("Desenvolver e-commerce completo com catálogo, carrinho e checkout.")
                .categoria("TI e Programação")
                .orcamento(BigDecimal.valueOf(2500))
                .build()
        );

        projetos.forEach(projeto ->
            assertTrue(
                avaliadorIaService.avaliarProjeto(projeto).isCapaz(),
                "preço não deveria bloquear a abordagem inicial do projeto " + projeto.getId()
            )
        );
    }

    @Test
    @DisplayName("Preço baixo não deve transformar demanda operacional em projeto atendível")
    void precoBaixoNaoDeveLiberarDemandaSemEntregaTecnica() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt()))
            .thenReturn("NAO - orçamento de R$ 250 está abaixo do mínimo");

        Projeto projeto = Projeto.builder()
            .id(1019L)
            .titulo("Cadastro de produtos no TikTok Shop")
            .descricao("Cadastrar 40 produtos, fotos e descrições em uma loja já configurada.")
            .categoria("Suporte Administrativo")
            .orcamento(BigDecimal.valueOf(250))
            .build();

        assertFalse(avaliadorIaService.avaliarProjeto(projeto).isCapaz());
    }

    @Test
    @DisplayName("Prompt deve proibir preço como critério da abordagem inicial")
    void promptDeveProibirPrecoComoCriterioDaAbordagemInicial() throws Exception {
        Projeto projeto = Projeto.builder()
            .id(1018L)
            .titulo("Integração com API")
            .descricao("Desenvolver integração entre o sistema atual e uma API externa.")
            .categoria("TI e Programação")
            .orcamento(BigDecimal.valueOf(250))
            .build();

        avaliadorIaService.avaliarProjeto(projeto);

        var promptCaptor = forClass(String.class);
        verify(iaProvider).enviar(promptCaptor.capture(), anyInt());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("preço NUNCA deve ser usado para recusar"));
        assertFalse(prompt.contains("O valor é justo?"));
    }

    @Test
    @DisplayName("Deve bloquear app web com biometria mesmo quando IA aprovar")
    void deveBloquearAppWebComBiometriaMesmoQuandoIaAprovar() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt()))
            .thenReturn("SIM - app web usa React e Supabase");

        Projeto projeto = Projeto.builder()
            .id(1013L)
            .titulo("Desenvolvedor para finalizar app web e publicar")
            .descricao("App web React e Supabase com verificacao facial, SMS, pagamento e seguranca de dados.")
            .categoria("Web, Mobile & Software")
            .build();

        AvaliacaoResultado resultado = avaliadorIaService.avaliarProjeto(projeto);

        assertFalse(resultado.isCapaz());
        assertTrue(resultado.getMotivoRecusa().contains("risco sensivel"));
        assertNull(resultado.getMotivoInteresse());
    }

    @Test
    @DisplayName("Deve manter automacao n8n fora da abordagem inicial automatica")
    void deveManterAutomacaoN8nForaDaAbordagemInicialAutomatica() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt()))
            .thenReturn("SIM - integracao tecnicamente possivel");

        Projeto projeto = Projeto.builder()
            .id(1014L)
            .titulo("Automacoes RD Station com n8n")
            .descricao("Criar automacoes do CRM RD Station utilizando n8n.")
            .categoria("Web, Mobile & Software")
            .build();

        assertFalse(avaliadorIaService.avaliarProjeto(projeto).isCapaz());
    }

    @Test
    @DisplayName("Deve bloquear nichos nao tecnicos mesmo quando IA aprovar")
    void deveBloquearNichosNaoTecnicosMesmoQuandoIaAprovar() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt()))
            .thenReturn("SIM - oportunidade pode receber abordagem inicial");

        List<Projeto> projetos = List.of(
            Projeto.builder().id(1020L).titulo("Gestao mensal de Meta Ads")
                .descricao("Campanhas mensais de ads para aumentar o trafego do site.").build(),
            Projeto.builder().id(1021L).titulo("Motion designer para criativos")
                .descricao("Criacao de motion design e animacao para redes sociais usando After Effects.").build(),
            Projeto.builder().id(1022L).titulo("Abertura de CNPJ")
                .descricao("Abrir CNPJ e fazer regularizacao fiscal da empresa.").build()
        );

        projetos.forEach(projeto ->
            assertFalse(
                avaliadorIaService.avaliarProjeto(projeto).isCapaz(),
                "deveria bloquear nicho nao tecnico do projeto " + projeto.getId()
            )
        );
        verify(iaProvider, never()).enviar(anyString(), anyInt());
    }

    @Test
    @DisplayName("Falha tecnica do provider nao vira loop de erro tecnico no scheduler")
    void falhaTecnicaDoProviderViraRecusaTemporariaSemExcecao() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt()))
            .thenThrow(new IaProvider.IaProviderException(
                "codex retornou codigo 1; consulte os logs seguros do runtime",
                1,
                true
            ));

        Projeto projeto = Projeto.builder()
            .id(1030L)
            .titulo("Desenvolvimento de site institucional")
            .descricao("Preciso de um programador para desenvolver um site institucional com formulario.")
            .categoria("Web, Mobile & Software")
            .subcategoria("Desenvolvimento Web")
            .build();

        AvaliacaoResultado resultado = avaliadorIaService.avaliarProjeto(projeto);

        assertFalse(resultado.isCapaz());
        assertTrue(resultado.getMotivoRecusa().contains("IA temporariamente indisponivel"));
        assertTrue(resultado.getMotivoRecusa().contains("codex retornou codigo 1"));
    }

    @Test
    @DisplayName("Resposta fora do formato não derruba site simples claramente executável")
    void respostaForaDoFormatoNaoDerrubaSiteSimplesExecutavel() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt())).thenReturn("""
            Let me analyze this project. It is a website with address, WhatsApp button
            and contact form. We can build it with our web stack, but the value is null.
            My assessment leans toward approval.
            """);

        Projeto projeto = Projeto.builder()
            .id(1003L)
            .titulo("Site para empresa de festas infantis")
            .descricao("Preciso de um site com endereço, WhatsApp, formulário de contato e design bonito.")
            .categoria("Web, Mobile & Software")
            .subcategoria("Desenvolvimento Web")
            .orcamento(null)
            .valorMinimo(BigDecimal.valueOf(50))
            .build();

        AvaliacaoResultado resultado = avaliadorIaService.avaliarProjeto(projeto);

        assertTrue(resultado.isCapaz());
    }

    @Test
    @DisplayName("Resposta fora do formato vira recusa clara quando projeto não é baixo risco")
    void respostaForaDoFormatoViraRecusaClaraQuandoProjetoNaoEbaixoRisco() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt())).thenReturn("Let me analyze this project in detail...");

        Projeto projeto = Projeto.builder()
            .id(1004L)
            .titulo("Corrigir reconhecimento de arquivos em agente de IA")
            .descricao("Agente não reconhece arquivos. Sem informar tecnologia, stack ou ambiente.")
            .categoria("Web, Mobile & Software")
            .subcategoria("Inteligência Artificial")
            .orcamento(null)
            .valorMinimo(BigDecimal.valueOf(50))
            .build();

        AvaliacaoResultado resultado = avaliadorIaService.avaliarProjeto(projeto);

        assertFalse(resultado.isCapaz());
        assertEquals("IA respondeu fora do formato de decisão", resultado.getMotivoRecusa());
    }

    @Test
    @DisplayName("Deve sugerir proposta baseada na média do mercado")
    void deveSugerirPropostaBaseadoNaMedia() {
        Projeto projeto = Projeto.builder()
            .id(1L)
            .titulo("Site institucional")
            .orcamento(BigDecimal.valueOf(5000))
            .mediaProposta(BigDecimal.valueOf(4500))
            .valorMinimo(BigDecimal.valueOf(1000))
            .build();

        Proposta proposta = avaliadorIaService.sugerirProposta(projeto);

        assertNotNull(proposta);
        assertEquals(1L, proposta.getProjetoId());
        assertTrue(proposta.getValorProposto().compareTo(BigDecimal.valueOf(4500)) > 0);
        assertEquals(BigDecimal.valueOf(4500), proposta.getMediaMercado());
        assertEquals(BigDecimal.valueOf(1000), proposta.getValorMinimo());
    }

    @Test
    @DisplayName("Deve usar orçamento quando média for nula")
    void deveUsarOrcamentoQuandoMediaForNula() {
        Projeto projeto = Projeto.builder()
            .id(2L)
            .titulo("Landing page")
            .orcamento(BigDecimal.valueOf(2000))
            .mediaProposta(null)
            .build();

        Proposta proposta = avaliadorIaService.sugerirProposta(projeto);

        assertNotNull(proposta);
        assertEquals(BigDecimal.valueOf(2000), proposta.getValorProposto());
    }

    @Test
    @DisplayName("Deve sugerir proposta mínima quando tudo for nulo")
    void deveSugerirMinimaQuandoTudoForNulo() {
        Projeto projeto = Projeto.builder()
            .id(3L)
            .titulo("Bug simples")
            .orcamento(null)
            .mediaProposta(null)
            .build();

        Proposta proposta = avaliadorIaService.sugerirProposta(projeto);

        assertNotNull(proposta);
        assertEquals(BigDecimal.valueOf(500), proposta.getValorProposto());
    }

    @Test
    @DisplayName("Deve respeitar valor mínimo")
    void deveRespeitarValorMinimo() {
        Projeto projeto = Projeto.builder()
            .id(4L)
            .titulo("Projeto barato")
            .orcamento(BigDecimal.valueOf(100))
            .mediaProposta(BigDecimal.valueOf(50))
            .valorMinimo(BigDecimal.valueOf(500))
            .build();

        Proposta proposta = avaliadorIaService.sugerirProposta(projeto);

        assertTrue(proposta.getValorProposto().compareTo(BigDecimal.valueOf(500)) >= 0);
    }
}
