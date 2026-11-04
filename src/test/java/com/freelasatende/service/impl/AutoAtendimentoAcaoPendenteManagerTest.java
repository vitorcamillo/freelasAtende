package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.service.interfaceServico.MensagemClienteGuardService;
import com.freelasatende.service.interfaceServico.QualidadeRascunhoService;
import com.freelasatende.service.interfaceServico.TelegramService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoAtendimentoAcaoPendenteManagerTest {

    @Test
    @DisplayName("Revisao humana de conversa preserva rascunho sem revalidar qualidade")
    void revisaoHumanaDeConversaPreservaRascunhoSemRevalidarQualidade() {
        AcaoPendenteRepository repository = mock(AcaoPendenteRepository.class);
        when(repository.findFirstByPlataformaAndConversaIdAndTipoAndStatusOrderByDataCriacaoDesc(
            eq(PlataformaFreela.WORKANA),
            eq(77L),
            eq(TipoAcaoPendente.RESPONDER_CONVERSA),
            eq(StatusAcaoPendente.PENDENTE)
        )).thenReturn(Optional.empty());
        when(repository.save(any(AcaoPendente.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MensagemClienteGuardService guardService = mock(MensagemClienteGuardService.class);
        QualidadeRascunhoService qualidadeService = mock(QualidadeRascunhoService.class);
        AutoAtendimentoAcaoPendenteManager.Hooks hooks = mock(AutoAtendimentoAcaoPendenteManager.Hooks.class);
        AutoAtendimentoAcaoPendenteManager manager = new AutoAtendimentoAcaoPendenteManager(
            repository,
            new ObjectMapper(),
            guardService,
            qualidadeService,
            mock(TelegramService.class),
            new TelegramMessageFormatter(),
            hooks
        );

        AcaoPendente acao = manager.criarOuRecuperarAcaoConversaRevisaoHumana(
            ConversaResumo.builder()
                .plataforma(PlataformaFreela.WORKANA)
                .idConversa(77L)
                .idProjeto(123L)
                .nomeProjeto("Projeto teste")
                .build(),
            "Revisar qualidade antes de enviar",
            "{\"mensagem\":\"rascunho\"}",
            ModoSistema.REGULAR
        );

        assertNotNull(acao);
        assertEquals(TipoAcaoPendente.RESPONDER_CONVERSA, acao.getTipo());
        assertEquals(StatusAcaoPendente.PENDENTE, acao.getStatus());
        assertEquals("{\"mensagem\":\"rascunho\"}", acao.getPayloadJson());
        verify(guardService, never()).validarRascunho(any(), any());
        verify(qualidadeService, never()).avaliar(any(), any(), any());
        ArgumentCaptor<String> interacaoCaptor = ArgumentCaptor.forClass(String.class);
        verify(hooks).registrarInteracao(
            eq(PlataformaFreela.WORKANA),
            eq(123L),
            eq("Projeto teste"),
            eq(TipoInteracao.REAVALIACAO_SOLICITADA),
            interacaoCaptor.capture(),
            eq(ModoSistema.REGULAR)
        );
        assertEquals(
            "Rascunho de conversa preservado para revisao humana mesmo apos reprovação de qualidade.",
            interacaoCaptor.getValue()
        );
    }
}
