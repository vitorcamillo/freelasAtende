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
class AutoAtendimentoServiceImplModoStressTest extends AutoAtendimentoServiceImplTestSupport {
    @ParameterizedTest(name = "Projeto em modo {0} fica pendente sem envio direto")
    @EnumSource(ModoSistema.class)
    @DisplayName("Todos os modos abrem projeto com pergunta curta e aguardam aprovação humana")
    void todosModosRascunhamProjetoEAguardamAprovacaoHumana(ModoSistema modo) {
        Projeto projeto = projetoBase();
        projeto.setId(4_000L + modo.ordinal());
        when(avaliadorIaService.gerarDuvidas(any(Projeto.class))).thenReturn(List.of());

        autoAtendimentoService.processarProjeto(projeto, modo);

        List<AcaoPendente> pendencias = acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.PENDENTE);
        assertEquals(1, pendencias.size());
        assertEquals(TipoAcaoPendente.ENVIAR_PERGUNTA, pendencias.get(0).getTipo());
        assertEquals(modo, pendencias.get(0).getModoOrigem());
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
    }

    @ParameterizedTest(name = "Inbox em modo {0} fica pendente sem envio direto")
    @EnumSource(ModoSistema.class)
    @DisplayName("Todos os modos rascunham resposta de inbox e aguardam aprovação humana")
    void todosModosRascunhamRespostaInboxEAguardamAprovacaoHumana(ModoSistema modo) {
        long conversaId = 4_500L + modo.ordinal();
        ConversaResumo conversa = ConversaResumo.builder()
            .idConversa(conversaId)
            .idProjeto(4_600L + modo.ordinal())
            .nomeProjeto("Projeto inbox modo " + modo)
            .nomeCliente("Cliente")
            .idCliente(1613360L)
            .idFreelancer(269409L)
            .build();
        MensagemConversa clienteNova = MensagemConversa.builder()
            .id(4_700L + modo.ordinal())
            .idConversa(conversaId)
            .idPessoa(1613360L)
            .nomePessoa("Cliente")
            .texto("Consegue confirmar o próximo passo?")
            .dataCriacaoMillis(2000L)
            .enviadaPorMim(false)
            .build();
        when(scraperService.listarConversasInbox()).thenReturn(List.of(conversa));
        when(scraperService.listarMensagensConversa(conversaId)).thenReturn(List.of(clienteNova));

        autoAtendimentoService.sincronizarInbox(modo);

        List<AcaoPendente> pendencias = acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.PENDENTE);
        assertEquals(1, pendencias.size());
        assertEquals(TipoAcaoPendente.RESPONDER_CONVERSA, pendencias.get(0).getTipo());
        assertEquals(modo, pendencias.get(0).getModoOrigem());
        assertEquals(conversaId, pendencias.get(0).getConversaId());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
    }

    @Test
    @DisplayName("Processar mesmo projeto varias vezes nao cria spam")
    void processarMesmoProjetoVariasVezesNaoCriaSpam() {
        Projeto projeto = projetoBase();

        autoAtendimentoService.processarProjeto(projeto, ModoSistema.LIVRE);
        autoAtendimentoService.processarProjeto(projeto, ModoSistema.LIVRE);
        autoAtendimentoService.processarProjeto(projeto, ModoSistema.LIVRE);

        assertEquals(1, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
    }

    @Test
    @DisplayName("Processar mesmo projeto em paralelo não duplica pendência")
    void processarMesmoProjetoEmParaleloNaoDuplicaPendencia() throws Exception {
        Projeto projeto = projetoBase();
        Long projetoId = sequencia.incrementAndGet();
        projeto.setId(projetoId);
        when(avaliadorIaService.gerarDuvidas(any(Projeto.class))).thenReturn(List.of());
        int totalThreads = 8;
        CountDownLatch inicio = new CountDownLatch(1);
        CountDownLatch fim = new CountDownLatch(totalThreads);
        AtomicReference<Throwable> falha = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);

        for (int i = 0; i < totalThreads; i++) {
            executor.submit(() -> {
                try {
                    inicio.await(2, TimeUnit.SECONDS);
                    autoAtendimentoService.processarProjeto(projeto, ModoSistema.LIVRE);
                } catch (Throwable t) {
                    falha.compareAndSet(null, t);
                } finally {
                    fim.countDown();
                }
            });
        }

        inicio.countDown();
        assertTrue(fim.await(8, TimeUnit.SECONDS), "threads não finalizaram");
        executor.shutdownNow();
        if (falha.get() != null) {
            fail(falha.get());
        }

        long pendenciasDoProjeto = acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.PENDENTE)
            .stream()
            .filter(acao -> projetoId.equals(acao.getProjetoId()))
            .count();
        assertEquals(1, pendenciasDoProjeto);
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
    }

    @Test
    @DisplayName("Stress: muitos projetos viram pendências únicas sem envio automático")
    void stressMuitosProjetosViramPendenciasUnicasSemEnvioAutomatico() {
        int totalProjetos = 35;
        when(scraperService.buscarDetalhesProjeto(anyLong(), nullable(String.class)))
            .thenAnswer(invocation -> {
                Long id = invocation.getArgument(0);
                return Projeto.builder()
                    .id(id)
                    .titulo("Projeto stress " + id)
                    .descricao("Landing page com formulário, conteúdo institucional e prazo a confirmar.")
                    .categoria("Web")
                    .urlProjeto("https://www.99freelas.com.br/project/stress-" + id)
                    .orcamento(BigDecimal.valueOf(900 + (id % 10) * 50))
                    .numeroPropostas((int) (id % 5) + 1)
                    .dataCriacao(LocalDateTime.now())
                    .build();
            });
        when(avaliadorIaService.gerarDuvidas(any(Projeto.class)))
            .thenAnswer(invocation -> {
                Projeto projeto = invocation.getArgument(0);
                return List.of("Qual entrega precisa sair primeiro no projeto " + projeto.getId() + "?");
            });

        assertTimeout(Duration.ofSeconds(12), () -> {
            for (int i = 0; i < totalProjetos; i++) {
                Long id = sequencia.incrementAndGet();
                autoAtendimentoService.processarProjeto(Projeto.builder()
                    .id(id)
                    .titulo("Entrada stress " + id)
                    .urlProjeto("https://www.99freelas.com.br/project/entrada-stress-" + id)
                    .dataCriacao(LocalDateTime.now())
                    .build(), ModoSistema.ENSINAMENTO);
            }
        });

        List<AcaoPendente> pendencias = acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.PENDENTE);
        assertEquals(totalProjetos, pendencias.size());
        assertEquals(totalProjetos, pendencias.stream().map(AcaoPendente::getProjetoId).distinct().count());
        assertTrue(pendencias.stream().allMatch(acao -> acao.getTipo() == TipoAcaoPendente.ENVIAR_PERGUNTA));
        assertTrue(pendencias.stream().allMatch(acao -> acao.getPayloadJson().contains("informacoesConfirmadas")));
        assertTrue(pendencias.stream().allMatch(acao -> acao.getPayloadJson().contains("informacoesPendentes")));
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
    }

    @Test
    @DisplayName("Stress: inbox em lote deduplica conversas e não responde fala própria")
    void stressInboxEmLoteDeduplicaConversasENaoRespondeFalaPropria() {
        int totalConversas = 24;
        int conversasAcionaveis = 16;
        List<ConversaResumo> conversas = new java.util.ArrayList<>();
        for (int i = 0; i < totalConversas; i++) {
            long conversaId = 7000L + i;
            conversas.add(ConversaResumo.builder()
                .idConversa(conversaId)
                .idProjeto(8000L + i)
                .nomeProjeto("Inbox stress " + i)
                .nomeCliente("Cliente " + i)
                .idCliente(1600000L + i)
                .idFreelancer(269409L)
                .nomeFreelancer("Vitor Camillo")
                .build());
        }
        when(scraperService.listarConversasInbox()).thenReturn(conversas);
        when(scraperService.listarMensagensConversa(anyLong()))
            .thenAnswer(invocation -> {
                Long conversaId = invocation.getArgument(0);
                boolean somenteMinha = conversaId % 3 == 0;
                MensagemConversa minha = MensagemConversa.builder()
                    .id(conversaId * 10)
                    .idConversa(conversaId)
                    .idPessoa(269409L)
                    .nomePessoa("Vitor Camillo")
                    .texto("Oi, estou avaliando por aqui.")
                    .dataCriacaoMillis(1000L)
                    .enviadaPorMim(true)
                    .build();
                if (somenteMinha) {
                    return List.of(minha);
                }
                MensagemConversa cliente = MensagemConversa.builder()
                    .id(conversaId * 10 + 1)
                    .idConversa(conversaId)
                    .idPessoa(1600000L + conversaId)
                    .nomePessoa("Cliente")
                    .texto("Consegue confirmar o próximo passo?")
                    .dataCriacaoMillis(2000L)
                    .enviadaPorMim(false)
                    .build();
                return List.of(minha, cliente);
            });
        when(avaliadorIaService.sugerirRespostaConversa(any(Projeto.class), anyString(), anyString()))
            .thenAnswer(invocation -> {
                Projeto projeto = invocation.getArgument(0);
                return "Perfeito, obrigado pelo retorno. Vou revisar o projeto "
                    + projeto.getId()
                    + " e sigo por aqui com o próximo passo.";
            });

        assertTimeout(Duration.ofSeconds(12), () -> {
            autoAtendimentoService.sincronizarInbox(ModoSistema.ENSINAMENTO);
            autoAtendimentoService.sincronizarInbox(ModoSistema.ENSINAMENTO);
        });

        List<AcaoPendente> pendencias = acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.PENDENTE);
        assertEquals(conversasAcionaveis, pendencias.size());
        assertTrue(pendencias.stream().allMatch(acao -> acao.getTipo() == TipoAcaoPendente.RESPONDER_CONVERSA));
        assertEquals(conversasAcionaveis, pendencias.stream().map(AcaoPendente::getConversaId).distinct().count());
        assertTrue(pendencias.stream().allMatch(acao -> acao.getPayloadJson().contains("informacoesConfirmadas")));
        verify(avaliadorIaService, times(conversasAcionaveis)).sugerirRespostaConversa(any(Projeto.class), anyString(), anyString());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
    }

    @Test
    @DisplayName("Stress: aprovar muitos rascunhos inseguros bloqueia tudo")
    void stressAprovarMuitosRascunhosInsegurosBloqueiaTudo() {
        int totalAcoes = 20;
        List<AcaoPendente> acoes = new java.util.ArrayList<>();
        for (int i = 0; i < totalAcoes; i++) {
            Long projetoId = sequencia.incrementAndGet();
            acoes.add(acaoPendenteRepository.save(AcaoPendente.builder()
                .projetoId(projetoId)
                .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
                .status(StatusAcaoPendente.PENDENTE)
                .modoOrigem(ModoSistema.REGULAR)
                .tituloProjeto("Bloqueio stress " + i)
                .resumo("Rascunho inseguro em lote")
                .payloadJson("{\"projetoId\":" + projetoId
                    + ",\"tituloProjeto\":\"Bloqueio stress " + i + "\""
                    + ",\"mensagem\":\"Me chama no WhatsApp para fechar por fora da plataforma.\"}")
                .dataCriacao(LocalDateTime.now())
                .dataAtualizacao(LocalDateTime.now())
                .build()));
        }

        assertTimeout(Duration.ofSeconds(8), () -> {
            for (AcaoPendente acao : acoes) {
                String retorno = autoAtendimentoService.processarFeedback(acao.getId(), true, "aprovado");
                assertTrue(retorno.toLowerCase().contains("bloqueada"), retorno);
            }
        });

        assertEquals(totalAcoes, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
    }

    @Test
    @DisplayName("Primeiro contato usa mensagem exata e não mistura dúvidas")
    void primeiroContatoUsaMensagemExataENaoMisturaDuvidas() throws Exception {
        Projeto projeto = projetoBase();
        when(avaliadorIaService.gerarDuvidas(any(Projeto.class))).thenReturn(List.of(
            "Tem algum site atual que eu precise considerar?",
            "Tem alguma urgencia ou prazo que eu precise considerar?"
        ));

        autoAtendimentoService.processarProjeto(projeto, ModoSistema.REGULAR);

        AcaoPendente acao = acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.PENDENTE).get(0);
        Map<?, ?> payload = objectMapper.readValue(acao.getPayloadJson(), Map.class);
        String mensagem = String.valueOf(payload.get("mensagem"));
        assertEquals("Ja foi atendido?", mensagem);
        assertFalse(mensagem.contains("1."));
        assertFalse(mensagem.contains("2."));
        assertFalse(mensagem.toLowerCase().contains("site atual"));
        assertFalse(mensagem.toLowerCase().contains("urgencia"));
        assertFalse(mensagem.toLowerCase().contains("prazo que eu precise considerar"));
        assertFalse(mensagem.toLowerCase().contains("com isso ja consigo"));
        assertTrue(String.valueOf(payload.get("perguntasParaCliente")).contains("Ja foi atendido?"));
    }

    @Test
    @DisplayName("Abordagem inicial separa fatos e aguarda resposta real")
    void abordagemInicialSeparaFatosEAguardaRespostaReal() throws Exception {
        Projeto projeto = projetoBase();
        projeto.setId(sequencia.incrementAndGet());
        projeto.setCategoria("Web");
        projeto.setNumeroPropostas(4);
        when(avaliadorIaService.gerarDuvidas(any(Projeto.class))).thenReturn(List.of(
            "Tem algum site atual que eu precise considerar?",
            "Tem alguma urgencia ou prazo que eu precise considerar?"
        ));

        autoAtendimentoService.processarProjeto(projeto, ModoSistema.ENSINAMENTO);

        AcaoPendente acao = acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.PENDENTE).get(0);
        var payload = objectMapper.readTree(acao.getPayloadJson());
        String mensagem = payload.path("mensagem").asText();
        String mensagemLower = mensagem.toLowerCase();

        assertEquals(TipoAcaoPendente.ENVIAR_PERGUNTA, acao.getTipo());
        assertEquals("Ja foi atendido?", mensagem);
        assertTrue(payload.path("informacoesConfirmadas").toString().contains("Titulo: Site institucional"));
        assertTrue(payload.path("informacoesConfirmadas").toString().contains("Categoria: Web"));
        assertTrue(payload.path("informacoesPendentes").toString().contains("Aguardar resposta do cliente"));
        assertFalse(payload.path("informacoesConfirmadas").toString().contains("site atual"));
        assertFalse(mensagemLower.contains("teste"));
        assertFalse(mensagemLower.contains("simulad"));
        assertFalse(mensagemLower.contains("automatizado"));
        assertFalse(mensagemLower.contains("exemplo"));
        assertFalse(mensagemLower.contains("curti"));
        assertFalse(mensagemLower.contains("fala!"));
        assertFalse(mensagemLower.contains("e aí"));
    }

    @Test
    @DisplayName("Tom ruim de proposta não entra no primeiro contato")
    void tomRuimDePropostaNaoEntraNoPrimeiroContato() throws Exception {
        Projeto projeto = projetoBase();
        when(avaliadorIaService.gerarDuvidas(any(Projeto.class))).thenReturn(List.of());
        when(avaliadorIaService.gerarTextoProposta(any(Projeto.class), any(Proposta.class)))
            .thenReturn("Teste tecnico automatizado: mensagem de validacao de integracao real.");

        autoAtendimentoService.processarProjeto(projeto, ModoSistema.LIVRE);

        AcaoPendente acao = acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.PENDENTE).get(0);
        var payload = objectMapper.readTree(acao.getPayloadJson());
        assertEquals(TipoAcaoPendente.ENVIAR_PERGUNTA, acao.getTipo());
        assertEquals("Ja foi atendido?", payload.path("mensagem").asText());
        assertEquals(1, mensagemClienteRegistroRepository.count());
        verify(avaliadorIaService, never()).gerarTextoProposta(any(Projeto.class), any(Proposta.class));
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
        verify(telegramService, never()).enviarNotificacao(contains("RASCUNHO BLOQUEADO"));
    }

    @Test
    @DisplayName("Contato fora da plataforma não entra no primeiro contato")
    void contatoForaDaPlataformaNaoEntraNoPrimeiroContato() throws Exception {
        Projeto projeto = projetoBase();
        when(avaliadorIaService.gerarDuvidas(any(Projeto.class))).thenReturn(List.of());
        when(avaliadorIaService.gerarTextoProposta(any(Projeto.class), any(Proposta.class)))
            .thenReturn("Me chama no WhatsApp que te passo os detalhes e fechamos por fora da plataforma. Minha proposta é R$ 1200,00.");

        autoAtendimentoService.processarProjeto(projeto, ModoSistema.LIVRE);

        AcaoPendente acao = acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.PENDENTE).get(0);
        var payload = objectMapper.readTree(acao.getPayloadJson());
        assertEquals(TipoAcaoPendente.ENVIAR_PERGUNTA, acao.getTipo());
        assertEquals("Ja foi atendido?", payload.path("mensagem").asText());
        assertEquals(1, mensagemClienteRegistroRepository.count());
        verify(avaliadorIaService, never()).gerarTextoProposta(any(Projeto.class), any(Proposta.class));
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
        verify(telegramService, never()).enviarNotificacao(contains("RASCUNHO BLOQUEADO"));
    }

    @Test
    @DisplayName("Lista numerada não entra no primeiro contato")
    void listaNumeradaNaoEntraNoPrimeiroContato() throws Exception {
        Projeto projeto = projetoBase();
        when(avaliadorIaService.gerarDuvidas(any(Projeto.class))).thenReturn(List.of());
        when(avaliadorIaService.gerarTextoProposta(any(Projeto.class), any(Proposta.class)))
            .thenReturn("""
                Oi!
                1. Qual prazo?
                2. Ja tem conteudo?
                Minha proposta é R$ 1200,00.
                """);

        autoAtendimentoService.processarProjeto(projeto, ModoSistema.REGULAR);

        AcaoPendente acao = acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.PENDENTE).get(0);
        var payload = objectMapper.readTree(acao.getPayloadJson());
        assertEquals(TipoAcaoPendente.ENVIAR_PERGUNTA, acao.getTipo());
        assertEquals("Ja foi atendido?", payload.path("mensagem").asText());
        assertEquals(1, mensagemClienteRegistroRepository.count());
        verify(avaliadorIaService, never()).gerarTextoProposta(any(Projeto.class), any(Proposta.class));
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
        verify(telegramService, never()).enviarNotificacao(contains("RASCUNHO BLOQUEADO"));
    }

    @Test
    @DisplayName("Telegram de pendência mantém resumo compacto")
    void telegramDePendenciaMantemResumoCompacto() {
        Projeto projeto = projetoBase();

        autoAtendimentoService.processarProjeto(projeto, ModoSistema.REGULAR);

        ArgumentCaptor<String> mensagemCaptor = ArgumentCaptor.forClass(String.class);
        verify(telegramService).enviarAcaoPendenteComBotoes(mensagemCaptor.capture(), anyLong());
        String mensagem = mensagemCaptor.getValue();
        assertFalse(mensagem.contains("Plataforma:"));
        assertFalse(mensagem.contains("Projeto:"));
        assertFalse(mensagem.contains("Cliente:"));
        assertFalse(mensagem.contains("Qualidade:"));
        assertFalse(mensagem.contains("Naturalidade:"));
        assertFalse(mensagem.contains("Risco:"));
        assertTrue(mensagem.contains("Pergunta que sera enviada se aprovar:\n```"));
        assertFalse(mensagem.contains("Nada e enviado sem sua aprovacao."));
        assertTrue(mensagem.contains("Leitura rapida:\n```"));
        assertFalse(mensagem.contains("Contexto lido:"));
    }

}
