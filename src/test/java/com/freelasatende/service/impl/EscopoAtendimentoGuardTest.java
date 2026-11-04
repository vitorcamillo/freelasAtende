package com.freelasatende.service.impl;

import com.freelasatende.model.entity.Projeto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EscopoAtendimentoGuardTest {

    @Test
    void bloqueiaCampanhaMensalDeAdsMesmoQuandoMencionaSite() {
        assertBloqueado(
            "Gestao mensal de Meta Ads",
            "Preciso de campanhas mensais de ads para aumentar o trafego do meu site."
        );
    }

    @Test
    void bloqueiaCriacaoDeMotionDesign() {
        assertBloqueado(
            "Motion designer para criativos",
            "Criacao de motion design e animacao para redes sociais usando After Effects."
        );
    }

    @Test
    void bloqueiaAberturaDeCnpj() {
        assertBloqueado(
            "Abertura de CNPJ",
            "Preciso abrir CNPJ e fazer regularizacao fiscal da empresa."
        );
    }

    @Test
    void permiteLandingPageExplicitamenteTecnicaParaCampanha() {
        assertTrue(EscopoAtendimentoGuard.motivoBloqueio(projeto(
            "Criar landing page para campanha de Meta Ads",
            "Preciso desenvolver landing page responsiva com formulario e integracao web."
        )).isEmpty());
    }

    private void assertBloqueado(String titulo, String descricao) {
        assertTrue(EscopoAtendimentoGuard.motivoBloqueio(projeto(titulo, descricao)).isPresent());
    }

    private Projeto projeto(String titulo, String descricao) {
        return Projeto.builder()
            .id(1L)
            .titulo(titulo)
            .descricao(descricao)
            .categoria("Web, Mobile & Software")
            .build();
    }
}
