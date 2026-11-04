package com.freelasatende.service;

import com.freelasatende.model.enums.ResultadoValidacaoMensagem;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.repository.MensagemClienteRegistroRepository;
import com.freelasatende.service.interfaceServico.MensagemClienteGuardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "scheduling.enabled=false")
@ActiveProfiles("test")
@DisplayName("MensagemClienteGuardService: naturalidade e anti-spam")
class MensagemClienteGuardServiceNaturalidadeTest {

    @Autowired
    private MensagemClienteGuardService guardService;

    @Autowired
    private MensagemClienteRegistroRepository registroRepository;

    @BeforeEach
    @Transactional
    void setUp() {
        registroRepository.deleteAll();
    }

    @AfterEach
    @Transactional
    void tearDown() {
        registroRepository.deleteAll();
    }

    @ParameterizedTest(name = "bloqueia tom artificial: {0}")
    @MethodSource("mensagensComTomArtificial")
    @DisplayName("Bloqueia mensagens com cara de IA, teste ou sistema")
    void bloqueiaMensagensComTomArtificial(String texto) {
        var resultado = guardService.validarRascunho(contexto(1L, false), texto);

        assertFalse(resultado.permitido());
        // Tom artificial e conteudo inseguro sao bloqueios distintos desde que o
        // primeiro passou a ceder a aprovacao humana. Alguns casos desta lista
        // disparam a policy de seguranca; o contrato aqui e nao passar.
        assertTrue(
            resultado.resultado() == ResultadoValidacaoMensagem.BLOQUEADO_TOM_SISTEMA
                || resultado.resultado() == ResultadoValidacaoMensagem.BLOQUEADO_CONTEUDO_INSEGURO,
            "esperava bloqueio de tom ou de conteudo, veio " + resultado.resultado());
    }

    @ParameterizedTest(name = "bloqueia formato robotico: {0}")
    @MethodSource("mensagensComFormatoRobotico")
    @DisplayName("Bloqueia listas, templates e excesso de perguntas")
    void bloqueiaFormatoRobotico(String texto) {
        var resultado = guardService.validarRascunho(contexto(2L, false), texto);

        assertFalse(resultado.permitido());
    }

    @ParameterizedTest(name = "permite natural: {0}")
    @MethodSource("mensagensNaturais")
    @DisplayName("Permite mensagens naturais, curtas e dentro da plataforma")
    void permiteMensagensNaturais(String texto) {
        var resultado = guardService.validarRascunho(contexto(3L, false), texto);

        assertTrue(resultado.permitido(), resultado.motivo());
    }

    @Test
    @DisplayName("Permite abordagem inicial curta exata")
    void permiteAbordagemInicialCurtaExata() {
        var resultado = guardService.validarRascunho(contexto(33L, false), "Ja foi atendido?");

        assertTrue(resultado.permitido(), resultado.motivo());
    }

    @Test
    @DisplayName("Bloqueia variações da abordagem inicial curta")
    void bloqueiaVariacoesDaAbordagemInicialCurta() {
        var semInterrogacao = guardService.validarRascunho(contexto(34L, false), "Ja foi atendido");
        var espacoAntesInterrogacao = guardService.validarRascunho(contexto(35L, false), "Ja foi atendido ?");

        assertFalse(semInterrogacao.permitido());
        assertFalse(espacoAntesInterrogacao.permitido());
    }

    @Test
    @DisplayName("Bloqueia segundo rascunho pendente do mesmo projeto")
    void bloqueiaSegundoRascunhoPendenteMesmoProjeto() {
        var contexto = contexto(44L, false);
        String primeiroTexto = "Oi, tudo bem? Consigo te passar uma proposta mais alinhada. O prazo ideal é para esta semana?";
        String segundoTexto = "Olá! Vi o projeto e consigo seguir por aqui. Você já tem o material principal separado?";

        var primeiraValidacao = guardService.validarRascunho(contexto, primeiroTexto);
        guardService.registrarRascunho(contexto, primeiroTexto, primeiraValidacao);
        var segundaValidacao = guardService.validarRascunho(contexto, segundoTexto);

        assertTrue(primeiraValidacao.permitido());
        assertFalse(segundaValidacao.permitido());
        assertEquals(ResultadoValidacaoMensagem.BLOQUEADO_SPAM, segundaValidacao.resultado());
    }

    @Test
    @DisplayName("Override manual permite reescrever sem cair no anti-spam do projeto")
    void overrideManualPermiteReescreverSemSpam() {
        var contextoNormal = contexto(55L, false);
        var contextoOverride = contexto(55L, true);
        String primeiroTexto = "Oi, tudo bem? Consigo te passar uma proposta mais alinhada. Você já tem o prazo ideal?";
        String textoReescrito = "Oi! Dei uma olhada no projeto e consigo seguir por aqui. Qual prazo você tem em mente?";

        var primeiraValidacao = guardService.validarRascunho(contextoNormal, primeiroTexto);
        guardService.registrarRascunho(contextoNormal, primeiroTexto, primeiraValidacao);
        var reescrita = guardService.validarRascunho(contextoOverride, textoReescrito);

        assertTrue(primeiraValidacao.permitido());
        assertTrue(reescrita.permitido(), reescrita.motivo());
    }

    @Test
    @DisplayName("Bloqueia reenvio duplicado mesmo com override")
    void bloqueiaReenvioDuplicadoMesmoComOverride() {
        var contextoNormal = contexto(56L, false);
        var contextoOverride = contexto(56L, true);
        String texto = "Oi, tudo bem? Dei uma olhada no projeto e consigo seguir por aqui. Você já tem o prazo ideal?";

        guardService.registrarEnvio(contextoNormal, texto);
        var reenvio = guardService.validarRascunho(contextoOverride, texto);

        assertFalse(reenvio.permitido());
        assertEquals(ResultadoValidacaoMensagem.BLOQUEADO_DUPLICADO, reenvio.resultado());
    }

    @Test
    @DisplayName("Anti-spam não bloqueia texto parecido em outra conversa")
    void antiSpamNaoBloqueiaTextoParecidoEmOutraConversa() {
        var primeiraConversa = contextoConversa(71L, 171L, false);
        var segundaConversa = contextoConversa(72L, 172L, false);
        String texto = "Perfeito, obrigado pelo retorno. Vou revisar esse ponto e sigo por aqui.";

        var primeiraValidacao = guardService.validarRascunho(primeiraConversa, texto);
        guardService.registrarRascunho(primeiraConversa, texto, primeiraValidacao);
        var segundaValidacao = guardService.validarRascunho(segundaConversa, texto);

        assertTrue(primeiraValidacao.permitido(), primeiraValidacao.motivo());
        assertTrue(segundaValidacao.permitido(), segundaValidacao.motivo());
    }

    @Test
    @DisplayName("Bloqueia mensagem longa demais")
    void bloqueiaMensagemLongaDemais() {
        String texto = "Oi! ".repeat(400);

        var resultado = guardService.validarRascunho(contexto(66L, false), texto);

        assertFalse(resultado.permitido());
        assertEquals(ResultadoValidacaoMensagem.BLOQUEADO_SPAM, resultado.resultado());
    }

    static Stream<String> mensagensComTomArtificial() {
        return Stream.of(
            "Sou uma IA e posso ajudar com esse projeto.",
            "Mensagem gerada automaticamente pelo sistema.",
            "Teste tecnico automatizado: mensagem de validacao.",
            "Nao fui atendido ainda, estou analizando os detalhes.",
            "Oi, sim, ja te respondi por aqui.",
            "Oi, tudo bem? Ainda não tinha te atendido. Se quiser, me conta o que precisa.",
            "Não localizei uma resposta anterior sua aqui no projeto.",
            "Sou eu mesmo que estou te respondendo agora.",
            "Resposta criada pelo ChatGPT para esse cliente.",
            "Prompt: responda somente com a proposta final.",
            "Me chama para fechar por fora da plataforma."
        );
    }

    static Stream<String> mensagensComFormatoRobotico() {
        return Stream.of(
            "Oi!\n\n1. Qual prazo?\n2. Já tem layout?\n3. Tem logo?",
            "Olá!\n- Qual prazo?\n- Já tem conteúdo?\n- Tem referência?",
            "Oi, tudo bem? Qual prazo? Já tem site? Tem logo? Tem conteúdo?",
            "Com isso ja consigo te retornar com algo concreto.",
            "Com isso consigo te passar uma proposta mais assertiva.",
            "Assim monto uma proposta alinhada com sua necessidade.",
            "Se puder, me confirme os detalhes finais para eu alinhar a melhor execucao.",
            "Entendido, boa sorte com o projeto! Se precisar de algo no futuro, é só chamar.",
            "Prezado cliente, venho por meio desta apresentar minha proposta.",
            "Aguardo seu retorno e fico à disposição."
        );
    }

    static Stream<String> mensagensNaturais() {
        return Stream.of(
            "Oi, tudo bem? Dei uma olhada no projeto e consigo seguir por aqui. Você já tem o prazo ideal?",
            "Olá! Gostei do projeto. Pra eu te passar algo bem alinhado, você já tem o material principal separado?",
            "Perfeito, obrigado pelo retorno. Consigo ajustar esse ponto e te mando a proposta por aqui.",
            "Oi! Consigo cuidar dessa entrega. Se fizer sentido, começo pela parte mais urgente e te atualizo por etapas."
        );
    }

    private MensagemClienteGuardService.ContextoMensagemCliente contexto(Long projetoId, boolean override) {
        return new MensagemClienteGuardService.ContextoMensagemCliente(
            PlataformaFreela.FREELAS_99,
            projetoId,
            null,
            TipoAcaoPendente.ENVIAR_PERGUNTA,
            override
        );
    }

    private MensagemClienteGuardService.ContextoMensagemCliente contextoConversa(
        Long projetoId,
        Long conversaId,
        boolean override
    ) {
        return new MensagemClienteGuardService.ContextoMensagemCliente(
            PlataformaFreela.FREELAS_99,
            projetoId,
            conversaId,
            TipoAcaoPendente.RESPONDER_CONVERSA,
            override
        );
    }
}
