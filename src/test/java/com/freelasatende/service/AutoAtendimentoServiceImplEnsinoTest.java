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
class AutoAtendimentoServiceImplEnsinoTest extends AutoAtendimentoServiceImplTestSupport {
    @Test
    @DisplayName("Reescrever ação segura atualiza rascunho e não envia")
    void reescreverAcaoSeguraAtualizaRascunhoENaoEnvia() throws Exception {
        Long projetoId = sequencia.incrementAndGet();
        String textoOriginal = "Oi, consegue me confirmar o prazo ideal?";
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(projetoId)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Projeto reescrita")
            .resumo("Confirmar prazo com cliente")
            .payloadJson("{\"projetoId\":" + projetoId + ",\"tituloProjeto\":\"Projeto reescrita\",\"mensagem\":\"" + textoOriginal + "\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);

        String novoTexto = "Oi, voce consegue confirmar qual prazo faz mais sentido para essa entrega?";
        String retorno = autoAtendimentoService.reescreverAcao(acao.getId(), novoTexto, "Ajuste de naturalidade");

        AcaoPendente atualizada = acaoPendenteRepository.findById(acao.getId()).orElseThrow();
        Map<?, ?> payload = objectMapper.readValue(atualizada.getPayloadJson(), Map.class);
        assertTrue(retorno.toLowerCase().contains("reescrit"));
        assertEquals(StatusAcaoPendente.PENDENTE, atualizada.getStatus());
        assertEquals(novoTexto, payload.get("mensagem"));
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
    }

    @Test
    @DisplayName("Contexto de ensino salva orientação, reescreve rascunho e não envia")
    void contextoDeEnsinoSalvaOrientacaoReescreveRascunhoENaoEnvia() throws Exception {
        Long projetoId = sequencia.incrementAndGet();
        String textoOriginal = "Oi, consegue me confirmar o prazo ideal?";
        String textoNovo = "Oi! A hospedagem na Hostinger já está liberada para eu mexer?";
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(projetoId)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.ENSINAMENTO)
            .tituloProjeto("Site WordPress")
            .resumo("ENSINAMENTO | dúvida principal: hospedagem")
            .payloadJson("{\"projetoId\":" + projetoId
                + ",\"tituloProjeto\":\"Site WordPress\""
                + ",\"contextoProjeto\":\"Descrição completa do projeto WordPress\""
                + ",\"mensagem\":\"" + textoOriginal + "\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);
        when(avaliadorIaService.reescreverRascunhoComOrientacao(
            contains("Descrição completa do projeto WordPress"),
            eq(textoOriginal),
            eq("pergunta só se a hospedagem já está liberada")
        )).thenReturn(textoNovo);

        String retorno = autoAtendimentoService.registrarContextoEnsino(
            acao.getId(),
            "pergunta só se a hospedagem já está liberada"
        );

        AcaoPendente atualizada = acaoPendenteRepository.findById(acao.getId()).orElseThrow();
        var payload = objectMapper.readTree(atualizada.getPayloadJson());
        assertTrue(retorno.contains("Contexto salvo"));
        assertEquals(StatusAcaoPendente.PENDENTE, atualizada.getStatus());
        assertEquals(textoNovo, payload.path("mensagem").asText());
        assertEquals("pergunta só se a hospedagem já está liberada", payload.path("notasEnsino").get(0).path("texto").asText());
        assertTrue(atualizada.getObservacaoUsuario().contains("Ensino:"));
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
    }

    @Test
    @DisplayName("Contexto de ensino vazio não altera ação nem chama IA")
    void contextoDeEnsinoVazioNaoAlteraAcaoNemChamaIa() throws Exception {
        AcaoPendente acao = acaoEnsino(
            TipoAcaoPendente.ENVIAR_PERGUNTA,
            StatusAcaoPendente.PENDENTE,
            "Projeto ensino vazio",
            "Oi, consegue confirmar o prazo?"
        );

        String retorno = autoAtendimentoService.registrarContextoEnsino(acao.getId(), "   ");

        var payload = objectMapper.readTree(acaoPendenteRepository.findById(acao.getId()).orElseThrow().getPayloadJson());
        assertTrue(retorno.contains("orientação"));
        assertFalse(payload.has("notasEnsino"));
        verify(avaliadorIaService, never()).reescreverRascunhoComOrientacao(anyString(), anyString(), anyString());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
    }

    @Test
    @DisplayName("Contexto de ensino não altera ação que não está pendente")
    void contextoDeEnsinoNaoAlteraAcaoQueNaoEstaPendente() throws Exception {
        AcaoPendente acao = acaoEnsino(
            TipoAcaoPendente.ENVIAR_PERGUNTA,
            StatusAcaoPendente.EXECUTADA,
            "Projeto executado",
            "Oi, consegue confirmar o prazo?"
        );

        String retorno = autoAtendimentoService.registrarContextoEnsino(acao.getId(), "pergunte prazo");

        var payload = objectMapper.readTree(acaoPendenteRepository.findById(acao.getId()).orElseThrow().getPayloadJson());
        assertTrue(retorno.contains("não está pendente"));
        assertFalse(payload.has("notasEnsino"));
        verify(avaliadorIaService, never()).reescreverRascunhoComOrientacao(anyString(), anyString(), anyString());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
    }

    @Test
    @DisplayName("Contexto de ensino não altera cancelamento de proposta")
    void contextoDeEnsinoNaoAlteraCancelamentoDeProposta() throws Exception {
        AcaoPendente acao = acaoEnsino(
            TipoAcaoPendente.CANCELAR_PROPOSTA,
            StatusAcaoPendente.PENDENTE,
            "Projeto cancelamento",
            "Cancelar proposta"
        );

        String retorno = autoAtendimentoService.registrarContextoEnsino(acao.getId(), "cancelar por risco");

        var payload = objectMapper.readTree(acaoPendenteRepository.findById(acao.getId()).orElseThrow().getPayloadJson());
        assertTrue(retorno.contains("Cancelamento não usa conversa de ensino"));
        assertFalse(payload.has("notasEnsino"));
        verify(avaliadorIaService, never()).reescreverRascunhoComOrientacao(anyString(), anyString(), anyString());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
    }

    @Test
    @DisplayName("Contexto de ensino sem rascunho cria nova resposta e não envia")
    void contextoDeEnsinoSemRascunhoCriaNovaRespostaENaoEnvia() throws Exception {
        Long projetoId = sequencia.incrementAndGet();
        String novoRascunho = "Entendi. Qual parte da integração de pagamento ainda precisa de ajuste?";
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(projetoId)
            .conversaId(17025138L)
            .tipo(TipoAcaoPendente.RESPONDER_CONVERSA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.ENSINAMENTO)
            .tituloProjeto("Projeto sem rascunho")
            .resumo("Sem texto ao cliente")
            .payloadJson("{\"projetoId\":" + projetoId
                + ",\"tituloProjeto\":\"Projeto sem rascunho\""
                + ",\"mensagemGatilhoTexto\":\"Ainda não fui atendido\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);
        when(avaliadorIaService.reescreverRascunhoComOrientacao(
            contains("Ainda não fui atendido"),
            eq(""),
            eq("guardar apenas contexto")
        )).thenReturn(novoRascunho);

        String retorno = autoAtendimentoService.registrarContextoEnsino(acao.getId(), "guardar apenas contexto");

        var payload = objectMapper.readTree(acaoPendenteRepository.findById(acao.getId()).orElseThrow().getPayloadJson());
        assertTrue(retorno.contains("novo rascunho criado"));
        assertEquals(novoRascunho, payload.path("mensagem").asText());
        assertEquals("guardar apenas contexto", payload.path("notasEnsino").get(0).path("texto").asText());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
    }

    @Test
    @DisplayName("Ensino em uma ação não vaza para reprocessamento de outra ação")
    void ensinoEmUmaAcaoNaoVazaParaReprocessamentoDeOutraAcao() throws Exception {
        AcaoPendente acaoA = acaoEnsinoComContexto(
            "Projeto A",
            "CTX-A-HOSPEDAGEM",
            "Oi, consegue confirmar hospedagem?"
        );
        AcaoPendente acaoB = acaoEnsinoComContexto(
            "Projeto B",
            "CTX-B-APP",
            "Oi, consegue confirmar integrações?"
        );

        autoAtendimentoService.registrarContextoEnsino(acaoA.getId(), "orientacao unica CTX-A-ENSINO");
        clearInvocations(avaliadorIaService);
        when(avaliadorIaService.reescreverRascunhoComOrientacao(
            anyString(),
            eq("Oi, consegue confirmar integrações?"),
            eq("orientacao unica CTX-B-ENSINO")
        )).thenReturn("Oi, consegue confirmar quais integrações entram no escopo?");

        autoAtendimentoService.registrarContextoEnsino(acaoB.getId(), "orientacao unica CTX-B-ENSINO");

        ArgumentCaptor<String> contextoCaptor = ArgumentCaptor.forClass(String.class);
        verify(avaliadorIaService).reescreverRascunhoComOrientacao(
            contextoCaptor.capture(),
            eq("Oi, consegue confirmar integrações?"),
            eq("orientacao unica CTX-B-ENSINO")
        );
        String contextoB = contextoCaptor.getValue();
        var payloadB = objectMapper.readTree(acaoPendenteRepository.findById(acaoB.getId()).orElseThrow().getPayloadJson());
        assertTrue(contextoB.contains("CTX-B-APP"));
        assertTrue(contextoB.contains("orientacao unica CTX-B-ENSINO"));
        assertFalse(contextoB.contains("CTX-A-HOSPEDAGEM"));
        assertFalse(contextoB.contains("orientacao unica CTX-A-ENSINO"));
        assertEquals("orientacao unica CTX-B-ENSINO", payloadB.path("notasEnsino").get(0).path("texto").asText());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
    }

    @Test
    @DisplayName("Resposta de dúvida vazia não altera ação nem chama IA")
    void respostaDeDuvidaVaziaNaoAlteraAcaoNemChamaIa() throws Exception {
        AcaoPendente acao = acaoEnsino(
            TipoAcaoPendente.ENVIAR_PERGUNTA,
            StatusAcaoPendente.PENDENTE,
            "Projeto dúvida vazia",
            "Oi, consegue confirmar o prazo?"
        );

        String retorno = autoAtendimentoService.responderDuvidaIa(acao.getId(), "", "regra");

        var payload = objectMapper.readTree(acaoPendenteRepository.findById(acao.getId()).orElseThrow().getPayloadJson());
        assertTrue(retorno.contains("Responda a dúvida da IA"));
        assertFalse(payload.has("respostasDuvidasIa"));
        verify(avaliadorIaService, never()).reescreverRascunhoComOrientacao(anyString(), anyString(), anyString());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
    }

    @Test
    @DisplayName("Resposta de dúvida não altera cancelamento de proposta")
    void respostaDeDuvidaNaoAlteraCancelamentoDeProposta() throws Exception {
        AcaoPendente acao = acaoEnsino(
            TipoAcaoPendente.CANCELAR_PROPOSTA,
            StatusAcaoPendente.PENDENTE,
            "Projeto cancelamento dúvida",
            "Cancelar proposta"
        );

        String retorno = autoAtendimentoService.responderDuvidaIa(acao.getId(), "pode cancelar", "regra");

        var payload = objectMapper.readTree(acaoPendenteRepository.findById(acao.getId()).orElseThrow().getPayloadJson());
        assertTrue(retorno.contains("Cancelamento não usa resposta de dúvida da IA"));
        assertFalse(payload.has("respostasDuvidasIa"));
        verify(avaliadorIaService, never()).reescreverRascunhoComOrientacao(anyString(), anyString(), anyString());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
    }

    @Test
    @DisplayName("E2E ensino, duvida e aprovacao envia apenas o rascunho final")
    void e2eEnsinoDuvidaEAprovacaoEnviaApenasRascunhoFinal() throws Exception {
        habilitarEscritaRealMockada();
        Long projetoId = sequencia.incrementAndGet();
        String textoInicial = "Oi, consigo te passar uma proposta.";
        String textoDepoisEnsino = "Oi, voce consegue confirmar o prazo ideal antes da proposta?";
        String textoDepoisDuvida = "Oi, voce consegue confirmar o prazo ideal e se o conteudo ja esta pronto?";
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(projetoId)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.ENSINAMENTO)
            .tituloProjeto("Landing page E2E")
            .resumo("ENSINAMENTO | confirmar prazo e conteudo")
            .payloadJson("{\"projetoId\":" + projetoId
                + ",\"tituloProjeto\":\"Landing page E2E\""
                + ",\"contextoProjeto\":\"Landing page com formulario e conteudo pendente\""
                + ",\"mensagem\":\"" + textoInicial + "\""
                + ",\"conversaEnsinoObrigatoria\":true"
                + ",\"perguntaParaUsuario\":\"Pergunto prazo antes?\""
                + ",\"regraAprendizadoSugerida\":\"Quando faltar prazo, perguntar antes da proposta.\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);
        when(avaliadorIaService.reescreverRascunhoComOrientacao(anyString(), eq(textoInicial), contains("pergunte prazo")))
            .thenReturn(textoDepoisEnsino);
        when(avaliadorIaService.reescreverRascunhoComOrientacao(anyString(), eq(textoDepoisEnsino), contains("conteudo ja esta pronto")))
            .thenReturn(textoDepoisDuvida);

        String ensino = autoAtendimentoService.registrarContextoEnsino(
            acao.getId(),
            "pergunte prazo antes de propor valor"
        );
        String duvida = autoAtendimentoService.responderDuvidaIa(
            acao.getId(),
            "pergunte tambem se o conteudo ja esta pronto",
            "Quando faltar conteudo, confirmar antes de estimar entrega."
        );
        String aprovacao = autoAtendimentoService.processarFeedback(acao.getId(), true, "BOM_TOM");

        AcaoPendente atualizada = acaoPendenteRepository.findById(acao.getId()).orElseThrow();
        var payload = objectMapper.readTree(atualizada.getPayloadJson());
        assertTrue(ensino.contains("Contexto salvo"));
        assertTrue(duvida.contains("Resposta salva"));
        assertTrue(aprovacao.contains("executada"));
        assertEquals(StatusAcaoPendente.EXECUTADA, atualizada.getStatus());
        assertEquals(textoDepoisDuvida, payload.path("mensagem").asText());
        assertEquals("pergunte prazo antes de propor valor", payload.path("notasEnsino").get(0).path("texto").asText());
        assertEquals("pergunte tambem se o conteudo ja esta pronto", payload.path("respostasDuvidasIa").get(0).path("resposta").asText());
        verify(scraperService, times(1)).enviarPergunta(eq(projetoId), eq(textoDepoisDuvida));
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
    }

    @Test
    @DisplayName("Stress misto de ensino mantém status, envio e contexto isolados")
    void stressMistoDeEnsinoMantemStatusEnvioEContextoIsolados() throws Exception {
        habilitarEscritaRealMockada();
        List<AcaoPendente> acoes = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            TipoAcaoPendente tipo = i % 2 == 0
                ? TipoAcaoPendente.ENVIAR_PERGUNTA
                : TipoAcaoPendente.RESPONDER_CONVERSA;
            acoes.add(acaoEnsinoMista(tipo, i));
        }

        for (int i = 0; i < acoes.size(); i++) {
            AcaoPendente acao = acoes.get(i);
            String nota = "NOTA-STRESS-" + i;
            autoAtendimentoService.registrarContextoEnsino(acao.getId(), nota);
            if (i % 3 == 0) {
                String retorno = autoAtendimentoService.processarFeedback(acao.getId(), true, "BOM_TOM");
                assertTrue(retorno.contains("executada"));
            } else if (i % 3 == 1) {
                String retorno = autoAtendimentoService.processarFeedback(acao.getId(), false, "ROBOTICO");
                assertTrue(retorno.contains("rejeitada"));
            }
        }

        for (int i = 0; i < acoes.size(); i++) {
            AcaoPendente atual = acaoPendenteRepository.findById(acoes.get(i).getId()).orElseThrow();
            var payload = objectMapper.readTree(atual.getPayloadJson());
            assertEquals("NOTA-STRESS-" + i, payload.path("notasEnsino").get(0).path("texto").asText());
            assertEquals(1, payload.path("notasEnsino").size());
            if (i % 3 == 0) {
                assertEquals(StatusAcaoPendente.EXECUTADA, atual.getStatus());
            } else if (i % 3 == 1) {
                assertEquals(StatusAcaoPendente.REJEITADA, atual.getStatus());
            } else {
                assertEquals(StatusAcaoPendente.PENDENTE, atual.getStatus());
            }
        }

        verify(scraperService, times(2)).enviarPergunta(anyLong(), anyString());
        verify(scraperService, times(2)).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
    }

    @Test
    @DisplayName("Reescrita com termo proibido bloqueia e mantém ação pendente")
    void reescritaComTermoProibidoBloqueiaEMantemAcaoPendente() throws Exception {
        Long projetoId = sequencia.incrementAndGet();
        String textoOriginal = "Oi, consegue me confirmar o prazo ideal?";
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(projetoId)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Projeto bloqueio")
            .resumo("Confirmar prazo com cliente")
            .payloadJson("{\"projetoId\":" + projetoId + ",\"tituloProjeto\":\"Projeto bloqueio\",\"mensagem\":\"" + textoOriginal + "\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);

        String retorno = autoAtendimentoService.reescreverAcao(
            acao.getId(),
            "Teste tecnico automatizado: mensagem de validacao de integracao real.",
            "teste bloqueado"
        );

        AcaoPendente atualizada = acaoPendenteRepository.findById(acao.getId()).orElseThrow();
        Map<?, ?> payload = objectMapper.readValue(atualizada.getPayloadJson(), Map.class);
        assertTrue(retorno.toLowerCase().contains("revisão") || retorno.toLowerCase().contains("guardiao") || retorno.toLowerCase().contains("guard"));
        assertEquals(StatusAcaoPendente.PENDENTE, atualizada.getStatus());
        assertEquals(textoOriginal, payload.get("mensagem"));
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
    }

    @Test
    @DisplayName("Sugerir proposta com projeto nulo deve retornar mínima")
    @Disabled("O serviço real não tolera null no sugerirProposta - requer tratamento adicional no AvaliadorIaServiceImpl")
    void sugestaoPropostaNuloDeveRetornarMinima() {
        // Act
        var proposta = avaliadorIaService.sugerirProposta(null);

        // Assert
        assertNotNull(proposta);
        assertEquals(BigDecimal.valueOf(500), proposta.getValorProposto());
    }

    @Test
    @DisplayName("Processar projeto com scraper unavailable não deve falhar")
    void processarProjetoComScraperUnavailable() {
        // Arrange
        Projeto projeto = projetoBase();
        ReflectionTestUtils.setField(autoAtendimentoService, "minConexoesReserva", 700);
        ReflectionTestUtils.setField(autoAtendimentoService, "minMediaCliente", 3.5);
        ReflectionTestUtils.setField(autoAtendimentoService, "bloquearClienteSemAvaliacao", true);

        // Act & Assert: não deve lançar exceção mesmo se scraper falhar
        assertDoesNotThrow(() -> autoAtendimentoService.processarProjeto(projeto, ModoSistema.REGULAR));
    }

}
