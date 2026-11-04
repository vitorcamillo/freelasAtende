package com.freelasatende.service.impl;

import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.PlataformaFreela;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvaliadorIaPromptFactoryRewriteTest {

    @Test
    @DisplayName("Prompt do Freelancer nao inventa moeda nem experiencia fora do perfil")
    void promptFreelancerNaoInventaMoedaNemExperiencia() {
        Projeto projeto = Projeto.builder()
            .plataforma(PlataformaFreela.FREELANCER)
            .titulo("Professional Business Website Design")
            .descricao("Build a responsive business website")
            .categoria("Website Design")
            .orcamento(new BigDecimal("25"))
            .build();

        String prompt = AvaliadorIaPromptFactory.gerarTextoProposta(
            projeto,
            "R$ 25.00",
            "Tom direto",
            ""
        );

        assertTrue(prompt.contains("NÃO mencione preço, moeda ou valor no corpo"));
        assertTrue(prompt.contains("PERFIL REAL VERIFICADO"));
        assertTrue(prompt.contains("não alegue especialização em branding, graphic design, UX/UI"));
        assertTrue(prompt.contains("sem repetir preço ou moeda"));
        assertFalse(prompt.contains("R$ 25.00"));
        assertTrue(prompt.contains("preenchido separadamente no campo de oferta da plataforma"));
    }

    @Test
    @DisplayName("Outras plataformas continuam incluindo o valor informado")
    void outrasPlataformasMantemValorNoTexto() {
        Projeto projeto = Projeto.builder()
            .plataforma(PlataformaFreela.WORKANA)
            .titulo("API Spring")
            .descricao("Criar uma API")
            .build();

        String prompt = AvaliadorIaPromptFactory.gerarTextoProposta(
            projeto,
            "R$ 500.00",
            "Tom direto",
            ""
        );

        assertTrue(prompt.contains("Inclua exatamente o valor informado abaixo"));
        assertTrue(prompt.contains("incluindo o valor informado"));
        assertFalse(prompt.contains("FREELANCER.COM: NÃO mencione preço"));
    }

    @Test
    @DisplayName("Prompt de reescrita bloqueia resposta generica para cliente ainda nao atendido")
    void promptReescritaBloqueiaRespostaGenericaParaClienteAindaNaoAtendido() {
        String prompt = AvaliadorIaPromptFactory.reescreverRascunhoComOrientacao(
            """
            Historico limpo da conversa:
            - freelancer: Já foi atendido?
            - cliente: Opa, bom dia! Ainda não.
            """,
            "",
            "Perfeito, obrigado pelo retorno. Vou conferir esse ponto com calma e te atualizo por aqui com o próximo passo.",
            "Reescreva com outra ideia.",
            ""
        );

        assertTrue(prompt.contains("não prometa verificar depois"));
        assertTrue(prompt.contains("pergunte qual ajuste ou dúvida ainda falta"));
        assertTrue(prompt.contains("A nova mensagem precisa ser diferente"));
        assertTrue(prompt.contains("vou revisar esse ponto"));
    }
}
