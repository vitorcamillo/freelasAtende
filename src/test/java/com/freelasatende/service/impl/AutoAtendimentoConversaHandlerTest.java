package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.policy.OperationalSafetyPolicy;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.repository.ProjetoRepository;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoAtendimentoConversaHandlerTest {

    @ParameterizedTest
    @EnumSource(value = PlataformaFreela.class, names = {"FREELAS_99", "WORKANA"})
    void emEnsinoCriaPendenteENaoExecutaRespostaAutomatica(PlataformaFreela plataforma) throws Exception {
        ConversaFixture fixture = criarFixture(plataforma, ModoSistema.ENSINAMENTO);

        fixture.handler().processarConversa(conversa(plataforma), ModoSistema.ENSINAMENTO);

        verify(fixture.hooks()).criarOuRecuperarAcaoConversa(any(), anyString(), anyString(), anyString(), eq(ModoSistema.ENSINAMENTO));
        verify(fixture.hooks()).notificarAcaoPendente(fixture.pendente());
        verify(fixture.hooks(), never()).executarAcao(any());
        verify(fixture.adapter(), never()).enviarMensagemConversa(any(), anyString());
    }

    @ParameterizedTest
    @EnumSource(value = PlataformaFreela.class, names = {"FREELAS_99", "WORKANA"})
    void emRegularUsaCoreComPendenteTelegramSemEnvioAutomatico(PlataformaFreela plataforma) throws Exception {
        ConversaFixture fixture = criarFixture(plataforma, ModoSistema.REGULAR);

        fixture.handler().processarConversa(conversa(plataforma), ModoSistema.REGULAR);

        verify(fixture.hooks()).criarOuRecuperarAcaoConversa(any(), anyString(), anyString(), anyString(), eq(ModoSistema.REGULAR));
        verify(fixture.hooks()).notificarAcaoPendente(fixture.pendente());
        verify(fixture.hooks(), never()).executarAcao(any());
        verify(fixture.adapter(), never()).enviarMensagemConversa(any(), anyString());
    }

    @ParameterizedTest
    @EnumSource(value = PlataformaFreela.class, names = {"FREELAS_99", "WORKANA"})
    void conversaForaDeEscopoNaoEhRecusadaEViraPendenciaTelegram(PlataformaFreela plataforma) throws Exception {
        // Conversas nunca sao recusadas por escopo: mesmo um projeto "fora do foco"
        // deve gerar rascunho e card para o operador decidir.
        ConversaFixture fixture = criarFixture(
            plataforma,
            ModoSistema.REGULAR,
            List.of(
                MensagemConversa.builder()
                    .id(210744865L)
                    .idConversa(77L)
                    .texto("Sou leigo na area e estou ajudando meus alunos a levar o software para o mercado.")
                    .dataCriacaoMillis(2000L)
                    .enviadaPorMim(false)
                    .sistema(false)
                    .build(),
                MensagemConversa.builder()
                    .id(210745040L)
                    .idConversa(77L)
                    .texto("Os detalhes do projeto foram modificados.")
                    .dataCriacaoMillis(3000L)
                    .enviadaPorMim(false)
                    .sistema(true)
                    .build()
            )
        );

        fixture.handler().processarConversa(conversa(plataforma, "Especialista em Trafego Pago e Growth"), ModoSistema.REGULAR);

        verify(fixture.avaliador()).sugerirRespostaConversa(any(), anyString(), anyString());
        verify(fixture.hooks()).criarOuRecuperarAcaoConversa(any(), anyString(), anyString(), anyString(), eq(ModoSistema.REGULAR));
        verify(fixture.hooks()).notificarAcaoPendente(fixture.pendente());
        verify(fixture.adapter(), never()).enviarMensagemConversa(any(), anyString());
    }

    @ParameterizedTest
    @EnumSource(value = PlataformaFreela.class, names = {"FREELAS_99", "WORKANA"})
    void iaRecebeDescricaoDoProjetoEProposstaJaEnviada(PlataformaFreela plataforma) throws Exception {
        ConversaFixture fixture = criarFixture(plataforma, ModoSistema.REGULAR);
        Long projetoId = plataforma.idInterno(123L);
        Projeto completo = Projeto.builder()
            .id(projetoId)
            .idExterno(123L)
            .plataforma(plataforma)
            .titulo("Refatoracao de plataforma SaaS em Laravel e Next.js")
            .categoria("Desenvolvimento web")
            .descricao("Preciso refatorar um SaaS legado em Laravel e migrar o front para Next.js com multi-tenant.")
            .build();
        when(fixture.projetoRepository().findById(projetoId)).thenReturn(Optional.of(completo));
        AcaoPendente propostaExecutada = AcaoPendente.builder()
            .plataforma(plataforma)
            .projetoId(projetoId)
            .tipo(TipoAcaoPendente.ENVIAR_PROPOSTA)
            .status(StatusAcaoPendente.EXECUTADA)
            .payloadJson("{\"mensagem\":\"Ola! Posso refatorar o Laravel por modulos e entregar o Next.js em fases.\"}")
            .build();
        when(fixture.acaoRepository().findByPlataformaAndProjetoIdAndTipoAndStatusOrderByDataCriacaoDesc(
            eq(plataforma),
            eq(projetoId),
            eq(TipoAcaoPendente.ENVIAR_PROPOSTA),
            eq(StatusAcaoPendente.EXECUTADA)
        )).thenReturn(List.of(propostaExecutada));

        fixture.handler().processarConversa(conversa(plataforma), ModoSistema.REGULAR);

        ArgumentCaptor<Projeto> projetoCaptor = ArgumentCaptor.forClass(Projeto.class);
        ArgumentCaptor<String> historicoCaptor = ArgumentCaptor.forClass(String.class);
        verify(fixture.avaliador()).sugerirRespostaConversa(projetoCaptor.capture(), historicoCaptor.capture(), anyString());
        assertEquals(completo.getDescricao(), projetoCaptor.getValue().getDescricao());
        assertEquals("Desenvolvimento web", projetoCaptor.getValue().getCategoria());
        assertTrue(historicoCaptor.getValue().contains("[o que eu ja enviei neste projeto antes desta conversa]"));
        assertTrue(historicoCaptor.getValue().contains("refatorar o Laravel por modulos"));
        assertTrue(historicoCaptor.getValue().contains("Ainda precisa fazer?"));

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(fixture.hooks()).criarOuRecuperarAcaoConversa(any(), anyString(), payloadCaptor.capture(), anyString(), eq(ModoSistema.REGULAR));
        RespostaPayload payload = new ObjectMapper().readValue(payloadCaptor.getValue(), RespostaPayload.class);
        assertTrue(payload.getContextoProjeto().contains("multi-tenant"));
        assertTrue(payload.getEnviosAnteriores().contains("refatorar o Laravel por modulos"));
        assertFalse(payload.getHistoricoConversa().contains("[o que eu ja enviei"));
        assertTrue(payload.getEntendimentoIa().contains("descricao do projeto"));
    }

    @ParameterizedTest
    @EnumSource(value = PlataformaFreela.class, names = {"FREELAS_99", "WORKANA"})
    void semDescricaoDoProjetoRegistraRiscoDeContexto(PlataformaFreela plataforma) throws Exception {
        ConversaFixture fixture = criarFixture(plataforma, ModoSistema.REGULAR);

        fixture.handler().processarConversa(conversa(plataforma), ModoSistema.REGULAR);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(fixture.hooks()).criarOuRecuperarAcaoConversa(any(), anyString(), payloadCaptor.capture(), anyString(), eq(ModoSistema.REGULAR));
        RespostaPayload payload = new ObjectMapper().readValue(payloadCaptor.getValue(), RespostaPayload.class);
        assertTrue(payload.getRiscosDecisao().stream().anyMatch(r -> r.startsWith("contexto_projeto")));
        assertTrue(payload.getEntendimentoIa().contains("a conversa"));
    }

    @ParameterizedTest
    @EnumSource(value = PlataformaFreela.class, names = {"FREELAS_99", "WORKANA"})
    void mensagemJaRegistradaReenviaCardSePendenteNaoTemTelegramId(PlataformaFreela plataforma) throws Exception {
        MarketplaceAdapter adapter = mock(MarketplaceAdapter.class);
        when(adapter.plataforma()).thenReturn(plataforma);
        when(adapter.listarMensagensConversa(77L)).thenReturn(List.of(
            MensagemConversa.builder()
                .id(8801L)
                .idConversa(77L)
                .texto("Ainda precisa fazer?")
                .dataCriacaoMillis(1000L)
                .enviadaPorMim(false)
                .build()
        ));

        AcaoPendente pendente = AcaoPendente.builder()
            .plataforma(plataforma)
            .tipo(TipoAcaoPendente.RESPONDER_CONVERSA)
            .status(StatusAcaoPendente.PENDENTE)
            .payloadJson("{\"mensagemGatilhoId\":8801,\"mensagem\":\"Sim, consigo seguir por aqui.\"}")
            .build();
        AcaoPendenteRepository acaoRepository = mock(AcaoPendenteRepository.class);
        when(acaoRepository.findFirstByPlataformaAndConversaIdAndTipoAndStatusOrderByDataCriacaoDesc(
            eq(plataforma),
            eq(77L),
            eq(TipoAcaoPendente.RESPONDER_CONVERSA),
            eq(StatusAcaoPendente.PENDENTE)
        )).thenReturn(Optional.of(pendente));

        InteracaoRepository interacaoRepository = mock(InteracaoRepository.class);
        when(interacaoRepository.existsByTipoAndConteudoContaining(
            eq(TipoInteracao.MENSAGEM_RECEBIDA),
            anyString()
        )).thenReturn(true);
        AutoAtendimentoConversaHandler.Hooks hooks = mock(AutoAtendimentoConversaHandler.Hooks.class);
        AutoAtendimentoConversaHandler handler = new AutoAtendimentoConversaHandler(
            new MarketplaceAdapterRegistry(List.of(adapter)),
            mock(AvaliadorIaService.class),
            acaoRepository,
            interacaoRepository,
            mock(ProjetoRepository.class),
            new ObjectMapper(),
            new OperationalSafetyPolicy(),
            hooks,
            true,
            false
        );

        handler.processarConversa(conversa(plataforma), ModoSistema.REGULAR);

        verify(hooks).notificarAcaoPendente(pendente);
        verify(hooks, never()).criarOuRecuperarAcaoConversa(any(), anyString(), anyString(), anyString(), eq(ModoSistema.REGULAR));
    }

    @ParameterizedTest
    @EnumSource(value = PlataformaFreela.class, names = {"FREELAS_99", "WORKANA"})
    void iaQueFalhaAindaEnviaCardParaDecisaoHumanaENaoMarcaComoProcessada(PlataformaFreela plataforma) throws Exception {
        // A IA nao pode descartar a mensagem do cliente em silencio: mesmo falhando,
        // o operador recebe o card (revisao humana) e a mensagem NAO e marcada como
        // processada, para reprocessar e tentar gerar o rascunho na proxima rodada.
        ConversaFixture fixture = criarFixture(plataforma, ModoSistema.REGULAR);
        when(fixture.avaliador().sugerirRespostaConversa(any(Projeto.class), anyString(), anyString()))
            .thenThrow(new IllegalStateException("IA nao gerou resposta de conversa valida: timeout"));

        fixture.handler().processarConversa(conversa(plataforma), ModoSistema.REGULAR);

        verify(fixture.hooks()).criarOuRecuperarAcaoConversaRevisaoHumana(any(), anyString(), anyString(), eq(ModoSistema.REGULAR));
        verify(fixture.hooks()).notificarAcaoPendente(fixture.pendente());
        verify(fixture.hooks(), never()).criarOuRecuperarAcaoConversa(any(), anyString(), anyString(), anyString(), any());
        verify(fixture.hooks(), never()).registrarInteracao(
            eq(plataforma),
            any(),
            anyString(),
            eq(TipoInteracao.MENSAGEM_RECEBIDA),
            anyString(),
            eq(ModoSistema.REGULAR)
        );
        verify(fixture.adapter(), never()).enviarMensagemConversa(any(), anyString());
    }

    @ParameterizedTest
    @EnumSource(value = PlataformaFreela.class, names = {"FREELAS_99", "WORKANA"})
    void respostaReprovadaComRascunhoViraPendenciaParaDecisaoHumana(PlataformaFreela plataforma) throws Exception {
        ConversaFixture fixture = criarFixture(plataforma, ModoSistema.REGULAR);
        when(fixture.avaliador().sugerirRespostaConversa(any(Projeto.class), anyString(), anyString()))
            .thenThrow(new AvaliadorIaRespostaConversaHelper.RespostaConversaReprovadaException(
                "Posso seguir por aqui, mas antes me confirma qual ajuste ficou pendente?",
                "IA gerou resposta de conversa reprovada pela qualidade: decisao=REESCREVER; sinais=[parece_template]"
            ));

        fixture.handler().processarConversa(conversa(plataforma), ModoSistema.REGULAR);

        ArgumentCaptor<String> resumoCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(fixture.hooks()).criarOuRecuperarAcaoConversaRevisaoHumana(
            any(),
            resumoCaptor.capture(),
            payloadCaptor.capture(),
            eq(ModoSistema.REGULAR)
        );
        verify(fixture.hooks(), never()).criarOuRecuperarAcaoConversa(any(), anyString(), anyString(), anyString(), any());
        assertTrue(resumoCaptor.getValue().contains("Revisar qualidade"));
        assertTrue(payloadCaptor.getValue().contains("guardiao_qualidade"));
        assertTrue(payloadCaptor.getValue().contains("Posso seguir por aqui"));
        verify(fixture.hooks()).notificarAcaoPendente(fixture.pendente());
        verify(fixture.adapter(), never()).enviarMensagemConversa(any(), anyString());
    }

    @ParameterizedTest
    @EnumSource(value = PlataformaFreela.class, names = {"FREELAS_99", "WORKANA"})
    void erroAnteriorDeIaNaoBloqueiaReprocessamentoDaConversa(PlataformaFreela plataforma) throws Exception {
        // Um erro de IA anterior nao pode mais "estacionar" a conversa: a cada rodada
        // o sistema tenta gerar o rascunho de novo ate conseguir responder.
        ConversaFixture fixture = criarFixture(plataforma, ModoSistema.REGULAR);
        when(fixture.interacaoRepository().existsByTipoAndConteudoContaining(
            eq(TipoInteracao.MENSAGEM_ERRO),
            org.mockito.ArgumentMatchers.contains("|mensagem:8801")
        )).thenReturn(true);

        fixture.handler().processarConversa(conversa(plataforma), ModoSistema.REGULAR);

        verify(fixture.avaliador()).sugerirRespostaConversa(any(), anyString(), anyString());
        verify(fixture.hooks()).criarOuRecuperarAcaoConversa(any(), anyString(), anyString(), anyString(), eq(ModoSistema.REGULAR));
        verify(fixture.hooks()).notificarAcaoPendente(fixture.pendente());
        verify(fixture.adapter(), never()).enviarMensagemConversa(any(), anyString());
    }

    private ConversaFixture criarFixture(PlataformaFreela plataforma, ModoSistema modo) {
        return criarFixture(
            plataforma,
            modo,
            List.of(
                MensagemConversa.builder()
                    .id(8801L)
                    .idConversa(77L)
                    .texto("Ainda precisa fazer?")
                    .dataCriacaoMillis(1000L)
                    .enviadaPorMim(false)
                    .build()
            )
        );
    }

    private ConversaFixture criarFixture(PlataformaFreela plataforma, ModoSistema modo, List<MensagemConversa> mensagens) {
        MarketplaceAdapter adapter = mock(MarketplaceAdapter.class);
        when(adapter.plataforma()).thenReturn(plataforma);
        when(adapter.listarMensagensConversa(77L)).thenReturn(mensagens);

        AvaliadorIaService avaliador = mock(AvaliadorIaService.class);
        when(avaliador.sugerirRespostaConversa(any(Projeto.class), anyString(), anyString()))
            .thenReturn("Sim, consigo seguir por aqui.");

        AcaoPendenteRepository acaoRepository = mock(AcaoPendenteRepository.class);
        when(acaoRepository.save(any(AcaoPendente.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(acaoRepository.findFirstByPlataformaAndConversaIdAndTipoAndStatusOrderByDataCriacaoDesc(
            eq(plataforma),
            eq(77L),
            eq(TipoAcaoPendente.RESPONDER_CONVERSA),
            eq(StatusAcaoPendente.PENDENTE)
        )).thenReturn(Optional.empty());

        AutoAtendimentoConversaHandler.Hooks hooks = mock(AutoAtendimentoConversaHandler.Hooks.class);
        AcaoPendente pendente = AcaoPendente.builder()
            .tipo(TipoAcaoPendente.RESPONDER_CONVERSA)
            .status(StatusAcaoPendente.PENDENTE)
            .build();
        when(hooks.criarOuRecuperarAcaoConversa(any(), anyString(), anyString(), anyString(), eq(modo)))
            .thenReturn(pendente);
        when(hooks.criarOuRecuperarAcaoConversaRevisaoHumana(any(), anyString(), anyString(), eq(modo)))
            .thenReturn(pendente);

        InteracaoRepository interacaoRepository = mock(InteracaoRepository.class);
        ProjetoRepository projetoRepository = mock(ProjetoRepository.class);
        AutoAtendimentoConversaHandler handler = new AutoAtendimentoConversaHandler(
            new MarketplaceAdapterRegistry(List.of(adapter)),
            avaliador,
            acaoRepository,
            interacaoRepository,
            projetoRepository,
            new ObjectMapper(),
            new OperationalSafetyPolicy(),
            hooks,
            true,
            false
        );
        return new ConversaFixture(adapter, avaliador, acaoRepository, interacaoRepository, projetoRepository, hooks, pendente, handler);
    }

    private ConversaResumo conversa(PlataformaFreela plataforma) {
        return conversa(plataforma, "Projeto " + plataforma.key());
    }

    private ConversaResumo conversa(PlataformaFreela plataforma, String nomeProjeto) {
        return ConversaResumo.builder()
            .plataforma(plataforma)
            .idConversa(77L)
            .idProjeto(plataforma.idInterno(123L))
            .nomeProjeto(nomeProjeto)
            .build();
    }

    private record ConversaFixture(
        MarketplaceAdapter adapter,
        AvaliadorIaService avaliador,
        AcaoPendenteRepository acaoRepository,
        InteracaoRepository interacaoRepository,
        ProjetoRepository projetoRepository,
        AutoAtendimentoConversaHandler.Hooks hooks,
        AcaoPendente pendente,
        AutoAtendimentoConversaHandler handler
    ) {
    }
}
