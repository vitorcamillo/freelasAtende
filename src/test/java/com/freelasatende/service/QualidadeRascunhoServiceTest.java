package com.freelasatende.service;

import com.freelasatende.service.impl.QualidadeRascunhoServiceImpl;
import com.freelasatende.service.interfaceServico.QualidadeRascunhoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("QualidadeRascunhoService: score operacional")
class QualidadeRascunhoServiceTest {

    private QualidadeRascunhoService qualidadeRascunhoService;

    @BeforeEach
    void setUp() {
        qualidadeRascunhoService = new QualidadeRascunhoServiceImpl();
    }

    @Test
    @DisplayName("Mensagem natural fica com confiança alta")
    void mensagemNaturalFicaComConfiancaAlta() {
        var qualidade = qualidadeRascunhoService.avaliar(
            "Oi, tudo bem? Dei uma olhada no projeto e consigo seguir por aqui. Voce ja tem o prazo ideal?"
        );

        assertEquals("APROVAR", qualidade.getDecisaoRecomendada());
        assertEquals("ALTA", qualidade.getNivelConfianca());
        assertTrue(qualidade.getScoreNaturalidade() >= 80);
    }

    @Test
    @DisplayName("Abordagem inicial curta exata fica aprovada")
    void abordagemInicialCurtaExataFicaAprovada() {
        var qualidade = qualidadeRascunhoService.avaliar("Ja foi atendido?");

        assertEquals("APROVAR", qualidade.getDecisaoRecomendada());
        assertEquals("ALTA", qualidade.getNivelConfianca());
        assertTrue(qualidade.getSinais().contains("abordagem_inicial_curta"));
    }

    @Test
    @DisplayName("Variações da abordagem inicial curta não usam exceção")
    void variacoesDaAbordagemInicialCurtaNaoUsamExcecao() {
        var semInterrogacao = qualidadeRascunhoService.avaliar("Ja foi atendido");
        var espacoAntesInterrogacao = qualidadeRascunhoService.avaliar("Ja foi atendido ?");

        assertEquals("BLOQUEAR", semInterrogacao.getDecisaoRecomendada());
        assertEquals("BLOQUEAR", espacoAntesInterrogacao.getDecisaoRecomendada());
    }

    @Test
    @DisplayName("Tom de sistema bloqueia")
    void tomDeSistemaBloqueia() {
        var qualidade = qualidadeRascunhoService.avaliar(
            "Teste tecnico automatizado: mensagem de validacao de integracao real."
        );

        assertEquals("BLOQUEAR", qualidade.getDecisaoRecomendada());
        assertEquals("BLOQUEADA", qualidade.getNivelConfianca());
        assertTrue(qualidade.getSinais().contains("tom_sistema_ou_ia"));
    }

    @Test
    @DisplayName("Contato fora da plataforma bloqueia por risco")
    void contatoForaDaPlataformaBloqueiaPorRisco() {
        var qualidade = qualidadeRascunhoService.avaliar(
            "Me chama no WhatsApp que te explico melhor e a gente fecha por fora da plataforma."
        );

        assertEquals("BLOQUEAR", qualidade.getDecisaoRecomendada());
        assertTrue(qualidade.getScoreRisco() >= 70);
        assertTrue(qualidade.getSinais().contains("risco_regra_plataforma"));
    }

    @Test
    @DisplayName("Lista numerada recomenda reescrita")
    void listaNumeradaRecomendaReescrita() {
        var qualidade = qualidadeRascunhoService.avaliar("""
            Oi!
            1. Qual prazo?
            2. Ja tem layout?
            """);

        assertEquals("REESCREVER", qualidade.getDecisaoRecomendada());
        assertTrue(qualidade.getSinais().contains("parece_template"));
    }

    @Test
    @DisplayName("Fechamento template recomenda reescrita")
    void fechamentoTemplateRecomendaReescrita() {
        var qualidade = qualidadeRascunhoService.avaliar(
            "Pode me dar mais detalhes sobre o que precisa fazer? Assim monto uma proposta alinhada com sua necessidade."
        );

        assertEquals("REESCREVER", qualidade.getDecisaoRecomendada());
        assertTrue(qualidade.getSinais().contains("parece_template"));
    }

    @Test
    @DisplayName("Resposta que assume nao atendimento recomenda reescrita")
    void respostaQueAssumeNaoAtendimentoRecomendaReescrita() {
        var qualidade = qualidadeRascunhoService.avaliar(
            "Oi, tudo bem? Ainda não tinha te atendido. Vi que o projeto está sem detalhes ainda."
        );

        assertEquals("REESCREVER", qualidade.getDecisaoRecomendada());
        assertTrue(qualidade.getSinais().contains("parece_template"));
    }

    @Test
    @DisplayName("Duplicidade do guardiao derruba confiança")
    void duplicidadeDoGuardiaoDerrubaConfianca() {
        var qualidade = qualidadeRascunhoService.avaliar(
            "Oi, consigo seguir por aqui. Voce ja tem o prazo ideal?",
            "BLOQUEADO_DUPLICADO",
            "Texto igual ou muito parecido com mensagem ja criada"
        );

        assertEquals("BLOQUEAR", qualidade.getDecisaoRecomendada());
        assertEquals(100, qualidade.getScoreDuplicidade());
        assertTrue(qualidade.getSinais().contains("duplicidade"));
    }

    @Test
    @DisplayName("Promessa de desconto sem validar escopo bloqueia")
    void promessaDescontoSemValidarEscopoBloqueia() {
        var qualidade = qualidadeRascunhoService.avaliar(
            "Oi, entendo a comparacao. Consigo repassar um novo valor por aqui, reducao pequena mas justa. " +
                "Me da uns minutos que ja atualizo a proposta na plataforma pra voce ver."
        );

        assertEquals("BLOQUEAR", qualidade.getDecisaoRecomendada());
        assertTrue(qualidade.getScoreRisco() >= 70);
        assertTrue(qualidade.getSinais().contains("promessa_preco_sem_validacao"));
    }

    @Test
    @DisplayName("Pergunta sobre valor com escopo preservado pode ser aprovada")
    void perguntaSobreValorComEscopoPreservadoPodeSerAprovada() {
        var qualidade = qualidadeRascunhoService.avaliar(
            "Oi, entendo o ponto. Antes de confirmar valor, preciso validar o escopo desse ajuste. " +
                "Qual parte voce quer priorizar para caber melhor no orcamento?"
        );

        assertEquals("APROVAR", qualidade.getDecisaoRecomendada());
        assertTrue(qualidade.getScoreRisco() < 30);
    }

    @Test
    @DisplayName("Promessa de prazo fechado sem validar escopo bloqueia")
    void promessaPrazoFechadoSemValidarEscopoBloqueia() {
        var qualidade = qualidadeRascunhoService.avaliar(
            "Consigo entregar hoje sem risco, pode ficar tranquilo que ja deixo pronto ate a noite."
        );

        assertEquals("BLOQUEAR", qualidade.getDecisaoRecomendada());
        assertTrue(qualidade.getScoreRisco() >= 70);
        assertTrue(qualidade.getSinais().contains("promessa_prazo_sem_validacao"));
    }
}
