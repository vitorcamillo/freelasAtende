package com.freelasatende.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlataformaFreelaTest {

    @Test
    void fromKeyAceitaAliasesConhecidos() {
        assertEquals(PlataformaFreela.FREELAS_99, PlataformaFreela.fromKey("99freelas"));
        assertEquals(PlataformaFreela.WORKANA, PlataformaFreela.fromKey("workana"));
        assertEquals(PlataformaFreela.TESTE, PlataformaFreela.fromKey("teste"));
        assertEquals(PlataformaFreela.UPWORK, PlataformaFreela.fromKey("upwork"));
        assertEquals(PlataformaFreela.FREELANCER, PlataformaFreela.fromKey("freelancer"));
        assertEquals(PlataformaFreela.FREELANCER_BR, PlataformaFreela.fromKey("freelancer_br"));
        assertEquals(PlataformaFreela.FREELANCER_BR, PlataformaFreela.fromKey("FREELANCER-BR"));
    }

    @Test
    void novasPlataformasTemIdsInternosEstaveis() {
        assertEquals(3_000_000_000_123L, PlataformaFreela.UPWORK.idInterno(123L));
        assertEquals(4_000_000_000_123L, PlataformaFreela.FREELANCER.idInterno(123L));
        assertEquals(5_000_000_000_123L, PlataformaFreela.FREELANCER_BR.idInterno(123L));
    }

    @Test
    void fromKeyFalhaParaMarketplaceDesconhecido() {
        assertThrows(IllegalArgumentException.class, () -> PlataformaFreela.fromKey("worknaa"));
    }
}
