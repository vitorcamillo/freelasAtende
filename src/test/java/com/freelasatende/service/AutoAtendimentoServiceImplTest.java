package com.freelasatende.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.dto.AvaliacaoResultado;
import com.freelasatende.model.dto.ConexoesDisponiveis;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Cliente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.StatusMensagemCliente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.repository.MensagemClienteRegistroRepository;
import com.freelasatende.repository.ProjetoRepository;
import com.freelasatende.service.impl.AutoAtendimentoServiceImpl;
import com.freelasatende.service.interfaceServico.AprendizadoService;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.ScraperService;
import com.freelasatende.service.interfaceServico.TelegramService;
import com.freelasatende.service.interfaceServico.HistoricoService;
import com.freelasatende.service.impl.TelegramServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;

/**
 * Testes de integração para AutoAtendimentoService.
 * Usa SpringBootTest com escrita externa mockada.
 */
@SpringBootTest(properties = {
    "scheduling.enabled=false",
    "ia.memoria-arquivo=target/test-memoria-autoatendimento.json"
})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutoAtendimentoServiceImplTest extends AutoAtendimentoServiceImplTestSupport {
    @Test
    @DisplayName("Deve processar projeto e criar pendência para aprovação")
    void deveProcessarProjetoECriarPendencia() throws Exception {
        Projeto projeto = projetoBase();

        assertDoesNotThrow(() -> autoAtendimentoService.processarProjeto(projeto, ModoSistema.REGULAR));

        assertEquals(1, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        AcaoPendente acao = acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.PENDENTE).get(0);
        Map<?, ?> payload = objectMapper.readValue(acao.getPayloadJson(), Map.class);
        assertEquals(TipoAcaoPendente.ENVIAR_PERGUNTA, acao.getTipo());
        assertEquals("Ja foi atendido?", payload.get("mensagem"));
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
        verify(avaliadorIaService, never()).gerarDuvidas(any(Projeto.class));
        verify(avaliadorIaService, never()).sugerirProposta(any(Projeto.class));
    }

    @Test
    @DisplayName("Projeto aprovado cria apenas pendência Telegram para não duplicar texto")
    void projetoAprovadoCriaApenasPendenciaTelegramParaNaoDuplicarTexto() {
        Projeto projeto = projetoBase();
        projeto.setId(sequencia.incrementAndGet());
        when(avaliadorIaService.avaliarProjeto(any(Projeto.class)))
            .thenReturn(AvaliacaoResultado.builder()
                .capaz(true)
                .motivoInteresse("site institucional dentro do escopo")
                .build());
        when(avaliadorIaService.gerarDuvidas(any(Projeto.class)))
            .thenReturn(List.of("Tem alguma urgencia ou prazo que eu precise considerar?"));

        autoAtendimentoService.processarProjeto(projeto, ModoSistema.ENSINAMENTO);

        ArgumentCaptor<String> mensagemCaptor = ArgumentCaptor.forClass(String.class);
        verify(telegramService, never()).notificarProjetoInteressante(any(Projeto.class), any());
        verify(telegramService).enviarAcaoPendenteComBotoes(mensagemCaptor.capture(), anyLong());
        assertTrue(mensagemCaptor.getValue().contains("Pergunta que sera enviada se aprovar:\n```"));
        assertFalse(mensagemCaptor.getValue().contains("Cliente:"));
        assertTrue(mensagemCaptor.getValue().contains("Leitura rapida:\n```"));
        assertFalse(mensagemCaptor.getValue().contains("Proximo passo"));
        assertFalse(mensagemCaptor.getValue().contains("Contexto lido:"));
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
    }

    @Test
    @DisplayName("Site aprovado vira abordagem inicial curta e não proposta barata")
    void siteAprovadoViraAbordagemInicialCurta() throws Exception {
        Long projetoId = sequencia.incrementAndGet();
        Projeto projetoSemValor = Projeto.builder()
            .id(projetoId)
            .titulo("Site para empresa de festas infantis")
            .descricao("Mostrar endereço, botão do WhatsApp, formulário de contato e visual moderno.")
            .categoria("Web, Mobile & Software")
            .subcategoria("Desenvolvimento Web")
            .urlProjeto("https://www.99freelas.com.br/project/site-festas-" + projetoId)
            .orcamento(null)
            .mediaProposta(null)
            .valorMinimo(BigDecimal.valueOf(50))
            .dataCriacao(LocalDateTime.now())
            .build();

        when(scraperService.buscarDetalhesProjeto(eq(projetoId), nullable(String.class))).thenReturn(projetoSemValor);
        when(avaliadorIaService.gerarDuvidas(any(Projeto.class))).thenReturn(List.of());
        when(avaliadorIaService.sugerirProposta(any(Projeto.class))).thenReturn(Proposta.builder()
            .projetoId(projetoId)
            .valorProposto(BigDecimal.valueOf(50))
            .valorMinimo(BigDecimal.valueOf(50))
            .mediaMercado(null)
            .build());

        autoAtendimentoService.processarProjeto(projetoSemValor, ModoSistema.ENSINAMENTO);

        AcaoPendente acao = acaoPendenteRepository.findAll().stream()
            .filter(a -> a.getProjetoId().equals(projetoId))
            .findFirst()
            .orElseThrow();
        Map<?, ?> payload = objectMapper.readValue(acao.getPayloadJson(), Map.class);

        assertEquals(TipoAcaoPendente.ENVIAR_PERGUNTA, acao.getTipo());
        assertEquals("Ja foi atendido?", payload.get("mensagem"));
        verify(avaliadorIaService, never()).gerarTextoProposta(any(Projeto.class), any(Proposta.class));
        verify(avaliadorIaService, never()).sugerirProposta(any(Projeto.class));
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
    }

    @Test
    @DisplayName("Deve processar projeto nulo sem exceção")
    void deveProcessarProjetoNuloSemExcecao() {
        // Act & Assert
        assertDoesNotThrow(() -> autoAtendimentoService.processarProjeto(null, ModoSistema.REGULAR));
    }

    @Test
    @DisplayName("Deve sincronizar inbox sem exceção")
    void deveSincronizarInboxSemExcecao() {
        when(scraperService.listarConversasInbox()).thenReturn(List.of());

        // Act & Assert
        assertDoesNotThrow(() -> autoAtendimentoService.sincronizarInbox(ModoSistema.REGULAR));
    }

    @Test
    @DisplayName("Deve contar ações pendentes")
    void deveContarAcoesPendentes() {
        // Arrange: criar ação pendente de teste
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(sequencia.incrementAndGet())
            .tipo(TipoAcaoPendente.ENVIAR_PROPOSTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Projeto de Teste")
            .resumo("Teste de proposta")
            .payloadJson("{}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acaoPendenteRepository.save(acao);

        // Act
        long pendentes = autoAtendimentoService.contarAcoesPendentes();

        // Assert
        assertTrue(pendentes >= 1, "Deveria ter pelo menos 1 ação pendente");
    }

    @Test
    @DisplayName("Deve retornar zero quando não há ações pendentes")
    void deveRetornarZeroQuandoNaoHaPendencia() {
        // Arrange: garantir que não há pendências
        acaoPendenteRepository.deleteAll();

        // Act
        long pendentes = autoAtendimentoService.contarAcoesPendentes();

        // Assert
        assertEquals(0, pendentes, "Não deveria ter ações pendentes");
    }

    @Test
    @DisplayName("Deve rejeitar ação pendente e registrar aprendizado")
    void deveRejeitarAcaoPendente() {
        // Arrange: criar ação pendente
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(sequencia.incrementAndGet())
            .tipo(TipoAcaoPendente.ENVIAR_PROPOSTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Projeto Teste Rejeição")
            .resumo("Proposta de teste")
            .payloadJson("{}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);

        // Act
        String resultado = autoAtendimentoService.processarFeedback(acao.getId(), false, "Não quero enviar");

        // Assert
        assertTrue(resultado.contains("rejeitada") || resultado.contains("Rejeitada"));
        assertEquals(StatusAcaoPendente.REJEITADA, acaoPendenteRepository.findById(acao.getId()).get().getStatus());
    }

    @Test
    @DisplayName("Feedback deve falhar quando ação não existe")
    void feedbackDeveFalharQuandoAcaoNaoExiste() {
        // Act
        String retorno = autoAtendimentoService.processarFeedback(999999L, true, "ok");

        // Assert
        assertTrue(retorno.contains("não encontrada") || retorno.contains("Não encontrada"));
    }

    @Test
    @DisplayName("Feedback deve avisar quando ação já foi tratada")
    void feedbackDeveAvisarQuandoAcaoJaFoiTratada() {
        // Arrange: criar ação já executada
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(sequencia.incrementAndGet())
            .tipo(TipoAcaoPendente.ENVIAR_PROPOSTA)
            .status(StatusAcaoPendente.EXECUTADA)
            .modoOrigem(ModoSistema.REGULAR)
            .payloadJson("{}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);

        // Act
        String retorno = autoAtendimentoService.processarFeedback(acao.getId(), true, "dup");

        // Assert
        assertTrue(retorno.contains("já foi tratada") || retorno.contains("ja foi tratada"));
    }

    @Test
    @DisplayName("Aprovação de ação que falha deve marcar erro")
    @Disabled("Requer scraper real configurado para testar falha de envio")
    void feedbackAprovadoComFalhaMarcaErro() {
        // Arrange: criar ação pendente
        Long projetoId = sequencia.incrementAndGet();
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(projetoId)
            .tipo(TipoAcaoPendente.ENVIAR_PROPOSTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Projeto Teste")
            .payloadJson("{\"projetoId\":" + projetoId + ",\"tituloProjeto\":\"Teste\",\"urlProjeto\":\"https://www.99freelas.com.br/project/teste\",\"valorProposto\":1500,\"mensagem\":\"Texto\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);

        // Act
        String retorno = autoAtendimentoService.processarFeedback(acao.getId(), true, "enviar");

        // Assert: deve indicar falha
        assertTrue(retorno.contains("falhou") || retorno.contains("Falhou"));
    }

    @Test
    @DisplayName("Cliente sem avaliação deve passar quando bloqueio desativado")
    @Disabled("Este teste requer configuração específica do serviço")
    void clienteSemAvaliacaoDevePassarQuandoBloqueioDesativado() {
        // Teste de configuração específico
    }

    @Test
    @DisplayName("Sugerir proposta com dados básicos deve funcionar")
    void sugestaoPropostaComDadosBasicos() {
        // Arrange
        Projeto projeto = projetoBase();
        projeto.setId(sequencia.incrementAndGet());

        // Act
        var proposta = avaliadorIaService.sugerirProposta(projeto);

        // Assert
        assertNotNull(proposta);
        assertEquals(projeto.getId(), proposta.getProjetoId());
    }

    @Test
    @DisplayName("Modo LIVRE cria rascunho e nao envia direto")
    void modoLivreCriaRascunhoENaoEnviaDireto() {
        Projeto projeto = projetoBase();
        when(avaliadorIaService.gerarDuvidas(any(Projeto.class))).thenReturn(List.of());

        autoAtendimentoService.processarProjeto(projeto, ModoSistema.LIVRE);

        assertEquals(1, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
    }

    @Test
    @DisplayName("Modo liberado envia abordagem inicial para projeto atendivel com margem de conexoes")
    void modoLiberadoEnviaAbordagemInicialParaProjetoAtendivelComMargemConexoes() {
        habilitarEscritaRealMockada();
        Projeto projeto = projetoBase();
        Long projetoId = sequencia.incrementAndGet();
        projeto.setId(projetoId);
        when(gerenciadorConexoesService.temConexoesDisponiveis()).thenReturn(true);
        when(scraperService.obterConexoesDisponiveis())
            .thenReturn(ConexoesDisponiveis.builder().total(900).renovaveis(100).naoExpiraveis(800).build());

        autoAtendimentoService.processarProjeto(projeto, ModoSistema.REGULAR);

        assertEquals(0, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        assertEquals(1, acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.EXECUTADA).size());
        verify(scraperService, times(1)).enviarPergunta(eq(projetoId), eq("Ja foi atendido?"));
        verify(gerenciadorConexoesService, times(1)).reservarConexao();
        verify(avaliadorIaService, never()).gerarDuvidas(any(Projeto.class));
        verify(avaliadorIaService, never()).sugerirProposta(any(Projeto.class));
    }

    @Test
    @DisplayName("Modo liberado envia abordagem inicial mesmo com cliente abaixo da reputacao minima")
    void modoLiberadoEnviaAbordagemInicialMesmoComClienteAbaixoDaReputacaoMinima() {
        habilitarEscritaRealMockada();
        Projeto projeto = projetoBase();
        Long projetoId = sequencia.incrementAndGet();
        projeto.setId(projetoId);
        when(scraperService.buscarCliente(projetoId))
            .thenReturn(Cliente.builder().id(17L).mediaNota(1.0).build());
        when(gerenciadorConexoesService.temConexoesDisponiveis()).thenReturn(true);
        when(scraperService.obterConexoesDisponiveis())
            .thenReturn(ConexoesDisponiveis.builder().total(900).renovaveis(100).naoExpiraveis(800).build());

        autoAtendimentoService.processarProjeto(projeto, ModoSistema.LIVRE);

        assertEquals(0, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        assertEquals(1, acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.EXECUTADA).size());
        verify(scraperService, times(1)).enviarPergunta(eq(projetoId), eq("Ja foi atendido?"));
        verify(gerenciadorConexoesService, times(1)).reservarConexao();
    }

    @Test
    @DisplayName("Modo liberado envia abordagem inicial sem aplicar reserva artificial de conexoes")
    void modoLiberadoEnviaAbordagemInicialSemAplicarReservaArtificial() {
        habilitarEscritaRealMockada();
        ReflectionTestUtils.setField(autoAtendimentoService, "minConexoesReserva", 700);
        Projeto projeto = projetoBase();
        Long projetoId = sequencia.incrementAndGet();
        projeto.setId(projetoId);
        when(gerenciadorConexoesService.temConexoesDisponiveis()).thenReturn(true);
        when(scraperService.obterConexoesDisponiveis())
            .thenReturn(ConexoesDisponiveis.builder().total(700).renovaveis(0).naoExpiraveis(700).build());

        autoAtendimentoService.processarProjeto(projeto, ModoSistema.REGULAR);

        assertEquals(0, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        assertEquals(1, acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.EXECUTADA).size());
        verify(scraperService, times(1)).enviarPergunta(eq(projetoId), eq("Ja foi atendido?"));
        verify(gerenciadorConexoesService, times(1)).reservarConexao();
    }

    @Test
    @DisplayName("Reprocessamento explicito recupera ignorado sem apagar historico e envia uma vez")
    void reprocessamentoExplicitoRecuperaIgnoradoSemApagarHistoricoEEnviaUmaVez() {
        habilitarEscritaRealMockada();
        Long projetoId = sequencia.incrementAndGet();
        Projeto ignorado = projetoBase();
        ignorado.setId(projetoId);
        ignorado.setStatus(com.freelasatende.model.enums.StatusProjeto.IGNORADO);
        ignorado.setFoiProcessado(true);
        ignorado.setMotivoRecusa("escopo complexo ainda precisa ser detalhado");
        projetoRepository.save(ignorado);
        historicoService.registrarInteracao(com.freelasatende.model.entity.Interacao.builder()
            .projetoId(projetoId)
            .projetoTitulo(ignorado.getTitulo())
            .tipo(com.freelasatende.model.enums.TipoInteracao.PROJETO_IGNORADO)
            .conteudo("recusa anterior")
            .build());
        when(gerenciadorConexoesService.temConexoesDisponiveis()).thenReturn(true);

        String retorno = autoAtendimentoService.reprocessarProjetoIgnorado(projetoId, ModoSistema.LIVRE);
        String segundoRetorno = autoAtendimentoService.reprocessarProjetoIgnorado(projetoId, ModoSistema.LIVRE);

        assertTrue(retorno.contains("reprocessado"));
        assertTrue(segundoRetorno.contains("nao esta ignorado"));
        assertTrue(historicoService.projetoJaProcessado(projetoId));
        verify(avaliadorIaService, times(1)).reavaliarProjeto(any(Projeto.class));
        verify(scraperService, times(1)).enviarPergunta(eq(projetoId), eq("Ja foi atendido?"));
        verify(gerenciadorConexoesService, times(1)).reservarConexao();
    }

    @Test
    @DisplayName("Reprocessamento explicito recupera projeto que so tinha erro tecnico")
    void reprocessamentoExplicitoRecuperaProjetoComErroTecnico() {
        habilitarEscritaRealMockada();
        Long projetoId = sequencia.incrementAndGet();
        historicoService.registrarInteracao(com.freelasatende.model.entity.Interacao.builder()
            .projetoId(projetoId)
            .projetoTitulo("Projeto com falha temporaria")
            .tipo(TipoInteracao.ERRO_SISTEMA)
            .conteudo("Falha tecnica ao avaliar projeto")
            .build());
        when(gerenciadorConexoesService.temConexoesDisponiveis()).thenReturn(true);

        String retorno = autoAtendimentoService.reprocessarProjetoIgnorado(projetoId, ModoSistema.LIVRE);

        assertTrue(retorno.contains("reprocessado"));
        assertTrue(historicoService.projetoJaProcessado(projetoId));
        assertEquals(com.freelasatende.model.enums.StatusProjeto.EM_ANALISE,
            projetoRepository.findById(projetoId).orElseThrow().getStatus());
        verify(avaliadorIaService, times(1)).reavaliarProjeto(any(Projeto.class));
        verify(scraperService, times(1)).enviarPergunta(eq(projetoId), eq("Ja foi atendido?"));
        verify(gerenciadorConexoesService, times(1)).reservarConexao();
    }

    @Test
    @DisplayName("Reprocessamento explicito informa falha tecnica sem marcar ignorado como recuperado")
    void reprocessamentoExplicitoInformaFalhaTecnica() {
        Long projetoId = sequencia.incrementAndGet();
        Projeto ignorado = projetoBase();
        ignorado.setId(projetoId);
        ignorado.setStatus(com.freelasatende.model.enums.StatusProjeto.IGNORADO);
        ignorado.setFoiProcessado(true);
        projetoRepository.save(ignorado);
        when(avaliadorIaService.reavaliarProjeto(any(Projeto.class)))
            .thenThrow(new IllegalStateException("falha simulada"));

        String retorno = autoAtendimentoService.reprocessarProjetoIgnorado(projetoId, ModoSistema.LIVRE);

        assertTrue(retorno.startsWith("Erro ao reprocessar projeto"));
        assertEquals(com.freelasatende.model.enums.StatusProjeto.IGNORADO,
            projetoRepository.findById(projetoId).orElseThrow().getStatus());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
    }

    @Test
    @DisplayName("Modo liberado adia abordagem inicial sem pedir aprovacao quando limite diario atingiu")
    void modoLiberadoAdiaAbordagemInicialSemPedirAprovacaoComLimiteDiarioAtingido() {
        habilitarEscritaRealMockada();
        Projeto projeto = projetoBase();
        Long projetoId = sequencia.incrementAndGet();
        projeto.setId(projetoId);
        when(gerenciadorConexoesService.temConexoesDisponiveis()).thenReturn(false);
        when(scraperService.obterConexoesDisponiveis())
            .thenReturn(ConexoesDisponiveis.builder().total(900).renovaveis(100).naoExpiraveis(800).build());

        autoAtendimentoService.processarProjeto(projeto, ModoSistema.LIVRE);

        assertEquals(0, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        assertFalse(historicoService.projetoJaProcessado(projetoId));
        verify(avaliadorIaService, never()).avaliarProjeto(any(Projeto.class));
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        verify(gerenciadorConexoesService, never()).registrarUsoConexao();
    }

}
