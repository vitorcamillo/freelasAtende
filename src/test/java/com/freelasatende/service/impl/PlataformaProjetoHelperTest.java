package com.freelasatende.service.impl;

import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.PlataformaFreela;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PlataformaProjetoHelperTest {

    @Test
    @DisplayName("99Freelas preserva o id nativo como id interno")
    void freelas99PreservaIdNativo() {
        Projeto projeto = PlataformaProjetoHelper.normalizarProjeto(Projeto.builder()
            .plataforma(PlataformaFreela.FREELAS_99)
            .id(756384L)
            .titulo("Landing page")
            .build());

        assertEquals(756384L, projeto.getId());
        assertEquals(756384L, projeto.getIdExterno());
        assertEquals(PlataformaFreela.FREELAS_99, projeto.getPlataforma());
    }

    @Test
    @DisplayName("Plataformas diferentes podem carregar o mesmo id nativo sem colisao interna")
    void plataformasDiferentesGeramIdsInternosDistintos() {
        Long idNativo = 756384L;
        Projeto freelas99 = PlataformaProjetoHelper.normalizarProjeto(Projeto.builder()
            .plataforma(PlataformaFreela.FREELAS_99)
            .id(idNativo)
            .titulo("Sistema")
            .build());
        Projeto teste = PlataformaProjetoHelper.normalizarProjeto(Projeto.builder()
            .plataforma(PlataformaFreela.TESTE)
            .id(idNativo)
            .titulo("Sistema")
            .build());

        assertNotEquals(freelas99.getId(), teste.getId());
        assertEquals(idNativo, freelas99.getIdExterno());
        assertEquals(idNativo, teste.getIdExterno());
        assertEquals(teste.getId(), PlataformaFreela.TESTE.idInterno(idNativo));
    }
}
