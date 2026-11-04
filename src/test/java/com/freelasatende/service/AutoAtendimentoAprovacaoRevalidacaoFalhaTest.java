package com.freelasatende.service;

import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.service.interfaceServico.MensagemClienteGuardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoAtendimentoAprovacaoRevalidacaoFalhaTest extends AutoAtendimentoServiceImplTestSupport {

    @MockBean
    private MensagemClienteGuardService mensagemClienteGuardService;

    @Test
    @DisplayName("Falha na revalidacao depois de reivindicar a acao devolve para PENDENTE sem enviar")
    void falhaNaRevalidacaoDevolveAcaoParaPendente() {
        habilitarEscritaRealMockada();
        Long projetoId = sequencia.incrementAndGet();
        when(mensagemClienteGuardService.validarRascunho(any(), anyString()))
            .thenThrow(new IllegalStateException("guardiao indisponivel"));
        AcaoPendente acao = acaoPendenteRepository.save(AcaoPendente.builder()
            .projetoId(projetoId)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Projeto com guardiao fora")
            .resumo("Confirmar prazo com cliente")
            .payloadJson("{\"projetoId\":" + projetoId + ",\"tituloProjeto\":\"Projeto com guardiao fora\","
                + "\"mensagem\":\"Oi, voce consegue me confirmar o prazo ideal?\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build());
        Long acaoId = acao.getId();

        assertThrows(IllegalStateException.class,
            () -> autoAtendimentoService.processarFeedback(acaoId, true, "BOM_TOM"));

        assertEquals(StatusAcaoPendente.PENDENTE,
            acaoPendenteRepository.findById(acaoId).orElseThrow().getStatus());
        verify(scraperService, never()).enviarPergunta(any(), anyString());
    }
}
