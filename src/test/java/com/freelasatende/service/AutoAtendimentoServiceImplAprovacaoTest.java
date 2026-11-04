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
import com.freelasatende.service.impl.MarketplaceAdapterErrorType;
import com.freelasatende.service.impl.MarketplaceAdapterException;
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
class AutoAtendimentoServiceImplAprovacaoTest extends AutoAtendimentoServiceImplTestSupport {
    @Test
    @DisplayName("Aprovacao humana chama scraper uma unica vez")
    void aprovacaoHumanaChamaScraperUmaUnicaVez() {
        habilitarEscritaRealMockada();
        Long projetoId = sequencia.incrementAndGet();
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(projetoId)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Projeto aprovado")
            .resumo("Confirmar prazo com cliente")
            .payloadJson("{\"projetoId\":" + projetoId + ",\"tituloProjeto\":\"Projeto aprovado\",\"mensagem\":\"Oi, voce consegue me confirmar o prazo ideal?\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);

        String retorno = autoAtendimentoService.processarFeedback(acao.getId(), true, "aprovado");

        assertTrue(retorno.contains("executada"));
        verify(scraperService, times(1)).enviarPergunta(eq(projetoId), anyString());
    }

    @Test
    @DisplayName("Escrita real desligada bloqueia aprovação sem chamar scraper")
    void escritaRealDesligadaBloqueiaAprovacaoSemChamarScraper() {
        Long projetoId = sequencia.incrementAndGet();
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(projetoId)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Projeto bloqueado por flag")
            .resumo("Confirmar prazo com cliente")
            .payloadJson("{\"projetoId\":" + projetoId + ",\"tituloProjeto\":\"Projeto bloqueado por flag\",\"mensagem\":\"Oi, voce consegue confirmar o prazo ideal?\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);

        String retorno = autoAtendimentoService.processarFeedback(acao.getId(), true, "BOM_TOM");

        AcaoPendente atualizada = acaoPendenteRepository.findById(acao.getId()).orElseThrow();
        assertTrue(retorno.contains("real.write.enabled=true"));
        assertEquals(StatusAcaoPendente.PENDENTE, atualizada.getStatus());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
    }

    @Test
    @DisplayName("Simulação de envio aprova sem chamar scraper de escrita")
    void simulacaoEnvioAprovaSemChamarScraperDeEscrita() {
        Long projetoId = sequencia.incrementAndGet();
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(projetoId)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Projeto simulado")
            .resumo("Confirmar prazo com cliente")
            .payloadJson("{\"projetoId\":" + projetoId + ",\"tituloProjeto\":\"Projeto simulado\",\"mensagem\":\"Oi, voce consegue confirmar o prazo ideal?\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);
        habilitarSimulacaoEnvio();

        String retorno = autoAtendimentoService.processarFeedback(acao.getId(), true, "BOM_TOM");

        AcaoPendente atualizada = acaoPendenteRepository.findById(acao.getId()).orElseThrow();
        assertTrue(retorno.contains("simulada"));
        assertTrue(retorno.contains("Nenhum envio real"));
        assertEquals(StatusAcaoPendente.EXECUTADA, atualizada.getStatus());
        assertEquals(1, mensagemClienteRegistroRepository.countByStatus(StatusMensagemCliente.SIMULADA));
        assertEquals(0, mensagemClienteRegistroRepository.countByStatus(StatusMensagemCliente.ENVIADA));
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
        verify(scraperService, never()).enviarProposta(any(Projeto.class), any(Proposta.class));
        verify(scraperService, never()).cancelarProposta(anyLong());
    }

    @Test
    @DisplayName("Modo ensino respeita aprovação humana e envia pergunta")
    void modoEnsinoRespeitaAprovacaoHumanaEEnviaPergunta() {
        habilitarEscritaRealMockada();
        Long projetoId = sequencia.incrementAndGet();
        String mensagem = "Oi, voce consegue confirmar o prazo ideal?";
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(projetoId)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.ENSINAMENTO)
            .tituloProjeto("Projeto ensino")
            .resumo("Pergunta depende de conversa")
            .payloadJson("{\"projetoId\":" + projetoId
                + ",\"tituloProjeto\":\"Projeto ensino\""
                + ",\"mensagem\":\"" + mensagem + "\""
                + ",\"conversaEnsinoObrigatoria\":true"
                + ",\"perguntaParaUsuario\":\"Devo perguntar prazo ao cliente?\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);

        String retorno = autoAtendimentoService.processarFeedback(acao.getId(), true, "aprovado");

        assertTrue(retorno.contains("executada"));
        assertEquals(StatusAcaoPendente.EXECUTADA, acaoPendenteRepository.findById(acao.getId()).orElseThrow().getStatus());
        verify(scraperService, times(1)).enviarPergunta(eq(projetoId), eq(mensagem));
    }

    @Test
    @DisplayName("Modo ensino respeita botão de aprovação e envia resposta de conversa")
    void modoEnsinoRespeitaBotaoDeAprovacaoEEnviaRespostaDeConversa() {
        habilitarEscritaRealMockada();
        Long projetoId = sequencia.incrementAndGet();
        Long conversaId = sequencia.incrementAndGet();
        Long mensagemGatilhoId = sequencia.incrementAndGet();
        String mensagem = "Li sim. Hoje ja existe alguma base pronta ou seria algo começando do zero?";
        when(scraperService.listarMensagensConversa(conversaId)).thenReturn(List.of(MensagemConversa.builder()
            .id(mensagemGatilhoId)
            .idConversa(conversaId)
            .texto("tu ja leu o anuncio?")
            .dataCriacaoMillis(1000L)
            .enviadaPorMim(false)
            .build()));
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(projetoId)
            .conversaId(conversaId)
            .tipo(TipoAcaoPendente.RESPONDER_CONVERSA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.ENSINAMENTO)
            .tituloProjeto("Projeto ensino conversa")
            .resumo("Resposta depende de conversa")
            .payloadJson("{\"projetoId\":" + projetoId
                + ",\"conversaId\":" + conversaId
                + ",\"mensagemGatilhoId\":" + mensagemGatilhoId
                + ",\"tituloProjeto\":\"Projeto ensino conversa\""
                + ",\"mensagem\":\"" + mensagem + "\""
                + ",\"conversaEnsinoObrigatoria\":true"
                + ",\"perguntaParaUsuario\":\"Devo responder assim?\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);

        String retorno = autoAtendimentoService.processarFeedback(acao.getId(), true, "BOM_TOM");

        assertTrue(retorno.contains("executada"));
        assertEquals(StatusAcaoPendente.EXECUTADA, acaoPendenteRepository.findById(acao.getId()).orElseThrow().getStatus());
        verify(scraperService, times(1)).enviarMensagemConversa(eq(conversaId), eq(mensagem));
    }

    @Test
    @DisplayName("Modo ensino libera aprovação depois de orientação salva")
    void modoEnsinoLiberaAprovacaoDepoisDeOrientacaoSalva() {
        habilitarEscritaRealMockada();
        Long projetoId = sequencia.incrementAndGet();
        String mensagem = "Oi, voce consegue confirmar o prazo ideal?";
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(projetoId)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.ENSINAMENTO)
            .tituloProjeto("Projeto ensino liberado")
            .resumo("Pergunta depende de orientação")
            .payloadJson("{\"projetoId\":" + projetoId
                + ",\"tituloProjeto\":\"Projeto ensino liberado\""
                + ",\"mensagem\":\"" + mensagem + "\""
                + ",\"conversaEnsinoObrigatoria\":true"
                + ",\"perguntaParaUsuario\":\"Devo perguntar prazo ao cliente?\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);

        String ensino = autoAtendimentoService.registrarContextoEnsino(
            acao.getId(),
            "pergunte prazo somente quando nao houver data clara no projeto"
        );
        String retorno = autoAtendimentoService.processarFeedback(acao.getId(), true, "BOM_TOM");

        assertTrue(ensino.contains("Contexto salvo"));
        assertTrue(retorno.contains("executada"));
        assertEquals(StatusAcaoPendente.EXECUTADA, acaoPendenteRepository.findById(acao.getId()).orElseThrow().getStatus());
        verify(scraperService, times(1)).enviarPergunta(eq(projetoId), eq(mensagem));
    }

    @Test
    @DisplayName("Aprovação sem motivo não envia e mantém pendente")
    void aprovacaoSemMotivoNaoEnvia() {
        Long projetoId = sequencia.incrementAndGet();
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(projetoId)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.ENSINAMENTO)
            .tituloProjeto("Projeto sem motivo")
            .resumo("Confirmar prazo")
            .payloadJson("{\"projetoId\":" + projetoId + ",\"tituloProjeto\":\"Projeto sem motivo\",\"mensagem\":\"Oi, voce consegue confirmar o prazo ideal?\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);

        String retorno = autoAtendimentoService.processarFeedback(acao.getId(), true, "");

        assertTrue(retorno.toLowerCase().contains("feedback obrigatório"));
        assertEquals(StatusAcaoPendente.PENDENTE, acaoPendenteRepository.findById(acao.getId()).orElseThrow().getStatus());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
    }

    @Test
    @DisplayName("Aprovação de resposta vazia não envia e mantém pendente")
    void aprovacaoDeRespostaVaziaNaoEnvia() {
        habilitarEscritaRealMockada();
        Long projetoId = sequencia.incrementAndGet();
        Long conversaId = sequencia.incrementAndGet();
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(projetoId)
            .conversaId(conversaId)
            .tipo(TipoAcaoPendente.RESPONDER_CONVERSA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Projeto resposta vazia")
            .resumo("IA nao gerou resposta valida")
            .payloadJson("{\"projetoId\":" + projetoId
                + ",\"conversaId\":" + conversaId
                + ",\"tituloProjeto\":\"Projeto resposta vazia\""
                + ",\"mensagem\":\"\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);

        String retorno = autoAtendimentoService.processarFeedback(acao.getId(), true, "BOM_TOM");

        assertTrue(retorno.contains("não possui texto"));
        assertEquals(StatusAcaoPendente.PENDENTE, acaoPendenteRepository.findById(acao.getId()).orElseThrow().getStatus());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
    }

    @Test
    @DisplayName("Aprovação bloqueia payload alterado e não envia conteúdo inseguro")
    void aprovacaoRevalidaPayloadAlteradoENaoEnviaConteudoInseguro() {
        Long projetoId = sequencia.incrementAndGet();
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(projetoId)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Projeto payload alterado")
            .resumo("Confirmar prazo com cliente")
            .payloadJson("{\"projetoId\":" + projetoId + ",\"tituloProjeto\":\"Projeto payload alterado\",\"mensagem\":\"Me chama no WhatsApp para fechar por fora da plataforma.\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);

        String retorno = autoAtendimentoService.processarFeedback(acao.getId(), true, "aprovado sem perceber");

        assertTrue(retorno.toLowerCase().contains("bloqueada"));
        assertEquals(StatusAcaoPendente.PENDENTE, acaoPendenteRepository.findById(acao.getId()).orElseThrow().getStatus());
        verify(scraperService, never()).enviarPergunta(anyLong(), anyString());
    }

}
