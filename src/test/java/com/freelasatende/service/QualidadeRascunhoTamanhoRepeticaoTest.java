package com.freelasatende.service;

import com.freelasatende.service.impl.QualidadeRascunhoServiceImpl;
import com.freelasatende.service.interfaceServico.QualidadeRascunhoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("QualidadeRascunhoService: tamanho e repeticao de ideia")
class QualidadeRascunhoTamanhoRepeticaoTest {

    private QualidadeRascunhoService qualidadeRascunhoService;

    @BeforeEach
    void setUp() {
        qualidadeRascunhoService = new QualidadeRascunhoServiceImpl();
    }

    @Test
    @DisplayName("Texto no tamanho ideal e aprovado sem sinal de tamanho")
    void textoNoTamanhoIdealEAprovadoSemSinalDeTamanho() {
        var qualidade = qualidadeRascunhoService.avaliar(
            "Dei uma olhada no escopo do catalogo e ja trabalhei com filtro por categoria antes. "
                + "Antes de estimar prazo, preciso saber se os produtos vao vir de uma planilha ou de um ERP."
        );

        assertEquals("APROVAR", qualidade.getDecisaoRecomendada());
        assertFalse(qualidade.getSinais().contains("texto_longo"));
        assertFalse(qualidade.getSinais().contains("ideia_repetida"));
    }

    @Test
    @DisplayName("Texto acima do limite maximo forca reescrita sem bloquear")
    void textoAcimaDoLimiteMaximoForcaReescritaSemBloquear() {
        // filler sem frase repetida: isola o efeito do limite de tamanho
        var qualidade = qualidadeRascunhoService.avaliar("palavralonga".repeat(80));

        assertTrue(qualidade.getSinais().contains("texto_longo"));
        assertTrue(qualidade.getSinais().contains("texto_longo_demais"));
        assertEquals("REESCREVER", qualidade.getDecisaoRecomendada());
    }

    @Test
    @DisplayName("Texto entre o ideal e o maximo e tolerado mas sinalizado")
    void textoEntreOIdealEOMaximoEToleradoMasSinalizado() {
        var qualidade = qualidadeRascunhoService.avaliar("palavralonga".repeat(50));

        assertTrue(qualidade.getSinais().contains("texto_longo"));
        assertFalse(qualidade.getSinais().contains("texto_longo_demais"));
    }

    @Test
    @DisplayName("Ideia repetida na mesma mensagem forca reescrita")
    void ideiaRepetidaNaMesmaMensagemForcaReescrita() {
        var qualidade = qualidadeRascunhoService.avaliar(
            "Posso desenvolver o catalogo de produtos com filtro por categoria e busca rapida. "
                + "Vou desenvolver esse catalogo de produtos com filtro por categoria, busca rapida e ordenacao."
        );

        assertTrue(qualidade.getSinais().contains("ideia_repetida"));
        assertNotEquals("APROVAR", qualidade.getDecisaoRecomendada());
    }

    @Test
    @DisplayName("Frases distintas sobre o mesmo projeto nao contam como repeticao")
    void frasesDistintasSobreOMesmoProjetoNaoContamComoRepeticao() {
        var qualidade = qualidadeRascunhoService.avaliar(
            "Ja fiz integracao de pagamento em loja parecida usando cartao e boleto. "
                + "Para comecar preciso entender qual gateway voce pretende contratar."
        );

        assertFalse(qualidade.getSinais().contains("ideia_repetida"));
    }
}
