package com.freelasatende.service;

import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.IaProvider;
import com.freelasatende.service.interfaceServico.UrlFetcherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "scheduling.enabled=false")
@ActiveProfiles("test")
@DisplayName("AvaliadorIaService: contrato de saida ao cliente")
class AvaliadorIaOutputContractTest {

    @Autowired
    private AvaliadorIaService avaliadorIaService;

    @MockBean
    private IaProvider iaProvider;

    @MockBean
    private UrlFetcherService urlFetcherService;

    @BeforeEach
    void setUp() {
        reset(iaProvider, urlFetcherService);
        when(iaProvider.getNome()).thenReturn("OpenAI");
        when(iaProvider.getModelo()).thenReturn("gpt-4o-mini");
        when(urlFetcherService.buscarContextoDeUrls(anyString())).thenReturn("");
    }

    @Test
    @DisplayName("Gerar dúvidas limpa numeração e limita para uma conversa por vez")
    void gerarDuvidasLimpaNumeracaoELimitaVolume() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt())).thenReturn("""
            1. Qual prazo ideal?
            2. Já tem layout pronto?
            3. O conteúdo já existe?
            4. Tem preferência de tecnologia?
            """);

        List<String> duvidas = avaliadorIaService.gerarDuvidas(projetoBase());

        assertEquals(1, duvidas.size());
        assertFalse(duvidas.get(0).startsWith("1."));
        assertTrue(duvidas.get(0).endsWith("?"));
    }

    @Test
    @DisplayName("Texto de proposta com contato externo falha sem fallback local")
    void textoPropostaComContatoExternoFalhaSemFallbackLocal() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt()))
            .thenReturn("Me chama no WhatsApp para eu te passar tudo por lá. Minha proposta é R$ 800,00.");

        assertThrows(IllegalStateException.class, () -> avaliadorIaService.gerarTextoProposta(
            projetoBase(),
            Proposta.builder().projetoId(10L).valorProposto(BigDecimal.valueOf(800)).build()
        ));
    }

    @Test
    @DisplayName("Resposta de conversa com contato externo falha sem fallback local")
    void respostaConversaComContatoExternoFalhaSemFallbackLocal() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt()))
            .thenReturn("Me chama no Telegram que te explico melhor.");

        assertThrows(IllegalStateException.class, () -> avaliadorIaService.sugerirRespostaConversa(
            projetoBase(),
            "Cliente: pode seguir?",
            "Pode seguir?"
        ));
    }

    @Test
    @DisplayName("Resposta generica de conversa falha sem fallback contextual")
    void respostaGenericaDeConversaFalhaSemFallbackContextual() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt()))
            .thenReturn("Perfeito, obrigado pelo retorno. Vou revisar esse ponto e sigo por aqui com o próximo passo.");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> avaliadorIaService.sugerirRespostaConversa(
            projetoBase(),
            "Cliente: nesta aba precisamos incluir o convenio como obrigatorio",
            "nesta aba precisaremos adicionar a opção de incluir o convênio do paciente como obrigatório"
        ));
        assertTrue(exception.getMessage().contains("decisao="));
        assertTrue(exception.getMessage().contains("sinais="));
        assertTrue(exception.getMessage().contains("scores="));
    }

    @Test
    @DisplayName("Resposta de conversa não pode fechar base de escopo cedo demais")
    void respostaConversaNaoPodeFecharBaseEscopoCedoDemais() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt()))
            .thenReturn("Entendi. Com isso ja da pra fechar a base do escopo e avancar na proxima etapa.");

        assertThrows(IllegalStateException.class, () -> avaliadorIaService.sugerirRespostaConversa(
            projetoBase(),
            "Freelancer: O que existe hoje?\nCliente: Tenho um legado em Next.js e quero migrar.",
            "Tenho um legado em Next.js e quero migrar."
        ));
    }

    @Test
    @DisplayName("Reescrita generica falha sem alternativa local")
    void reescritaGenericaFalhaSemAlternativaLocal() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt()))
            .thenReturn("Perfeito, obrigado pelo retorno. Vou revisar esse ponto e sigo por aqui com o próximo passo.");

        assertThrows(IllegalStateException.class, () -> avaliadorIaService.reescreverRascunhoComOrientacao(
            "Mensagem do cliente que disparou a resposta:\nAinda não\n",
            "Perfeito, obrigado pelo retorno. Vou revisar esse ponto e sigo por aqui com o próximo passo.",
            "Crie uma alternativa diferente e concreta."
        ));
    }

    @Test
    @DisplayName("Reescrita quase igual tenta novamente e qualifica melhor o cliente")
    void reescritaQuaseIgualTentaNovamenteEQualificaMelhorCliente() throws Exception {
        String rascunho = """
            Entendi a migração: sair do Next.js/monorepo com Better Auth, Postgres/Drizzle, R2 e Asaas/Celoin
            e levar tudo para TanStack Start em estrutura flat, com Supabase Auth, Supabase/PostgreSQL,
            migrations SQL, createServerFn e Supabase Storage, mantendo shadcn/ui e Tailwind. Com isso, já dá
            para fechar a base do escopo e avançar na próxima etapa.
            """;
        when(iaProvider.enviar(anyString(), anyInt()))
            .thenReturn(rascunho)
            .thenReturn("""
                Legal, entendi a direção da migração. Pra eu não assumir errado, o legado atual já tem autenticação,
                telas principais e fluxo de arquivos funcionando, ou alguma dessas partes também precisa entrar na
                primeira entrega?
                """);

        String resposta = avaliadorIaService.reescreverRascunhoComOrientacao(
            "Conversa inicial: cliente só explicou o que existe hoje e pediu considerar bem o histórico.",
            rascunho,
            "Troque a mensagem. Ainda é primeira pergunta; extraia mais contexto do cliente antes de falar em escopo fechado."
        );

        assertTrue(resposta.contains("legado atual já tem autenticação"));
        assertFalse(resposta.toLowerCase().contains("fechar a base"));
        assertFalse(resposta.toLowerCase().contains("próxima etapa"));
        verify(iaProvider, times(2)).enviar(anyString(), anyInt());
    }

    @Test
    @DisplayName("Reescrita de tom robotico não pode trocar resposta por texto de anuncio")
    void reescritaRoboticaNaoPodeTrocarRespostaPorTextoDeAnuncio() throws Exception {
        String rascunhoAtual = """
            Perfeito, agora ficou claro. O projeto é um microSaaS web e mobile com cadastro de usuários,
            controle de agenda, estoque, entradas e saídas financeiras, dashboards e acesso para que cada
            cliente visualize agenda, serviços e valores. Com esse escopo, já consigo organizar o próximo
            passo e alinhar prazo e valor por aqui.
            """;
        when(iaProvider.enviar(anyString(), anyInt()))
            .thenReturn("""
                Entendi. Você pode deixar o anúncio assim: “Busco um profissional para desenvolver um microSaaS do zero,
                cuidando do planejamento até a entrega final em funcionamento. A plataforma deve permitir cadastro de vários
                usuários e incluir controle de agenda, estoque, entradas e saídas financeiras, dashboards com dados e acesso
                para que cada cliente visualize sua agenda, serviços disponíveis e valores.”
                """);

        assertThrows(IllegalStateException.class, () -> avaliadorIaService.reescreverRascunhoComOrientacao(
            "Cliente pediu um microSaaS web e mobile.",
            rascunhoAtual,
            "O texto ficou robotico. Reescreva com tom mais humano, mantendo o mesmo objetivo."
        ));
    }

    @Test
    @DisplayName("Texto de proposta com tom de IA revalida ate esgotar e entrega melhor rascunho sanizado")
    void textoPropostaComTomIaFalhaSemFallbackLocal() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt()))
            .thenReturn("Sou uma IA e gerei automaticamente esta proposta. Minha proposta é R$ 800,00.");

        String resposta = avaliadorIaService.gerarTextoProposta(
            projetoBase(),
            Proposta.builder().projetoId(10L).valorProposto(BigDecimal.valueOf(800)).build()
        );
        assertTrue(resposta.contains("R$ 800,00"));
    }

    @Test
    @DisplayName("Texto de proposta invalido e refeito ate vir texto valido")
    void textoPropostaInvalidoERefeitoAteVirTextoValido() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt()))
            .thenReturn("Prezado cliente, segue abaixo detalhes da proposta. Qualquer dúvida estou à disposição. Minha proposta é R$ 800,00.")
            .thenReturn("Posso desenvolver a landing page responsiva com foco em captação de leads, estrutura clara e ajustes finais dentro da plataforma. Minha proposta para essa entrega é de R$ 800,00.");

        String resposta = avaliadorIaService.gerarTextoProposta(
            projetoBase(),
            Proposta.builder().projetoId(10L).valorProposto(BigDecimal.valueOf(800)).build()
        );

        assertTrue(resposta.contains("R$ 800,00"));
        assertFalse(resposta.toLowerCase().contains("prezado cliente"));
        verify(iaProvider, times(2)).enviar(anyString(), anyInt());
    }

    @Test
    @DisplayName("Texto de proposta com vazamento de caracteres CJK e refeito ate vir texto valido")
    void textoPropostaComVazamentoCjkERefeitoAteVirTextoValido() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt()))
            .thenReturn("Minha abordagem: vou usar uma estrutura稳稳当当 clean e responsiva. Minha proposta é R$ 800,00.")
            .thenReturn("Posso desenvolver a landing page responsiva com foco em captação de leads, estrutura clara e ajustes finais dentro da plataforma. Minha proposta para essa entrega é de R$ 800,00.");

        String resposta = avaliadorIaService.gerarTextoProposta(
            projetoBase(),
            Proposta.builder().projetoId(10L).valorProposto(BigDecimal.valueOf(800)).build()
        );

        assertTrue(resposta.contains("R$ 800,00"));
        assertFalse(resposta.contains("稳"));
        verify(iaProvider, times(2)).enviar(anyString(), anyInt());
    }

    @Test
    @DisplayName("Resposta de inbox com lista numerada falha sem fallback natural")
    void respostaInboxComListaNumeradaFalhaSemFallbackNatural() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt())).thenReturn("""
            Oi!
            1. Confirmo o escopo.
            2. Posso começar hoje.
            """);

        assertThrows(IllegalStateException.class, () -> avaliadorIaService.sugerirRespostaConversa(
            projetoBase(),
            "Cliente: consegue fazer?",
            "consegue fazer?"
        ));
    }

    @Test
    @DisplayName("Proposta sem valores usa mínimo conservador")
    void propostaSemValoresUsaMinimoConservador() {
        Projeto projeto = Projeto.builder()
            .id(11L)
            .titulo("Ajuste simples")
            .descricao("Preciso ajustar uma tela")
            .build();

        Proposta proposta = avaliadorIaService.sugerirProposta(projeto);

        assertNotNull(proposta);
        assertEquals(BigDecimal.valueOf(500), proposta.getValorProposto());
    }

    @Test
    @DisplayName("Resposta curta demais falha sem fallback útil")
    void respostaCurtaDemaisFalhaSemFallbackUtil() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt())).thenReturn("ok");

        assertThrows(IllegalStateException.class, () -> avaliadorIaService.sugerirRespostaConversa(
            projetoBase(),
            "Cliente: consegue fazer?",
            "consegue fazer?"
        ));
    }

    @Test
    @DisplayName("Resposta de conversa invalida e refeita ate vir texto valido")
    void respostaConversaInvalidaERefeitaAteVirTextoValido() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt()))
            .thenReturn("")
            .thenReturn("Me chama no Telegram que te explico melhor.")
            .thenReturn("Consigo seguir sim. Para eu alinhar a proposta por aqui, voce quer manter o layout do template oficial ou posso ajustar a identidade visual junto?");

        String resposta = avaliadorIaService.sugerirRespostaConversa(
            projetoBase(),
            "Cliente: quero que fique igual essa plataforma: https://www.idealtrade.ai/",
            "quero que fique igual essa plataforma: https://www.idealtrade.ai/"
        );

        assertTrue(resposta.contains("Consigo seguir"));
        verify(iaProvider, times(3)).enviar(anyString(), anyInt());
    }

    @Test
    @DisplayName("Resposta de conversa inicial orienta qualificação antes de prazo e valor")
    void respostaConversaInicialOrientaQualificacaoAntesDePrazoEValor() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt()))
            .thenReturn("Entendi. Antes de falar em prazo e valor, o formulario precisa integrar com algum CRM ou só enviar por email?");

        avaliadorIaService.sugerirRespostaConversa(
            projetoBase(),
            "Cliente: Preciso de landing page com formulario, botao de WhatsApp e textos principais.",
            "Pode me passar valor e prazo?"
        );

        var captor = forClass(String.class);
        verify(iaProvider).enviar(captor.capture(), anyInt());
        String prompt = captor.getValue();

        assertTrue(prompt.contains("Em conversa inicial"));
        assertTrue(prompt.contains("NÃO diga que já dá para fechar escopo"));
        assertTrue(prompt.contains("extraia o suficiente do cliente"));
        assertTrue(prompt.contains("estado atual, objetivo da primeira entrega, integrações/dados"));
        assertTrue(prompt.contains("Nao pedir orcamento cedo demais"));
    }

    @Test
    @DisplayName("Auditoria IA interpreta score de qualidade")
    void auditoriaIaInterpretaScoreDeQualidade() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt())).thenReturn("""
            {"naturalidade":91,"clareza":88,"risco":8,"duplicidade":0,"confianca":89,
            "decisao":"APROVAR","comentario":"natural e claro","sinais":[]}
            """);

        var qualidade = avaliadorIaService.auditarRascunhoCliente(
            "primeiro contato",
            "Oi, tudo bem? Vi o projeto e consigo seguir por aqui. Voce ja tem o prazo ideal?"
        );

        assertEquals("APROVAR", qualidade.getDecisaoRecomendada());
        assertEquals("ALTA", qualidade.getNivelConfianca());
        assertTrue(qualidade.getScoreConfianca() >= 80);
    }

    @Test
    @DisplayName("Auditoria IA contraditória vira reescrita e não bloqueio")
    void auditoriaIaContraditoriaViraReescritaENaoBloqueio() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt())).thenReturn("""
            {"naturalidade":90,"clareza":90,"risco":8,"duplicidade":0,"confianca":88,
            "decisao":"BLOQUEAR","comentario":"tom humano e claro","sinais":["tom_humano"]}
            """);

        var qualidade = avaliadorIaService.auditarRascunhoCliente(
            "primeiro contato",
            "Oi, tudo bem? Vi o projeto e consigo seguir por aqui. Voce ja tem o prazo ideal?"
        );

        assertEquals("REESCREVER", qualidade.getDecisaoRecomendada());
        assertFalse(qualidade.bloqueante());
    }

    @Test
    @DisplayName("Auditoria IA com escala curta não derruba score determinístico")
    void auditoriaIaComEscalaCurtaNaoDerrubaScoreDeterministico() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt())).thenReturn("""
            {"naturalidade":1,"clareza":1,"risco":1,"duplicidade":1,"confianca":1,
            "decisao":"REESCREVER","comentario":"tom humano e claro","sinais":["tom_humano"]}
            """);

        var qualidade = avaliadorIaService.auditarRascunhoCliente(
            "primeiro contato",
            "Oi, tudo bem? Vi o projeto e consigo seguir por aqui. Voce ja tem o prazo ideal?"
        );

        assertFalse(qualidade.bloqueante());
        assertTrue(qualidade.getScoreConfianca() >= 60);
    }

    @Test
    @DisplayName("Auditoria IA com escala zero a dez é normalizada")
    void auditoriaIaComEscalaZeroADezENormalizada() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt())).thenReturn("""
            {"naturalidade":9,"clareza":9,"risco":1,"duplicidade":0,"confianca":7,
            "decisao":"REESCREVER","comentario":"Tom natural, direto e específico.","sinais":["tom_casual"]}
            """);

        var qualidade = avaliadorIaService.auditarRascunhoCliente(
            "primeiro contato",
            "Oi, tudo bem? Vi o projeto e consigo seguir por aqui. Voce ja tem o prazo ideal?"
        );

        assertFalse(qualidade.bloqueante());
        assertTrue(qualidade.getScoreNaturalidade() >= 70);
        assertTrue(qualidade.getScoreConfianca() >= 60);
    }

    @Test
    @DisplayName("Auditoria IA com bloqueio contraditório positivo vira reescrita")
    void auditoriaIaComBloqueioContraditorioPositivoViraReescrita() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt())).thenReturn("""
            {"naturalidade":95,"clareza":95,"risco":95,"duplicidade":0,"confianca":95,
            "decisao":"BLOQUEAR","comentario":"Mensagem curta, natural e direta para primeiro contato.","sinais":["tom_humano"]}
            """);

        var qualidade = avaliadorIaService.auditarRascunhoCliente(
            "primeiro contato",
            "Oi, tudo bem? Vi o projeto e consigo seguir por aqui. Voce ja tem o prazo ideal?"
        );

        assertFalse(qualidade.bloqueante());
        assertEquals("REESCREVER", qualidade.getDecisaoRecomendada());
    }

    @Test
    @DisplayName("Auditoria não chama IA quando regra determinística já bloqueia")
    void auditoriaNaoChamaIaQuandoDeterministicoBloqueia() throws Exception {
        var qualidade = avaliadorIaService.auditarRascunhoCliente(
            "primeiro contato",
            "Sou uma IA e esta mensagem foi gerada automaticamente pelo sistema."
        );

        assertEquals("BLOQUEAR", qualidade.getDecisaoRecomendada());
        verify(iaProvider, never()).enviar(anyString(), anyInt());
    }

    private Projeto projetoBase() {
        return Projeto.builder()
            .id(10L)
            .titulo("Landing page para campanha")
            .descricao("Preciso de uma landing page responsiva para captar leads.")
            .categoria("Web")
            .nivelExperiencia("Pleno")
            .orcamento(BigDecimal.valueOf(1000))
            .build();
    }
}
