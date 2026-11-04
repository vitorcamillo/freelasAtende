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
import com.freelasatende.service.impl.MarketplaceAdapterErrorType;
import com.freelasatende.service.impl.MarketplaceAdapterException;
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
class AutoAtendimentoServiceImplInboxBasicTest extends AutoAtendimentoServiceImplTestSupport {
    @Test
    @DisplayName("Inbox preserva cooldown de conversa inacessivel entre rodadas do servico")
    void inboxPreservaCooldownDeConversaInacessivelEntreRodadasDoServico() {
        ConversaResumo conversa = ConversaResumo.builder()
            .idConversa(40222544L)
            .idProjeto(200L)
            .nomeProjeto("Projeto removido")
            .nomeCliente("Cliente")
            .build();
        when(scraperService.listarConversasInbox()).thenReturn(List.of(conversa));
        when(scraperService.listarMensagensConversa(40222544L)).thenThrow(new MarketplaceAdapterException(
            com.freelasatende.model.enums.PlataformaFreela.FREELAS_99,
            MarketplaceAdapterErrorType.CONVERSA_INACESSIVEL,
            "99Freelas conversa 40222544 inacessivel ou removida: HTTP 404"
        ));

        autoAtendimentoService.sincronizarInbox(ModoSistema.ENSINAMENTO);
        autoAtendimentoService.sincronizarInbox(ModoSistema.ENSINAMENTO);

        verify(scraperService, times(1)).listarMensagensConversa(40222544L);
        verify(avaliadorIaService, never()).sugerirRespostaConversa(any(Projeto.class), anyString(), anyString());
    }

    @Test
    @DisplayName("Inbox sem nova mensagem do cliente nao gera nova resposta")
    void inboxSemNovaMensagemDoClienteNaoGeraNovaResposta() {
        ConversaResumo conversa = ConversaResumo.builder()
            .idConversa(77L)
            .idProjeto(88L)
            .nomeProjeto("Landing page")
            .nomeCliente("Cliente")
            .build();
        MensagemConversa mensagemCliente = MensagemConversa.builder()
            .id(999L)
            .idConversa(77L)
            .texto("Gostei, pode seguir?")
            .dataCriacaoMillis(1000L)
            .enviadaPorMim(false)
            .build();
        when(scraperService.listarConversasInbox()).thenReturn(List.of(conversa));
        when(scraperService.listarMensagensConversa(77L)).thenReturn(List.of(mensagemCliente));

        autoAtendimentoService.sincronizarInbox(ModoSistema.LIVRE);
        autoAtendimentoService.sincronizarInbox(ModoSistema.LIVRE);

        assertEquals(1, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
    }

    @Test
    @DisplayName("Inbox substitui rascunho pendente quando cliente envia nova mensagem")
    void inboxSubstituiRascunhoPendenteQuandoChegaNovaMensagem() {
        ConversaResumo conversa = ConversaResumo.builder()
            .idConversa(77L)
            .idProjeto(88L)
            .nomeProjeto("Landing page")
            .nomeCliente("Cliente")
            .build();
        MensagemConversa primeira = MensagemConversa.builder()
            .id(1001L)
            .idConversa(77L)
            .texto("Gostei, pode seguir?")
            .dataCriacaoMillis(1000L)
            .enviadaPorMim(false)
            .build();
        MensagemConversa segunda = MensagemConversa.builder()
            .id(1002L)
            .idConversa(77L)
            .texto("Só reforçando: preciso disso para esta semana.")
            .dataCriacaoMillis(2000L)
            .enviadaPorMim(false)
            .build();
        when(scraperService.listarConversasInbox()).thenReturn(List.of(conversa));
        when(scraperService.listarMensagensConversa(77L))
            .thenReturn(List.of(primeira))
            .thenReturn(List.of(primeira, segunda));

        autoAtendimentoService.sincronizarInbox(ModoSistema.LIVRE);
        autoAtendimentoService.sincronizarInbox(ModoSistema.LIVRE);

        assertEquals(1, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        assertEquals(1, acaoPendenteRepository.findAll().stream()
            .filter(acao -> acao.getStatus() == StatusAcaoPendente.REJEITADA)
            .count());
        verify(avaliadorIaService, times(2)).sugerirRespostaConversa(any(Projeto.class), anyString(), anyString());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
    }

    @Test
    @DisplayName("Inbox apaga mensagem Telegram da pendência quando eu já respondi no chat")
    void inboxApagaMensagemTelegramDaPendenciaQuandoEuJaRespondiNoChat() {
        ConversaResumo conversa = ConversaResumo.builder()
            .idConversa(77L)
            .idProjeto(88L)
            .nomeProjeto("Landing page")
            .nomeCliente("Cliente")
            .idFreelancer(269409L)
            .idCliente(1613360L)
            .build();
        MensagemConversa cliente = MensagemConversa.builder()
            .id(1001L)
            .idConversa(77L)
            .idPessoa(1613360L)
            .texto("Gostei, pode seguir?")
            .dataCriacaoMillis(1000L)
            .enviadaPorMim(false)
            .build();
        MensagemConversa minhaResposta = MensagemConversa.builder()
            .id(1002L)
            .idConversa(77L)
            .idPessoa(269409L)
            .texto("Ja respondi por aqui.")
            .dataCriacaoMillis(2000L)
            .enviadaPorMim(true)
            .build();
        when(scraperService.listarConversasInbox()).thenReturn(List.of(conversa));
        when(scraperService.listarMensagensConversa(77L))
            .thenReturn(List.of(cliente))
            .thenReturn(List.of(cliente, minhaResposta));
        when(telegramService.enviarAcaoPendenteComBotoes(anyString(), anyLong())).thenReturn(555L);

        autoAtendimentoService.sincronizarInbox(ModoSistema.LIVRE);
        autoAtendimentoService.sincronizarInbox(ModoSistema.LIVRE);

        assertEquals(0, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        assertEquals(1, acaoPendenteRepository.findAll().stream()
            .filter(acao -> acao.getStatus() == StatusAcaoPendente.REJEITADA)
            .count());
        verify(telegramService).apagarMensagemDaConta(555L);
    }

    @Test
    @DisplayName("Inbox ignora mensagem enviada pelo proprio freelancer")
    void inboxIgnoraMensagemEnviadaPeloProprioFreelancer() {
        ConversaResumo conversa = ConversaResumo.builder()
            .idConversa(77L)
            .idProjeto(88L)
            .nomeProjeto("Rastreador de odds")
            .nomeCliente("Cliente")
            .idFreelancer(269409L)
            .idCliente(1613360L)
            .build();
        MensagemConversa minhaMensagem = MensagemConversa.builder()
            .id(37098876L)
            .idConversa(77L)
            .idPessoa(269409L)
            .nomePessoa("Vitor Camillo")
            .texto("Ja foi atendido ?")
            .dataCriacaoMillis(1000L)
            .enviadaPorMim(true)
            .build();
        when(scraperService.listarConversasInbox()).thenReturn(List.of(conversa));
        when(scraperService.listarMensagensConversa(77L)).thenReturn(List.of(minhaMensagem));

        autoAtendimentoService.sincronizarInbox(ModoSistema.ENSINAMENTO);

        assertEquals(0, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        verify(avaliadorIaService, never()).sugerirRespostaConversa(any(Projeto.class), anyString(), anyString());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
    }

    @Test
    @DisplayName("Inbox nao responde se a ultima fala limpa for minha")
    void inboxNaoRespondeSeUltimaFalaLimpaForMinha() {
        ConversaResumo conversa = ConversaResumo.builder()
            .idConversa(77L)
            .idProjeto(88L)
            .nomeProjeto("Rastreador de odds")
            .nomeCliente("Cliente")
            .idFreelancer(269409L)
            .idCliente(1613360L)
            .build();
        MensagemConversa clienteAntigo = MensagemConversa.builder()
            .id(100L)
            .idConversa(77L)
            .idPessoa(1613360L)
            .nomePessoa("Cliente")
            .texto("Qual seria o prazo?")
            .dataCriacaoMillis(1000L)
            .enviadaPorMim(false)
            .build();
        MensagemConversa minhaMensagemNova = MensagemConversa.builder()
            .id(101L)
            .idConversa(77L)
            .idPessoa(269409L)
            .nomePessoa("Vitor Camillo")
            .texto("Oi, ja respondi isso acima.")
            .dataCriacaoMillis(2000L)
            .enviadaPorMim(true)
            .build();
        when(scraperService.listarConversasInbox()).thenReturn(List.of(conversa));
        when(scraperService.listarMensagensConversa(77L)).thenReturn(List.of(clienteAntigo, minhaMensagemNova));

        autoAtendimentoService.sincronizarInbox(ModoSistema.ENSINAMENTO);

        assertEquals(0, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        verify(avaliadorIaService, never()).sugerirRespostaConversa(any(Projeto.class), anyString(), anyString());
    }

    @Test
    @DisplayName("Inbox salva snapshot de autoria e historico limpo na pendencia")
    void inboxSalvaSnapshotDeAutoriaEHistoricoLimpoNaPendencia() throws Exception {
        ConversaResumo conversa = ConversaResumo.builder()
            .idConversa(77L)
            .idProjeto(88L)
            .nomeProjeto("Landing page")
            .nomeCliente("Cliente")
            .idCliente(1613360L)
            .idFreelancer(269409L)
            .nomeFreelancer("Vitor Camillo")
            .build();
        MensagemConversa minhaMensagem = MensagemConversa.builder()
            .id(100L)
            .idConversa(77L)
            .nomePessoa("Vitor Camillo")
            .texto("Oi, estou avaliando.")
            .dataCriacaoMillis(1000L)
            .enviadaPorMim(true)
            .build();
        MensagemConversa apagada = MensagemConversa.builder()
            .id(101L)
            .idConversa(77L)
            .texto(null)
            .dataCriacaoMillis(1500L)
            .enviadaPorMim(false)
            .excluida(true)
            .build();
        MensagemConversa clienteNova = MensagemConversa.builder()
            .id(102L)
            .idConversa(77L)
            .nomePessoa("Cliente")
            .texto("Consegue confirmar o prazo?")
            .dataCriacaoMillis(2000L)
            .enviadaPorMim(false)
            .build();
        when(scraperService.listarConversasInbox()).thenReturn(List.of(conversa));
        when(scraperService.listarMensagensConversa(77L)).thenReturn(List.of(minhaMensagem, apagada, clienteNova));

        autoAtendimentoService.sincronizarInbox(ModoSistema.ENSINAMENTO);

        AcaoPendente acao = acaoPendenteRepository.findAll().get(0);
        var payload = objectMapper.readTree(acao.getPayloadJson());
        assertEquals(102L, payload.path("mensagemGatilhoId").asLong());
        assertEquals("CLIENTE", payload.path("mensagemGatilhoAutor").asText());
        assertEquals(269409L, payload.path("idFreelancer").asLong());
        assertEquals(1613360L, payload.path("idCliente").asLong());
        assertTrue(payload.path("autoriaConfiavel").asBoolean());
        assertEquals("Oi, estou avaliando.", payload.path("ultimasMensagensMinhas").get(0).path("texto").asText());
        assertTrue(payload.path("mensagensIgnoradasResumo").toString().contains("apagada"));

        ArgumentCaptor<String> historicoCaptor = ArgumentCaptor.forClass(String.class);
        verify(avaliadorIaService).sugerirRespostaConversa(any(Projeto.class), historicoCaptor.capture(), eq("Consegue confirmar o prazo?"));
        assertFalse(historicoCaptor.getValue().contains("null"));
    }

    @Test
    @DisplayName("Conversa pendente expõe fatos confirmados, pendências e rascunho apresentável")
    void conversaPendenteExpoeFatosPendenciasERascunhoApresentavel() throws Exception {
        ConversaResumo conversa = ConversaResumo.builder()
            .idConversa(177L)
            .idProjeto(188L)
            .nomeProjeto("Landing page para consultoria")
            .nomeCliente("Cliente")
            .idCliente(1613360L)
            .idFreelancer(269409L)
            .nomeFreelancer("Vitor Camillo")
            .build();
        MensagemConversa minhaMensagem = MensagemConversa.builder()
            .id(201L)
            .idConversa(177L)
            .nomePessoa("Vitor Camillo")
            .texto("Oi, estou analisando os detalhes por aqui.")
            .dataCriacaoMillis(1000L)
            .enviadaPorMim(true)
            .build();
        MensagemConversa clienteNova = MensagemConversa.builder()
            .id(202L)
            .idConversa(177L)
            .nomePessoa("Cliente")
            .texto("O prazo ideal seria até sexta, consegue?")
            .dataCriacaoMillis(2000L)
            .enviadaPorMim(false)
            .build();
        when(scraperService.listarConversasInbox()).thenReturn(List.of(conversa));
        when(scraperService.listarMensagensConversa(177L)).thenReturn(List.of(minhaMensagem, clienteNova));
        when(avaliadorIaService.sugerirRespostaConversa(any(Projeto.class), anyString(), anyString()))
            .thenReturn("Perfeito, obrigado pelo retorno. Vou considerar esse prazo e sigo por aqui com a proposta.");

        autoAtendimentoService.sincronizarInbox(ModoSistema.ENSINAMENTO);

        AcaoPendente acao = acaoPendenteRepository.findAll().get(0);
        var payload = objectMapper.readTree(acao.getPayloadJson());
        String mensagem = payload.path("mensagem").asText();
        String mensagemLower = mensagem.toLowerCase();

        assertEquals(TipoAcaoPendente.RESPONDER_CONVERSA, acao.getTipo());
        assertTrue(payload.path("informacoesConfirmadas").toString().contains("Conversa: 177"));
        assertTrue(payload.path("informacoesConfirmadas").toString().contains("Mensagem do cliente identificada: 202"));
        assertTrue(payload.path("informacoesPendentes").toString().contains("Validar se"));
        assertFalse(mensagemLower.contains("teste"));
        assertFalse(mensagemLower.contains("simulad"));
        assertFalse(mensagemLower.contains("automatizado"));
        assertFalse(mensagemLower.contains("prompt"));
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
    }

    @Test
    @DisplayName("Conversa longa envia para IA só histórico recente, limpo e limitado")
    void conversaLongaEnviaParaIaSoHistoricoRecenteLimpoELimitado() throws Exception {
        ConversaResumo conversa = ConversaResumo.builder()
            .idConversa(377L)
            .idProjeto(388L)
            .nomeProjeto("Painel administrativo")
            .nomeCliente("Cliente")
            .idCliente(1613360L)
            .idFreelancer(269409L)
            .nomeFreelancer("Vitor Camillo")
            .build();
        List<MensagemConversa> mensagens = new java.util.ArrayList<>();
        for (int i = 1; i <= 65; i++) {
            boolean minha = i % 4 == 0;
            mensagens.add(MensagemConversa.builder()
                .id(10_000L + i)
                .idConversa(377L)
                .idPessoa(minha ? 269409L : 1613360L)
                .nomePessoa(minha ? "Vitor Camillo" : "Cliente")
                .texto((minha ? "Minha confirmacao antiga " : "Cliente detalhe antigo ") + String.format("%03d", i))
                .dataCriacaoMillis(i * 1000L)
                .enviadaPorMim(minha)
                .build());
        }
        mensagens.add(MensagemConversa.builder()
            .id(20_001L)
            .idConversa(377L)
            .texto("MENSAGEM APAGADA QUE NAO PODE ENTRAR")
            .dataCriacaoMillis(80_000L)
            .enviadaPorMim(false)
            .excluida(true)
            .build());
        mensagens.add(MensagemConversa.builder()
            .id(20_002L)
            .idConversa(377L)
            .texto("MENSAGEM DE SISTEMA QUE NAO PODE ENTRAR")
            .dataCriacaoMillis(81_000L)
            .enviadaPorMim(false)
            .sistema(true)
            .build());
        mensagens.add(MensagemConversa.builder()
            .id(20_003L)
            .idConversa(377L)
            .texto("   ")
            .dataCriacaoMillis(82_000L)
            .enviadaPorMim(false)
            .build());
        mensagens.add(MensagemConversa.builder()
            .id(30_000L)
            .idConversa(377L)
            .idPessoa(1613360L)
            .nomePessoa("Cliente")
            .texto("Mensagem final: preciso fechar escopo do painel administrativo.")
            .dataCriacaoMillis(90_000L)
            .enviadaPorMim(false)
            .build());
        when(scraperService.listarConversasInbox()).thenReturn(List.of(conversa));
        when(scraperService.listarMensagensConversa(377L)).thenReturn(mensagens);

        autoAtendimentoService.sincronizarInbox(ModoSistema.ENSINAMENTO);

        ArgumentCaptor<String> historicoCaptor = ArgumentCaptor.forClass(String.class);
        verify(avaliadorIaService).sugerirRespostaConversa(
            any(Projeto.class),
            historicoCaptor.capture(),
            eq("Mensagem final: preciso fechar escopo do painel administrativo.")
        );
        String historico = historicoCaptor.getValue();
        assertTrue(historico.contains("historico anterior omitido"));
        assertFalse(historico.contains("Cliente detalhe antigo 001"));
        assertFalse(historico.contains("Minha confirmacao antiga 004"));
        assertTrue(historico.contains("Mensagem final: preciso fechar escopo do painel administrativo."));
        assertFalse(historico.contains("MENSAGEM APAGADA"));
        assertFalse(historico.contains("MENSAGEM DE SISTEMA"));
        assertTrue(historico.length() < 12_000, "Historico enviado para IA ficou grande demais: " + historico.length());

        AcaoPendente acao = acaoPendenteRepository.findAll().get(0);
        var payload = objectMapper.readTree(acao.getPayloadJson());
        assertEquals(3, payload.path("ultimasMensagensCliente").size());
        assertEquals(3, payload.path("ultimasMensagensMinhas").size());
        assertTrue(payload.path("mensagensIgnoradasResumo").toString().contains("apagada"));
        assertTrue(payload.path("mensagensIgnoradasResumo").toString().contains("sistema"));
        assertTrue(payload.path("mensagensIgnoradasResumo").toString().contains("sem texto"));
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
    }

}
