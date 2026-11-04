package com.freelasatende.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedatorSegredosTest {

    @Test
    @DisplayName("Cookie com valor entre aspas nao expoe a sessao seguinte na mesma linha")
    void cookieComValorEntreAspasNaoExpoeSessaoSeguinte() {
        String resultado = RedatorSegredos.mascarar(
            "Cookie: pref=\"dark\"; freelancer_live_session=SESSION_TEST");

        assertFalse(resultado.contains("SESSION_TEST"), () -> "Sessao vazou: " + resultado);
        assertFalse(resultado.contains("dark"), () -> "Valor citado vazou: " + resultado);
        assertTrue(resultado.contains("***"), () -> "Mascara ausente: " + resultado);
    }

    @Test
    @DisplayName("Set-Cookie mascara todos os pares ate o fim da linha")
    void setCookieMascaraAteOFimDaLinha() {
        String resultado = RedatorSegredos.mascarar(
            "Set-Cookie: freelancer_live_session=SESSION_TEST; Path=/; HttpOnly");

        assertFalse(resultado.contains("SESSION_TEST"), () -> "Sessao vazou: " + resultado);
        assertFalse(resultado.contains("HttpOnly"), () -> "Trecho apos a sessao vazou: " + resultado);
        assertTrue(resultado.contains("***"), () -> "Mascara ausente: " + resultado);
    }

    @Test
    @DisplayName("Cookie com valor citado no inicio nao expoe pares seguintes")
    void cookieComValorCitadoNoInicioNaoExpoeParesSeguintes() {
        String resultado = RedatorSegredos.mascarar(
            "Cookie: \"tema=dark\"; freelancer_live_session=SESSION_TEST");

        assertFalse(resultado.contains("SESSION_TEST"), () -> "Sessao vazou: " + resultado);
        assertFalse(resultado.contains("dark"), () -> "Valor citado vazou: " + resultado);
    }

    @Test
    @DisplayName("Cookie mascara ate o fim da linha e preserva a linha seguinte")
    void cookiePreservaLinhaSeguinte() {
        String resultado = RedatorSegredos.mascarar(
            "Cookie: sessao=VALOR_SECRETO\nlinha seguinte visivel");

        assertFalse(resultado.contains("VALOR_SECRETO"), () -> "Segredo vazou: " + resultado);
        assertTrue(resultado.contains("linha seguinte visivel"),
            () -> "Linha seguinte foi apagada: " + resultado);
    }

    @Test
    @DisplayName("Campo cookie em JSON mascara o valor e preserva campo irmao")
    void campoJsonCookiePreservaCampoIrmao() {
        String resultado = RedatorSegredos.mascarar(
            "{\"cookie\": \"valor-secreto-json\", \"usuario\": \"visivel\"}");

        assertFalse(resultado.contains("valor-secreto-json"), () -> "Segredo vazou: " + resultado);
        assertTrue(resultado.contains("visivel"),
            () -> "Campo irmao foi apagado junto: " + resultado);
    }

    @Test
    @DisplayName("Token e Bearer continuam mascarados")
    void tokenEBearerContinuamMascarados() {
        String resultado = RedatorSegredos.mascarar(
            "falha token=segredo-de-teste Authorization: Bearer abc123secreto");

        assertFalse(resultado.contains("segredo-de-teste"), () -> "Token vazou: " + resultado);
        assertFalse(resultado.contains("abc123secreto"), () -> "Bearer vazou: " + resultado);
    }

    @Test
    @DisplayName("Valor citado muito longo nao estoura a pilha e continua mascarado")
    void valorCitadoLongoNaoEstouraPilha() {
        String longo = "x".repeat(10_000);
        String escapado = "\\\"".repeat(3_000);

        String resultado = RedatorSegredos.mascarar("{\"token\": \"" + longo + "\", \"cookie\": \"" + escapado + "\"}");

        assertFalse(resultado.contains(longo), () -> "Valor longo vazou");
        assertTrue(resultado.length() < 200, () -> "Mascara incompleta: " + resultado.length());
    }

    @Test
    @DisplayName("Texto longo sem segredo e mascarado em tempo linear")
    void textoLongoSemSegredoEmTempoLinear() {
        String semSegredo = "palavra_comprida".repeat(4_000) + " e mais " + "a-b.c".repeat(4_000);

        long inicio = System.nanoTime();
        String resultado = RedatorSegredos.mascarar(semSegredo);
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        assertEquals(semSegredo, resultado);
        assertTrue(ms < 500, () -> "Mascara lenta demais para 84 mil caracteres: " + ms + " ms");
    }

    @Test
    @DisplayName("Chave seguida de muitos espacos sem separador nao gera retrocesso quadratico")
    void chaveComMuitosEspacosSemSeparadorEmTempoLinear() {
        String espacos = " ".repeat(16_000);
        String texto = "cookie" + espacos + "fim token" + espacos + "fim";

        long inicio = System.nanoTime();
        String resultado = RedatorSegredos.mascarar(texto);
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        assertEquals(texto, resultado);
        assertTrue(ms < 200, () -> "Mascara lenta demais com espacos: " + ms + " ms");
    }

    @Test
    @DisplayName("Bearer entre aspas nao desalinha a mascara do segredo seguinte")
    void bearerEntreAspasNaoExpoeSegredoAdjacente() {
        String resultado = RedatorSegredos.mascarar("token=\"Bearer TEST\" password=\"SECRET_VALUE\"");

        assertFalse(resultado.contains("TEST"), () -> "Bearer vazou: " + resultado);
        assertFalse(resultado.contains("SECRET_VALUE"), () -> "Senha adjacente vazou: " + resultado);
    }

    @Test
    @DisplayName("Bearer com aspa escapada dentro de valor citado nao expoe o sufixo")
    void bearerComAspaEscapadaNaoExpoeSufixo() {
        String resultado = RedatorSegredos.mascarar("{\"token\": \"Bearer ABC\\\"SUFIXO_SECRETO\"}");

        assertFalse(resultado.contains("ABC"), () -> "Bearer vazou: " + resultado);
        assertFalse(resultado.contains("SUFIXO_SECRETO"), () -> "Sufixo vazou: " + resultado);
    }

    @Test
    @DisplayName("Chave sem aspas com valor Bearer mascara o termo seguinte")
    void chaveComValorBearerSemAspas() {
        String resultado = RedatorSegredos.mascarar("token=Bearer abc123secreto fim");

        assertFalse(resultado.contains("abc123secreto"), () -> "Bearer vazou: " + resultado);
        assertTrue(resultado.endsWith(" fim"), () -> "Texto seguinte sumiu: " + resultado);
    }

    @Test
    @DisplayName("Nomes sensiveis repetidos sem separador sao processados em tempo linear")
    void termosSensiveisRepetidosEmTempoLinear() {
        String texto = "token".repeat(40_000) + " " + "cookie".repeat(20_000);

        long inicio = System.nanoTime();
        String resultado = RedatorSegredos.mascarar(texto);
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        assertEquals(texto, resultado);
        assertTrue(ms < 300, () -> "Mascara lenta com termos repetidos: " + ms + " ms");
    }

    @Test
    @DisplayName("Valor com aspa sem fechamento (texto truncado) e mascarado ate o fim da linha")
    void valorComAspaSemFechamentoMascaradoAteFimDaLinha() {
        String resultado = RedatorSegredos.mascarar("token=\"Bearer SEGREDO_TESTE resto\nlinha seguinte");

        assertFalse(resultado.contains("SEGREDO_TESTE"), () -> "Token truncado vazou: " + resultado);
        assertTrue(resultado.endsWith("\nlinha seguinte"), () -> "Linha seguinte sumiu: " + resultado);
    }

    @Test
    @DisplayName("JSON escapado dentro de log, Authorization Basic e secret sao mascarados")
    void jsonEscapadoAuthorizationBasicESecret() {
        String resultado = RedatorSegredos.mascarar(
            "corpo={\\\"token\\\": \\\"ESCAPADO_TESTE\\\", \\\"nome\\\": \\\"visivel\\\"}"
                + "\nAuthorization: Basic BASICO_TESTE\nclient_secret=SECRETO_TESTE");

        assertFalse(resultado.contains("ESCAPADO_TESTE"), () -> "JSON escapado vazou: " + resultado);
        assertFalse(resultado.contains("BASICO_TESTE"), () -> "Basic vazou: " + resultado);
        assertFalse(resultado.contains("SECRETO_TESTE"), () -> "Secret vazou: " + resultado);
        assertTrue(resultado.contains("visivel"), () -> "Campo comum sumiu: " + resultado);
    }

    @Test
    @DisplayName("Muitas aspas abertas em linhas diferentes sao processadas em tempo linear")
    void muitasAspasAbertasEmLinhasEmTempoLinear() {
        String texto = "token=\"abc\n".repeat(20_000) + "senha=\\\"x\n".repeat(20_000)
            + "token=\"abc\" ".repeat(20_000);

        long inicio = System.nanoTime();
        String resultado = RedatorSegredos.mascarar(texto);
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        assertFalse(resultado.contains("abc"), () -> "Valor truncado vazou");
        assertTrue(ms < 300, () -> "Mascara lenta com aspas abertas: " + ms + " ms");
    }

    @Test
    @DisplayName("Aspa interna de JSON escapado nao encerra a mascara cedo")
    void aspaInternaDeJsonEscapadoNaoEncerraMascara() {
        // Texto real: corpo={\"senha\": \"ABC\\\"SUFIXO_SECRETO\", \"nome\": \"visivel\"}
        String resultado = RedatorSegredos.mascarar(
            "corpo={\\\"senha\\\": \\\"ABC\\\\\\\"SUFIXO_SECRETO\\\", \\\"nome\\\": \\\"visivel\\\"}");

        assertFalse(resultado.contains("SUFIXO_SECRETO"), () -> "Sufixo vazou: " + resultado);
        assertTrue(resultado.contains("visivel"), () -> "Campo comum sumiu: " + resultado);
    }

    @Test
    @DisplayName("JSON compacto com valor sem aspas nao corta a mascara da senha seguinte")
    void jsonCompactoComValorSemAspasNaoExpoeSenhaSeguinte() {
        String resultado = RedatorSegredos.mascarar(
            "{\"token\":123,\"password\":\"PRIMEIRA SEGUNDA_SECRETA\"} depois");

        assertFalse(resultado.contains("PRIMEIRA"), () -> "Senha vazou: " + resultado);
        assertFalse(resultado.contains("SEGUNDA_SECRETA"), () -> "Senha vazou: " + resultado);
        assertTrue(resultado.endsWith(" depois"), () -> "Texto seguinte sumiu: " + resultado);
    }
}
