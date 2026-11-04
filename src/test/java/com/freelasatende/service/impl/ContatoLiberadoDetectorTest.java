package com.freelasatende.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixa a deteccao do aviso em que o marketplace libera o contato do cliente.
 * Texto base copiado da inbox real do Freelancer.com.br (15/09).
 */
class ContatoLiberadoDetectorTest {

    private static final String AVISO_PT = """
        O empregador nao respondeu ao seu interesse e nao atualizou o status do projeto. \
        Portanto divulgamos agora os dados de contato do empregador. Se desejar, voce pode \
        conta-los diretamente: Nome do cliente: Pedro Henrique
        E-mail: pedro.henrique@empresa.com.br
        Telefone: +55 (11) 98765-4321
        """;

    @Test
    @DisplayName("detecta o aviso em portugues e extrai nome, email e telefone")
    void detectaAvisoPortugues() {
        var contato = ContatoLiberadoDetector.detectar(AVISO_PT).orElseThrow();
        assertEquals("Pedro Henrique", contato.nome());
        assertEquals("pedro.henrique@empresa.com.br", contato.email());
        assertEquals("+55 (11) 98765-4321", contato.telefone());
        assertTrue(contato.temAlgumDado());
        assertTrue(contato.textoIntegral().contains("divulgamos agora"));
    }

    @Test
    @DisplayName("detecta com acentuacao completa, como o site escreve")
    void detectaComAcento() {
        String comAcento = "O empregador não respondeu ao seu interesse. Portanto divulgamos agora os "
            + "dados de contato do empregador. Se desejar, você pode contatá-los diretamente: "
            + "Nome do contato: Gustavo Mattos | E-mail: gustavo@teste.com";
        var contato = ContatoLiberadoDetector.detectar(comAcento).orElseThrow();
        assertEquals("Gustavo Mattos", contato.nome());
        assertEquals("gustavo@teste.com", contato.email());
    }

    @Test
    @DisplayName("detecta a versao em ingles do mesmo aviso")
    void detectaAvisoIngles() {
        String ingles = "The employer did not respond to your interest. We are releasing the contact "
            + "details now. Name: John Doe Email: john@doe.com Phone: 11 3222-4455";
        var contato = ContatoLiberadoDetector.detectar(ingles).orElseThrow();
        assertEquals("John Doe", contato.nome());
        assertEquals("john@doe.com", contato.email());
        assertEquals("11 3222-4455", contato.telefone());
    }

    @Test
    @DisplayName("mensagem comum de cliente nao dispara alerta")
    void mensagemComumNaoDispara() {
        assertTrue(ContatoLiberadoDetector.detectar(
            "Ola, vi seu projeto e ainda esta em aberto? Se nao foi atendendo posso lhe ajudar?").isEmpty());
        assertTrue(ContatoLiberadoDetector.detectar("Ola, obrigado pelo contato.").isEmpty());
        assertTrue(ContatoLiberadoDetector.detectar(null).isEmpty());
        assertTrue(ContatoLiberadoDetector.detectar("   ").isEmpty());
    }

    @Test
    @DisplayName("data no texto nao vira telefone")
    void dataNaoViraTelefone() {
        String semTelefone = "Portanto divulgamos agora os dados de contato do empregador. "
            + "Publicado em 13-09-2026. Nome do cliente: Daniel Mangolini";
        var contato = ContatoLiberadoDetector.detectar(semTelefone).orElseThrow();
        assertEquals("Daniel Mangolini", contato.nome());
        assertTrue(contato.telefone().isBlank(), "data nao pode virar telefone: " + contato.telefone());
        assertTrue(contato.temAlgumDado());
    }
    @Test
    @DisplayName("alerta nao pode conter marcacao que quebra o Markdown do Telegram")
    void alertaSemMarkdownQuebrado() {
        var contato = ContatoLiberadoDetector.detectar(
            "Interesse no projeto `Banco de Dados`. Portanto divulgamos agora os dados de "
            + "contato do empregador. Nome do cliente: Pedro_Henrique E-mail: pedro@empresa.com").orElseThrow();
        String alerta = AutoAtendimentoTextHelper.formatarContatoLiberado(
            com.freelasatende.model.enums.PlataformaFreela.FREELANCER_BR,
            "Banco de Dados", "Pedro", contato);
        assertTrue(alerta.contains("pedro@empresa.com"));
        assertTrue(alerta.indexOf('`') < 0, "crase quebra o parse do Telegram");
        assertTrue(alerta.indexOf('_') < 0, "sublinhado quebra o parse do Telegram");
        assertTrue(alerta.indexOf('*') < 0, "asterisco quebra o parse do Telegram");
    }
}
