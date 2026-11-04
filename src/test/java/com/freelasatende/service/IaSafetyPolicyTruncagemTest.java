package com.freelasatende.service;

import com.freelasatende.util.IaSafetyPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("IaSafetyPolicy: truncagem sem cortar frase")
class IaSafetyPolicyTruncagemTest {

    @Test
    @DisplayName("Corta na ultima frase completa quando passa do limite")
    void cortaNaUltimaFraseCompletaQuandoPassaDoLimite() {
        String texto = "Primeira frase completa aqui. Segunda frase completa aqui. "
            + "Terceira frase que passa do limite e seria cortada no meio.";

        String saneada = IaSafetyPolicy.sanitize(texto, 80);

        assertEquals("Primeira frase completa aqui. Segunda frase completa aqui.", saneada);
        assertTrue(saneada.endsWith("."));
    }

    @Test
    @DisplayName("Nao corta no meio de valor decimal")
    void naoCortaNoMeioDeValorDecimal() {
        String texto = "Faco o projeto por R$ 1.500,00 e entrego conforme o escopo combinado.";

        String saneada = IaSafetyPolicy.sanitize(texto, 25);

        assertFalse(saneada.endsWith("1."), "nao deve terminar num ponto interno de numero");
        assertEquals("Faco o projeto por R$", saneada);
    }

    @Test
    @DisplayName("Sem fim de frase aproveitavel corta na fronteira de palavra")
    void semFimDeFraseAproveitavelCortaNaFronteiraDePalavra() {
        String texto = "Consigo montar o catalogo organizado por categoria e deixar a busca rapida";

        String saneada = IaSafetyPolicy.sanitize(texto, 40);

        assertTrue(saneada.length() <= 40);
        assertFalse(saneada.endsWith(" "));
        // nao deve terminar no meio de uma palavra
        assertTrue(texto.startsWith(saneada));
        assertTrue(texto.charAt(saneada.length()) == ' ');
    }

    @Test
    @DisplayName("Texto dentro do limite passa intacto")
    void textoDentroDoLimitePassaIntacto() {
        String texto = "Mensagem curta e completa.";

        assertEquals(texto, IaSafetyPolicy.sanitize(texto, 200));
    }
}
