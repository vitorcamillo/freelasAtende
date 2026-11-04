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
class AutoAtendimentoServiceImplInboxIsolationTest extends AutoAtendimentoServiceImplTestSupport {
    @Test
    @DisplayName("Inbox nunca recusa conversa por escopo: marketing tambem gera rascunho e pendencia")
    void inboxNuncaRecusaConversaPorEscopoEGeraRascunho() {
        // Conversa de cliente nunca e recusada por escopo (so projetos novos sao).
        // O cliente respondeu: a IA monta rascunho e o operador decide.
        ConversaResumo conversa = ConversaResumo.builder()
            .idConversa(8801L)
            .idProjeto(9801L)
            .nomeProjeto("Procuro profissional de marketing para lançamento e venda de livros online")
            .nomeCliente("Cliente Marketing")
            .idCliente(701L)
            .idFreelancer(269409L)
            .build();
        MensagemConversa mensagem = MensagemConversa.builder()
            .id(9901L)
            .idConversa(8801L)
            .idPessoa(701L)
            .nomePessoa("Cliente Marketing")
            .texto("Preciso de estratégia de marketing e lançamento para vender meus livros online.")
            .dataCriacaoMillis(2000L)
            .enviadaPorMim(false)
            .build();
        when(scraperService.listarConversasInbox()).thenReturn(List.of(conversa));
        when(scraperService.listarMensagensConversa(8801L)).thenReturn(List.of(mensagem));

        autoAtendimentoService.sincronizarInbox(ModoSistema.ENSINAMENTO);

        verify(avaliadorIaService).sugerirRespostaConversa(any(Projeto.class), anyString(), anyString());
        assertEquals(1, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        List<com.freelasatende.model.entity.Interacao> interacoes =
            interacaoRepository.findByProjetoIdOrderByDataDesc(9801L);
        assertTrue(interacoes.stream().noneMatch(i -> i.getConteudo().contains("Ignorado por escopo")));
    }

    @Test
    @DisplayName("Inbox continua avaliando proximas conversas quando uma conversa falha")
    void inboxContinuaAvaliandoProximasConversasQuandoUmaConversaFalha() {
        ConversaResumo conversaComErro = ConversaResumo.builder()
            .idConversa(8901L)
            .idProjeto(9901L)
            .nomeProjeto("Site com conversa indisponivel")
            .nomeCliente("Cliente A")
            .idCliente(801L)
            .idFreelancer(269409L)
            .build();
        ConversaResumo conversaValida = ConversaResumo.builder()
            .idConversa(8902L)
            .idProjeto(9902L)
            .nomeProjeto("Landing page para servico local")
            .nomeCliente("Cliente B")
            .idCliente(802L)
            .idFreelancer(269409L)
            .build();
        MensagemConversa mensagemValida = MensagemConversa.builder()
            .id(9902L)
            .idConversa(8902L)
            .idPessoa(802L)
            .nomePessoa("Cliente B")
            .texto("Pode me explicar prazo e valor para a landing page?")
            .dataCriacaoMillis(3000L)
            .enviadaPorMim(false)
            .build();
        when(scraperService.listarConversasInbox()).thenReturn(List.of(conversaComErro, conversaValida));
        when(scraperService.listarMensagensConversa(8901L))
            .thenThrow(new IllegalStateException("mensagens indisponiveis"));
        when(scraperService.listarMensagensConversa(8902L)).thenReturn(List.of(mensagemValida));

        autoAtendimentoService.sincronizarInbox(ModoSistema.ENSINAMENTO);

        verify(avaliadorIaService).sugerirRespostaConversa(any(Projeto.class), contains("landing page"), anyString());
        assertEquals(1, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        assertTrue(acaoPendenteRepository.findAll().stream()
            .anyMatch(acao -> Long.valueOf(8902L).equals(acao.getConversaId())));
    }

    @Test
    @DisplayName("Conversas paralelas não misturam histórico, gatilho nem payload")
    void conversasParalelasNaoMisturamHistoricoGatilhoNemPayload() throws Exception {
        ConversaResumo conversaWordpress = ConversaResumo.builder()
            .idConversa(901L)
            .idProjeto(1901L)
            .nomeProjeto("Site WordPress para clínica")
            .nomeCliente("Ana")
            .idCliente(501L)
            .idFreelancer(269409L)
            .nomeFreelancer("Vitor Camillo")
            .build();
        ConversaResumo conversaShopify = ConversaResumo.builder()
            .idConversa(902L)
            .idProjeto(1902L)
            .nomeProjeto("Loja Shopify para moda")
            .nomeCliente("Bruno")
            .idCliente(502L)
            .idFreelancer(269409L)
            .nomeFreelancer("Vitor Camillo")
            .build();
        MensagemConversa msgWordpressMinha = MensagemConversa.builder()
            .id(11L)
            .idConversa(901L)
            .idPessoa(269409L)
            .nomePessoa("Vitor Camillo")
            .texto("Oi Ana, estou vendo o escopo do WordPress.")
            .dataCriacaoMillis(1000L)
            .enviadaPorMim(true)
            .build();
        MensagemConversa msgWordpressCliente = MensagemConversa.builder()
            .id(12L)
            .idConversa(901L)
            .idPessoa(501L)
            .nomePessoa("Ana")
            .texto("Meu projeto usa WordPress. Código interno ALFA-771. Prazo ideal: sexta.")
            .dataCriacaoMillis(2000L)
            .enviadaPorMim(false)
            .build();
        MensagemConversa msgShopifyMinha = MensagemConversa.builder()
            .id(21L)
            .idConversa(902L)
            .idPessoa(269409L)
            .nomePessoa("Vitor Camillo")
            .texto("Oi Bruno, estou vendo a loja.")
            .dataCriacaoMillis(1000L)
            .enviadaPorMim(true)
            .build();
        MensagemConversa msgShopifyCliente = MensagemConversa.builder()
            .id(22L)
            .idConversa(902L)
            .idPessoa(502L)
            .nomePessoa("Bruno")
            .texto("Minha loja é Shopify. Código interno BETA-982. Prazo ideal: mês que vem.")
            .dataCriacaoMillis(2000L)
            .enviadaPorMim(false)
            .build();
        when(scraperService.listarConversasInbox()).thenReturn(List.of(conversaWordpress, conversaShopify));
        when(scraperService.listarMensagensConversa(901L)).thenReturn(List.of(msgWordpressMinha, msgWordpressCliente));
        when(scraperService.listarMensagensConversa(902L)).thenReturn(List.of(msgShopifyMinha, msgShopifyCliente));
        when(avaliadorIaService.sugerirRespostaConversa(any(Projeto.class), anyString(), anyString()))
            .thenAnswer(invocation -> {
                Projeto projeto = invocation.getArgument(0);
                return "Oi, obrigado pelo retorno. Vou considerar o contexto do projeto "
                    + projeto.getId()
                    + " e seguir por aqui.";
            });

        autoAtendimentoService.sincronizarInbox(ModoSistema.ENSINAMENTO);

        ArgumentCaptor<Projeto> projetoCaptor = ArgumentCaptor.forClass(Projeto.class);
        ArgumentCaptor<String> historicoCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> ultimaMensagemCaptor = ArgumentCaptor.forClass(String.class);
        verify(avaliadorIaService, times(2)).sugerirRespostaConversa(
            projetoCaptor.capture(),
            historicoCaptor.capture(),
            ultimaMensagemCaptor.capture()
        );

        Map<Long, String> historicosPorProjeto = new java.util.HashMap<>();
        Map<Long, String> ultimasPorProjeto = new java.util.HashMap<>();
        for (int i = 0; i < projetoCaptor.getAllValues().size(); i++) {
            Long projetoId = projetoCaptor.getAllValues().get(i).getId();
            historicosPorProjeto.put(projetoId, historicoCaptor.getAllValues().get(i));
            ultimasPorProjeto.put(projetoId, ultimaMensagemCaptor.getAllValues().get(i));
        }

        assertTrue(historicosPorProjeto.get(1901L).contains("ALFA-771"));
        assertFalse(historicosPorProjeto.get(1901L).contains("BETA-982"));
        assertTrue(historicosPorProjeto.get(1902L).contains("BETA-982"));
        assertFalse(historicosPorProjeto.get(1902L).contains("ALFA-771"));
        assertTrue(ultimasPorProjeto.get(1901L).contains("ALFA-771"));
        assertTrue(ultimasPorProjeto.get(1902L).contains("BETA-982"));

        List<AcaoPendente> pendencias = acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.PENDENTE);
        assertEquals(2, pendencias.size());
        Map<Long, com.fasterxml.jackson.databind.JsonNode> payloadsPorConversa = new java.util.HashMap<>();
        for (AcaoPendente acao : pendencias) {
            payloadsPorConversa.put(acao.getConversaId(), objectMapper.readTree(acao.getPayloadJson()));
        }
        var payloadWordpress = payloadsPorConversa.get(901L);
        var payloadShopify = payloadsPorConversa.get(902L);
        assertNotNull(payloadWordpress);
        assertNotNull(payloadShopify);
        assertEquals(12L, payloadWordpress.path("mensagemGatilhoId").asLong());
        assertEquals(22L, payloadShopify.path("mensagemGatilhoId").asLong());
        assertTrue(payloadWordpress.path("historicoConversa").asText().contains("ALFA-771"));
        assertFalse(payloadWordpress.path("historicoConversa").asText().contains("BETA-982"));
        assertTrue(payloadShopify.path("historicoConversa").asText().contains("BETA-982"));
        assertFalse(payloadShopify.path("historicoConversa").asText().contains("ALFA-771"));
        assertEquals("Ana", payloadWordpress.path("nomeCliente").asText());
        assertEquals("Bruno", payloadShopify.path("nomeCliente").asText());
        assertTrue(payloadWordpress.path("ultimasMensagensCliente").get(0).path("texto").asText().contains("ALFA-771"));
        assertTrue(payloadShopify.path("ultimasMensagensCliente").get(0).path("texto").asText().contains("BETA-982"));
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
    }

    @Test
    @DisplayName("Mesmo id de mensagem em conversas diferentes não deduplica errado")
    void mesmoIdDeMensagemEmConversasDiferentesNaoDeduplicaErrado() throws Exception {
        ConversaResumo conversaA = ConversaResumo.builder()
            .idConversa(9101L)
            .idProjeto(9201L)
            .nomeProjeto("Sistema interno A")
            .nomeCliente("Cliente A")
            .idCliente(601L)
            .idFreelancer(269409L)
            .build();
        ConversaResumo conversaB = ConversaResumo.builder()
            .idConversa(9102L)
            .idProjeto(9202L)
            .nomeProjeto("Sistema interno B")
            .nomeCliente("Cliente B")
            .idCliente(602L)
            .idFreelancer(269409L)
            .build();
        MensagemConversa mensagemA = MensagemConversa.builder()
            .id(7777L)
            .idConversa(9101L)
            .idPessoa(601L)
            .nomePessoa("Cliente A")
            .texto("Preciso revisar o fluxo A. Código A-CTX-100.")
            .dataCriacaoMillis(2000L)
            .enviadaPorMim(false)
            .build();
        MensagemConversa mensagemB = MensagemConversa.builder()
            .id(7777L)
            .idConversa(9102L)
            .idPessoa(602L)
            .nomePessoa("Cliente B")
            .texto("Preciso revisar o fluxo B. Código B-CTX-200.")
            .dataCriacaoMillis(2000L)
            .enviadaPorMim(false)
            .build();
        when(scraperService.listarConversasInbox()).thenReturn(List.of(conversaA, conversaB));
        when(scraperService.listarMensagensConversa(9101L)).thenReturn(List.of(mensagemA));
        when(scraperService.listarMensagensConversa(9102L)).thenReturn(List.of(mensagemB));

        autoAtendimentoService.sincronizarInbox(ModoSistema.ENSINAMENTO);

        List<AcaoPendente> pendencias = acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.PENDENTE);
        assertEquals(2, pendencias.size());
        assertEquals(2, pendencias.stream().map(AcaoPendente::getConversaId).distinct().count());
        for (AcaoPendente acao : pendencias) {
            var payload = objectMapper.readTree(acao.getPayloadJson());
            assertEquals(7777L, payload.path("mensagemGatilhoId").asLong());
            if (acao.getConversaId().equals(9101L)) {
                assertTrue(payload.path("historicoConversa").asText().contains("A-CTX-100"));
                assertFalse(payload.path("historicoConversa").asText().contains("B-CTX-200"));
            } else if (acao.getConversaId().equals(9102L)) {
                assertTrue(payload.path("historicoConversa").asText().contains("B-CTX-200"));
                assertFalse(payload.path("historicoConversa").asText().contains("A-CTX-100"));
            } else {
                fail("Conversa inesperada: " + acao.getConversaId());
            }
        }
        verify(avaliadorIaService, times(2)).sugerirRespostaConversa(any(Projeto.class), anyString(), anyString());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
    }

    @Test
    @DisplayName("Nova mensagem em uma conversa com pendência não contamina outra conversa")
    void novaMensagemEmUmaConversaComPendenciaNaoContaminaOutraConversa() throws Exception {
        ConversaResumo conversaA = ConversaResumo.builder()
            .idConversa(9301L)
            .idProjeto(9401L)
            .nomeProjeto("Portal A")
            .nomeCliente("Cliente A")
            .idCliente(701L)
            .idFreelancer(269409L)
            .build();
        ConversaResumo conversaB = ConversaResumo.builder()
            .idConversa(9302L)
            .idProjeto(9402L)
            .nomeProjeto("Portal B")
            .nomeCliente("Cliente B")
            .idCliente(702L)
            .idFreelancer(269409L)
            .build();
        MensagemConversa primeiraA = MensagemConversa.builder()
            .id(101L)
            .idConversa(9301L)
            .idPessoa(701L)
            .nomePessoa("Cliente A")
            .texto("Primeira mensagem A. Código A-FIRST-1.")
            .dataCriacaoMillis(1000L)
            .enviadaPorMim(false)
            .build();
        MensagemConversa segundaA = MensagemConversa.builder()
            .id(102L)
            .idConversa(9301L)
            .idPessoa(701L)
            .nomePessoa("Cliente A")
            .texto("Nova mensagem A enquanto já existe rascunho. Código A-NEW-2.")
            .dataCriacaoMillis(3000L)
            .enviadaPorMim(false)
            .build();
        MensagemConversa primeiraB = MensagemConversa.builder()
            .id(201L)
            .idConversa(9302L)
            .idPessoa(702L)
            .nomePessoa("Cliente B")
            .texto("Primeira mensagem B. Código B-FIRST-1.")
            .dataCriacaoMillis(2000L)
            .enviadaPorMim(false)
            .build();
        when(scraperService.listarConversasInbox())
            .thenReturn(List.of(conversaA))
            .thenReturn(List.of(conversaA, conversaB));
        when(scraperService.listarMensagensConversa(9301L))
            .thenReturn(List.of(primeiraA))
            .thenReturn(List.of(primeiraA, segundaA));
        when(scraperService.listarMensagensConversa(9302L)).thenReturn(List.of(primeiraB));

        autoAtendimentoService.sincronizarInbox(ModoSistema.ENSINAMENTO);
        autoAtendimentoService.sincronizarInbox(ModoSistema.ENSINAMENTO);

        List<AcaoPendente> pendencias = acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.PENDENTE);
        assertEquals(2, pendencias.size());
        assertEquals(1, pendencias.stream().filter(acao -> acao.getConversaId().equals(9301L)).count());
        assertEquals(1, pendencias.stream().filter(acao -> acao.getConversaId().equals(9302L)).count());

        AcaoPendente pendenciaA = pendencias.stream()
            .filter(acao -> acao.getConversaId().equals(9301L))
            .findFirst()
            .orElseThrow();
        AcaoPendente pendenciaB = pendencias.stream()
            .filter(acao -> acao.getConversaId().equals(9302L))
            .findFirst()
            .orElseThrow();
        var payloadA = objectMapper.readTree(pendenciaA.getPayloadJson());
        var payloadB = objectMapper.readTree(pendenciaB.getPayloadJson());
        assertEquals(102L, payloadA.path("mensagemGatilhoId").asLong());
        assertTrue(payloadA.path("historicoConversa").asText().contains("A-FIRST-1"));
        assertTrue(payloadA.path("historicoConversa").asText().contains("A-NEW-2"));
        assertEquals(201L, payloadB.path("mensagemGatilhoId").asLong());
        assertTrue(payloadB.path("historicoConversa").asText().contains("B-FIRST-1"));
        assertFalse(payloadB.path("historicoConversa").asText().contains("A-NEW-2"));

        assertTrue(interacaoRepository.findAll().stream()
            .anyMatch(interacao -> interacao.getConteudo().contains("substituiu")));
        verify(avaliadorIaService, times(3)).sugerirRespostaConversa(any(Projeto.class), anyString(), anyString());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
    }

    @Test
    @DisplayName("Inbox ignora mensagem apagada ou sem texto")
    void inboxIgnoraMensagemApagadaOuSemTexto() {
        ConversaResumo conversa = ConversaResumo.builder()
            .idConversa(77L)
            .idProjeto(88L)
            .nomeProjeto("Rastreador de odds")
            .nomeCliente("Cliente")
            .build();
        MensagemConversa apagada = MensagemConversa.builder()
            .id(37261159L)
            .idConversa(77L)
            .texto(null)
            .dataCriacaoMillis(2000L)
            .enviadaPorMim(false)
            .excluida(true)
            .build();
        when(scraperService.listarConversasInbox()).thenReturn(List.of(conversa));
        when(scraperService.listarMensagensConversa(77L)).thenReturn(List.of(apagada));

        autoAtendimentoService.sincronizarInbox(ModoSistema.ENSINAMENTO);

        assertEquals(0, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        verify(avaliadorIaService, never()).sugerirRespostaConversa(any(Projeto.class), anyString(), anyString());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
    }

}
