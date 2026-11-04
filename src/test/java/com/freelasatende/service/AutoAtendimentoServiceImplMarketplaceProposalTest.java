package com.freelasatende.service;

import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.model.enums.ModoSistema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.when;

class AutoAtendimentoServiceImplMarketplaceProposalTest extends AutoAtendimentoServiceImplTestSupport {

    @Test
    @DisplayName("Proposta preserva texto completo gerado pela IA")
    void propostaPreservaTextoCompletoGeradoPelaIa() throws Exception {
        Long projetoId = sequencia.incrementAndGet();
        Projeto projeto = Projeto.builder()
            .id(projetoId)
            .titulo("API para integracao")
            .descricao("Construir integracao REST com testes e documentacao.")
            .urlProjeto("https://www.99freelas.com.br/project/api-" + projetoId)
            .orcamento(BigDecimal.valueOf(900))
            .build();
        Proposta proposta = Proposta.builder()
            .valorProposto(BigDecimal.valueOf(900))
            .valorMinimo(BigDecimal.valueOf(700))
            .build();
        String textoCompleto = "Ola! Posso implementar a integracao REST, cobrir os fluxos com testes e documentar a entrega.";
        when(avaliadorIaService.sugerirProposta(projeto)).thenReturn(proposta);
        when(avaliadorIaService.gerarTextoProposta(projeto, proposta)).thenReturn(textoCompleto);

        ReflectionTestUtils.invokeMethod(
            autoAtendimentoService,
            "criarPropostaProjeto",
            projeto,
            ModoSistema.REGULAR
        );

        AcaoPendente acao = acaoPendenteRepository.findAll().stream()
            .filter(item -> projetoId.equals(item.getProjetoId()))
            .findFirst()
            .orElseThrow();
        Map<?, ?> payload = objectMapper.readValue(acao.getPayloadJson(), Map.class);
        assertEquals(textoCompleto, payload.get("mensagem"));
        assertNotEquals("Ja foi atendido?", payload.get("mensagem"));
    }
}
