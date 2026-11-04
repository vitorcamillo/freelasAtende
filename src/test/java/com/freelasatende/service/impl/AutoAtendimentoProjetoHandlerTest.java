package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.StatusProjeto;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.policy.OperationalSafetyPolicy;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.ProjetoRepository;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import com.freelasatende.service.interfaceServico.MarketplaceCapabilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoAtendimentoProjetoHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AcaoPendenteRepository acaoPendenteRepository = mock(AcaoPendenteRepository.class);
    private final ProjetoRepository projetoRepository = mock(ProjetoRepository.class);
    private final GerenciadorConexoesService gerenciadorConexoes = mock(GerenciadorConexoesService.class);
    private final AutoAtendimentoProjetoHandler.Hooks hooks = mock(AutoAtendimentoProjetoHandler.Hooks.class);

    @Test
    void candidaturaInicialCurtaComEscritaRealEhExecutadaSemPendenciaHumana() throws Exception {
        AutoAtendimentoProjetoHandler handler = handler(PlataformaFreela.UPWORK, true, false);
        Projeto projeto = projeto(PlataformaFreela.UPWORK, 91L, "API Python");
        Proposta proposta = Proposta.builder()
            .valorProposto(new BigDecimal("100"))
            .mensagem("Ja foi atendido?")
            .build();
        when(hooks.criarOuRecuperarAcaoProjeto(
            eq(projeto), eq(TipoAcaoPendente.ENVIAR_PROPOSTA), any(), any(), eq("Ja foi atendido?"),
            eq(ModoSistema.REGULAR)
        )).thenReturn(AcaoPendente.builder()
            .plataforma(PlataformaFreela.UPWORK)
            .projetoId(projeto.getId())
            .tipo(TipoAcaoPendente.ENVIAR_PROPOSTA)
            .status(StatusAcaoPendente.PENDENTE)
            .build());

        handler.tratarProposta(projeto, proposta, ModoSistema.REGULAR);

        verify(hooks).executarAcao(argThat(acao ->
            acao.getTipo() == TipoAcaoPendente.ENVIAR_PROPOSTA
                && acao.getStatus() == StatusAcaoPendente.EXECUTADA));
        verify(hooks, never()).notificarAcaoPendente(any());
        verify(projetoRepository).save(projeto);
        assertEquals(StatusProjeto.EM_ANALISE, projeto.getStatus());
    }

    @Test
    void propostaComPossePerdidaDescartaSoAAcaoESemMarcarProjeto() throws Exception {
        AutoAtendimentoProjetoHandler handler = handler(PlataformaFreela.UPWORK, true, false);
        Projeto projeto = projeto(PlataformaFreela.UPWORK, 93L, "API Go");
        Proposta proposta = Proposta.builder()
            .valorProposto(new BigDecimal("150"))
            .mensagem("Proposta tecnica completa com escopo, prazo e entregaveis bem definidos.")
            .build();
        AcaoPendente acao = AcaoPendente.builder()
            .id(501L)
            .plataforma(PlataformaFreela.UPWORK)
            .projetoId(projeto.getId())
            .tipo(TipoAcaoPendente.ENVIAR_PROPOSTA)
            .status(StatusAcaoPendente.PENDENTE)
            .build();
        when(hooks.criarOuRecuperarAcaoProjeto(
            eq(projeto), eq(TipoAcaoPendente.ENVIAR_PROPOSTA), any(), any(), eq(proposta.getMensagem()),
            eq(ModoSistema.REGULAR)
        )).thenReturn(acao);
        // Outra instancia ficou com o projeto: a confirmacao antes do envio recusa a posse.
        doThrow(new ReivindicacaoPerdidaException(projeto.getId())).when(hooks).executarAcao(acao);

        org.junit.jupiter.api.Assertions.assertThrows(ReivindicacaoPerdidaException.class,
            () -> handler.tratarProposta(projeto, proposta, ModoSistema.REGULAR));

        verify(acaoPendenteRepository).descartarAcaoDeExecucaoSemPosse(eq(501L), any(), any());
        verify(projetoRepository, never()).save(any(Projeto.class));
        verify(hooks, never()).registrarInteracao(any(Projeto.class), any(), any(), any());
        org.junit.jupiter.api.Assertions.assertNotEquals(Boolean.TRUE, projeto.getFoiProcessado());
    }

    @Test
    void projetoPreferencialEhIgnoradoENaoRetentadoAutomaticamente() throws Exception {
        AutoAtendimentoProjetoHandler handler = handler(PlataformaFreela.FREELANCER, true, false);
        Projeto projeto = projeto(PlataformaFreela.FREELANCER, 92L, "Projeto preferencial");
        Proposta proposta = Proposta.builder()
            .valorProposto(new BigDecimal("250"))
            .mensagem("Proposta tecnica completa com escopo, prazo e entregaveis bem definidos.")
            .build();
        AcaoPendente acao = AcaoPendente.builder()
            .plataforma(PlataformaFreela.FREELANCER)
            .projetoId(projeto.getId())
            .tipo(TipoAcaoPendente.ENVIAR_PROPOSTA)
            .status(StatusAcaoPendente.PENDENTE)
            .build();
        when(hooks.criarOuRecuperarAcaoProjeto(
            eq(projeto), eq(TipoAcaoPendente.ENVIAR_PROPOSTA), any(), any(), eq(proposta.getMensagem()),
            eq(ModoSistema.REGULAR)
        )).thenReturn(acao);
        doThrow(new MarketplaceAdapterException(
            PlataformaFreela.FREELANCER,
            MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL,
            "Projeto exclusivo para Freelancer Preferencial"
        )).when(hooks).executarAcao(acao);

        handler.tratarProposta(projeto, proposta, ModoSistema.REGULAR);

        verify(acaoPendenteRepository).save(argThat(item ->
            item.getStatus() == StatusAcaoPendente.ERRO
                && item.getObservacaoUsuario().contains("Nao retentar automaticamente")
        ));
        verify(projetoRepository).save(projeto);
        assertEquals(StatusProjeto.IGNORADO, projeto.getStatus());
        assertEquals(true, projeto.getFoiProcessado());
        assertEquals("Projeto exclusivo para Freelancer Preferencial", projeto.getMotivoRecusa());
    }

    @ParameterizedTest
    @EnumSource(value = PlataformaFreela.class, names = {"FREELAS_99", "WORKANA"})
    void abordagemInicialEmEnsinamentoEnviaDiretoSemCriarRascunho(PlataformaFreela plataforma) throws Exception {
        AutoAtendimentoProjetoHandler handler = handler(plataforma, true, false);
        Projeto projeto = projeto(plataforma, 42L, "Landing page");
        when(acaoPendenteRepository.findByPlataformaAndProjetoIdAndTipoAndStatusOrderByDataCriacaoDesc(
            plataforma,
            projeto.getId(),
            TipoAcaoPendente.ENVIAR_PERGUNTA,
            StatusAcaoPendente.PENDENTE
        )).thenReturn(List.of());
        when(acaoPendenteRepository.save(any(AcaoPendente.class))).thenAnswer(invocation -> invocation.getArgument(0));

        handler.criarAbordagemInicialProjeto(projeto, ModoSistema.ENSINAMENTO);

        verify(hooks).executarAcao(any(AcaoPendente.class));
        verify(hooks, never()).criarOuRecuperarAcaoProjeto(any(), any(), any(), any(), any(), any());
        verify(hooks, never()).notificarAcaoPendente(any());
        verify(projetoRepository).save(projeto);
        assertEquals(StatusProjeto.EM_ANALISE, projeto.getStatus());
    }

    @ParameterizedTest
    @EnumSource(value = PlataformaFreela.class, names = {"FREELAS_99", "WORKANA"})
    void abordagemInicialComEscritaDesligadaCriaPendenciaDoCore(PlataformaFreela plataforma) throws Exception {
        AutoAtendimentoProjetoHandler handler = handler(plataforma, false, false);
        Projeto projeto = projeto(plataforma, 77L, "Sistema");
        AcaoPendente acao = AcaoPendente.builder()
            .plataforma(plataforma)
            .projetoId(projeto.getId())
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .build();
        when(hooks.criarOuRecuperarAcaoProjeto(
            eq(projeto),
            eq(TipoAcaoPendente.ENVIAR_PERGUNTA),
            any(),
            any(),
            any(),
            eq(ModoSistema.REGULAR)
        )).thenReturn(acao);

        handler.criarAbordagemInicialProjeto(projeto, ModoSistema.REGULAR);

        verify(hooks, never()).executarAcao(any(AcaoPendente.class));
        verify(hooks).criarOuRecuperarAcaoProjeto(
            eq(projeto),
            eq(TipoAcaoPendente.ENVIAR_PERGUNTA),
            any(),
            any(),
            any(),
            eq(ModoSistema.REGULAR)
        );
        verify(hooks).notificarAcaoPendente(acao);
        verify(projetoRepository).save(projeto);
        assertEquals(StatusProjeto.EM_ANALISE, projeto.getStatus());
    }

    @ParameterizedTest
    @EnumSource(value = PlataformaFreela.class, names = {"FREELAS_99", "WORKANA"})
    void abordagemInicialAutomaticaEnviaDiretoSemCriarRascunho(PlataformaFreela plataforma) throws Exception {
        AutoAtendimentoProjetoHandler handler = handler(plataforma, true, false);
        Projeto projeto = projeto(plataforma, 78L, "Sistema");

        when(acaoPendenteRepository.findByPlataformaAndProjetoIdAndTipoAndStatusOrderByDataCriacaoDesc(
            plataforma,
            projeto.getId(),
            TipoAcaoPendente.ENVIAR_PERGUNTA,
            StatusAcaoPendente.PENDENTE
        )).thenReturn(List.of());
        when(acaoPendenteRepository.save(any(AcaoPendente.class))).thenAnswer(invocation -> invocation.getArgument(0));

        handler.criarAbordagemInicialProjeto(projeto, ModoSistema.REGULAR);

        verify(hooks).executarAcao(any(AcaoPendente.class));
        verify(hooks, never()).validarERegistrarRascunho(any(), any(), any(), any(), any(), any(), any());
        verify(hooks, never()).criarOuRecuperarAcaoProjeto(any(), any(), any(), any(), any(), any());
        verify(hooks, never()).notificarAcaoPendente(any());
        verify(projetoRepository).save(projeto);
        assertEquals(StatusProjeto.EM_ANALISE, projeto.getStatus());
    }

    @ParameterizedTest
    @EnumSource(value = PlataformaFreela.class, names = {"FREELAS_99", "WORKANA"})
    void abordagemInicialAutomaticaNaoCriaPendenciaEmFalhaOperacionalAntesDoEnvio(PlataformaFreela plataforma) throws Exception {
        AutoAtendimentoProjetoHandler handler = handler(plataforma, true, false);
        Projeto projeto = projeto(plataforma, 178L, "Sistema");
        MarketplaceAdapterException falhaOperacional = new MarketplaceAdapterException(
            plataforma,
            MarketplaceAdapterErrorType.FALHA_AUTENTICACAO,
            "HTTP 403"
        );

        when(acaoPendenteRepository.findByPlataformaAndProjetoIdAndTipoAndStatusOrderByDataCriacaoDesc(
            plataforma,
            projeto.getId(),
            TipoAcaoPendente.ENVIAR_PERGUNTA,
            StatusAcaoPendente.PENDENTE
        )).thenReturn(List.of());
        when(acaoPendenteRepository.save(any(AcaoPendente.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(falhaOperacional).when(hooks).executarAcao(any(AcaoPendente.class));

        handler.criarAbordagemInicialProjeto(projeto, ModoSistema.REGULAR);

        verify(hooks, never()).criarOuRecuperarAcaoProjeto(any(), any(), any(), any(), any(), any());
        verify(hooks).registrarInteracao(
            eq(projeto),
            eq(TipoInteracao.MENSAGEM_ERRO),
            argThat(conteudo -> conteudo.contains("Abordagem inicial automatica nao enviada")),
            eq(ModoSistema.REGULAR)
        );
        verify(acaoPendenteRepository, atLeastOnce()).save(argThat(acao ->
            acao.getStatus() == StatusAcaoPendente.ERRO
                && acao.getObservacaoUsuario().contains("Nao criar pendencia de aprovacao")
        ));
        verify(hooks, never()).notificarAcaoPendente(any());
        verify(projetoRepository).save(projeto);
        assertEquals(StatusProjeto.EM_ANALISE, projeto.getStatus());
    }

    @Test
    void abordagemInicialAutomaticaWorkanaNaoRetentaLimiteSemanalDeConexoes() throws Exception {
        AutoAtendimentoProjetoHandler handler = handler(PlataformaFreela.WORKANA, true, false);
        Projeto projeto = projeto(PlataformaFreela.WORKANA, 2000485211091L, "Atendente virtual");
        MarketplaceAdapterException limiteConexoes = new MarketplaceAdapterException(
            PlataformaFreela.WORKANA,
            MarketplaceAdapterErrorType.LIMITE_CONEXOES,
            "Workana form de pergunta retornou HTTP 403: Poxa, voce nao tem mais conexoes disponiveis"
        );

        when(acaoPendenteRepository.findByPlataformaAndProjetoIdAndTipoAndStatusOrderByDataCriacaoDesc(
            PlataformaFreela.WORKANA,
            projeto.getId(),
            TipoAcaoPendente.ENVIAR_PERGUNTA,
            StatusAcaoPendente.PENDENTE
        )).thenReturn(List.of());
        when(acaoPendenteRepository.save(any(AcaoPendente.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(limiteConexoes).when(hooks).executarAcao(any(AcaoPendente.class));

        handler.criarAbordagemInicialProjeto(projeto, ModoSistema.REGULAR);

        verify(hooks, never()).criarOuRecuperarAcaoProjeto(any(), any(), any(), any(), any(), any());
        verify(hooks, never()).notificarAcaoPendente(any());
        verify(acaoPendenteRepository, atLeastOnce()).save(argThat(acao ->
            acao.getStatus() == StatusAcaoPendente.ERRO
                && acao.getObservacaoUsuario().contains("limite semanal de conexoes do Workana")
                && acao.getObservacaoUsuario().contains("Nao retentar automaticamente")
        ));
        verify(projetoRepository).save(projeto);
        assertEquals(StatusProjeto.EM_ANALISE, projeto.getStatus());
    }

    @ParameterizedTest
    @EnumSource(value = PlataformaFreela.class, names = {"FREELAS_99", "WORKANA"})
    void abordagemInicialAutomaticaNaoRetentaQuandoJaExisteErroOperacional(PlataformaFreela plataforma) throws Exception {
        AutoAtendimentoProjetoHandler handler = handler(plataforma, true, false);
        Projeto projeto = projeto(plataforma, 179L, "Sistema");
        AcaoPendente erroAnterior = AcaoPendente.builder()
            .id(99L)
            .plataforma(plataforma)
            .projetoId(projeto.getId())
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.ERRO)
            .resumo("Abordagem inicial automatica | Ja foi atendido?")
            .observacaoUsuario("Envio automatico nao executado por recusa definitiva do marketplace.")
            .build();

        when(acaoPendenteRepository.findByPlataformaAndProjetoIdAndTipoAndStatusOrderByDataCriacaoDesc(
            plataforma,
            projeto.getId(),
            TipoAcaoPendente.ENVIAR_PERGUNTA,
            StatusAcaoPendente.PENDENTE
        )).thenReturn(List.of());
        when(acaoPendenteRepository.findByPlataformaAndProjetoIdAndTipoAndStatusOrderByDataCriacaoDesc(
            plataforma,
            projeto.getId(),
            TipoAcaoPendente.ENVIAR_PERGUNTA,
            StatusAcaoPendente.ERRO
        )).thenReturn(List.of(erroAnterior));

        handler.criarAbordagemInicialProjeto(projeto, ModoSistema.REGULAR);

        verify(hooks, never()).executarAcao(any(AcaoPendente.class));
        verify(hooks, never()).notificarAcaoPendente(any());
        verify(projetoRepository).save(projeto);
        assertEquals(StatusProjeto.EM_ANALISE, projeto.getStatus());
    }

    @ParameterizedTest
    @EnumSource(value = PlataformaFreela.class, names = {"FREELAS_99", "WORKANA"})
    void abordagemInicialAutomaticaDescartaRascunhoAntigoEEnviaUmaAcaoNova(PlataformaFreela plataforma) throws Exception {
        AutoAtendimentoProjetoHandler handler = handler(plataforma, true, false);
        Projeto projeto = projeto(plataforma, 79L, "Sistema");
        AcaoPendente pendenteAntigo = AcaoPendente.builder()
            .id(11L)
            .plataforma(plataforma)
            .projetoId(projeto.getId())
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .tituloProjeto(projeto.getTitulo())
            .build();
        AcaoPendente pendenteDuplicado = AcaoPendente.builder()
            .id(12L)
            .plataforma(plataforma)
            .projetoId(projeto.getId())
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .tituloProjeto(projeto.getTitulo())
            .build();

        when(acaoPendenteRepository.findByPlataformaAndProjetoIdAndTipoAndStatusOrderByDataCriacaoDesc(
            plataforma,
            projeto.getId(),
            TipoAcaoPendente.ENVIAR_PERGUNTA,
            StatusAcaoPendente.PENDENTE
        )).thenReturn(List.of(pendenteAntigo, pendenteDuplicado));
        when(acaoPendenteRepository.save(any(AcaoPendente.class))).thenAnswer(invocation -> invocation.getArgument(0));

        handler.criarAbordagemInicialProjeto(projeto, ModoSistema.REGULAR);

        verify(acaoPendenteRepository).save(argThat(acao ->
            acao == pendenteAntigo && acao.getStatus() == StatusAcaoPendente.REJEITADA
        ));
        verify(acaoPendenteRepository).save(argThat(acao ->
            acao == pendenteDuplicado && acao.getStatus() == StatusAcaoPendente.REJEITADA
        ));
        verify(hooks).executarAcao(argThat(acao ->
            acao != pendenteAntigo
                && acao.getTipo() == TipoAcaoPendente.ENVIAR_PERGUNTA
                && acao.getStatus() == StatusAcaoPendente.EXECUTADA
        ));
        verify(hooks, never()).notificarAcaoPendente(any());
    }

    @ParameterizedTest
    @EnumSource(value = PlataformaFreela.class, names = {"FREELAS_99", "WORKANA"})
    void abordagemInicialAutomaticaNaoReenviaQuandoJaExisteEnvioExecutado(PlataformaFreela plataforma) throws Exception {
        AutoAtendimentoProjetoHandler handler = handler(plataforma, true, false);
        Projeto projeto = projeto(plataforma, 80L, "Sistema");
        when(acaoPendenteRepository.findByPlataformaAndProjetoIdAndTipoAndStatusOrderByDataCriacaoDesc(
            plataforma,
            projeto.getId(),
            TipoAcaoPendente.ENVIAR_PERGUNTA,
            StatusAcaoPendente.PENDENTE
        )).thenReturn(List.of());
        when(acaoPendenteRepository.countByPlataformaAndProjetoIdAndTipoAndStatusIn(
            eq(plataforma),
            eq(projeto.getId()),
            eq(TipoAcaoPendente.ENVIAR_PERGUNTA),
            any()
        )).thenReturn(1L);

        handler.criarAbordagemInicialProjeto(projeto, ModoSistema.REGULAR);

        verify(hooks, never()).executarAcao(any(AcaoPendente.class));
        verify(hooks, never()).criarOuRecuperarAcaoProjeto(any(), any(), any(), any(), any(), any());
        verify(hooks, never()).notificarAcaoPendente(any());
        verify(projetoRepository).save(projeto);
    }

    private AutoAtendimentoProjetoHandler handler(PlataformaFreela plataforma, boolean realWriteEnabled, boolean simulateEnabled) {
        MarketplaceAdapter adapter = mock(MarketplaceAdapter.class);
        when(adapter.plataforma()).thenReturn(plataforma);
        when(adapter.capabilities()).thenReturn(new MarketplaceCapabilities(true, false, true, false, false, false, false));

        return new AutoAtendimentoProjetoHandler(
            objectMapper,
            acaoPendenteRepository,
            projetoRepository,
            new OperationalSafetyPolicy(),
            new MarketplaceAdapterRegistry(List.of(adapter), plataforma.key()),
            gerenciadorConexoes,
            hooks,
            realWriteEnabled,
            simulateEnabled
        );
    }

    private Projeto projeto(PlataformaFreela plataforma, Long idExterno, String titulo) {
        return Projeto.builder()
            .id(plataforma.idInterno(idExterno))
            .idExterno(idExterno)
            .plataforma(plataforma)
            .titulo(titulo)
            .urlProjeto("https://example.local/job/" + titulo.toLowerCase().replace(" ", "-"))
            .build();
    }
}
