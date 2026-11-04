package com.freelasatende.service;

import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.StatusMensagemCliente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.service.impl.MarketplaceAdapterErrorType;
import com.freelasatende.service.impl.MarketplaceAdapterException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoAtendimentoServiceImplAprovacaoFalhasTest extends AutoAtendimentoServiceImplTestSupport {

    @Test
    @DisplayName("Falha ao enviar pergunta marca erro e não registra envio")
    void falhaAoEnviarPerguntaMarcaErroENaoRegistraEnvio() {
        habilitarEscritaRealMockada();
        Long projetoId = sequencia.incrementAndGet();
        when(scraperService.enviarPergunta(eq(projetoId), anyString())).thenReturn(false);
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(projetoId)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Projeto com falha HTTP")
            .resumo("Confirmar prazo com cliente")
            .payloadJson("{\"projetoId\":" + projetoId + ",\"tituloProjeto\":\"Projeto com falha HTTP\",\"mensagem\":\"Oi, voce consegue me confirmar o prazo ideal?\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);

        String retorno = autoAtendimentoService.processarFeedback(acao.getId(), true, "BOM_TOM");

        AcaoPendente atualizada = acaoPendenteRepository.findById(acao.getId()).orElseThrow();
        assertTrue(retorno.contains("falhou"));
        assertEquals(StatusAcaoPendente.ERRO, atualizada.getStatus());
        assertEquals(0, mensagemClienteRegistroRepository.countByStatus(StatusMensagemCliente.ENVIADA));
        verify(gerenciadorConexoesService, never()).registrarUsoConexao();
    }

    @Test
    @DisplayName("Falha temporaria de rede ao enviar pergunta mantem pendente")
    void falhaTemporariaRedeAoEnviarPerguntaMantemPendente() {
        habilitarEscritaRealMockada();
        Long projetoId = sequencia.incrementAndGet();
        when(scraperService.enviarPergunta(eq(projetoId), anyString())).thenThrow(new MarketplaceAdapterException(
            com.freelasatende.model.enums.PlataformaFreela.FREELAS_99,
            MarketplaceAdapterErrorType.FALHA_REDE,
            "99Freelas envio de pergunta retornou HTTP 502"
        ));
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(projetoId)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Projeto com gateway instavel")
            .resumo("Confirmar prazo com cliente")
            .payloadJson("{\"projetoId\":" + projetoId
                + ",\"tituloProjeto\":\"Projeto com gateway instavel\""
                + ",\"mensagem\":\"Oi, voce consegue confirmar o prazo ideal?\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);

        String retorno = autoAtendimentoService.processarFeedback(acao.getId(), true, "BOM_TOM");

        AcaoPendente atualizada = acaoPendenteRepository.findById(acao.getId()).orElseThrow();
        assertTrue(retorno.contains("continua pendente"));
        assertEquals(StatusAcaoPendente.PENDENTE, atualizada.getStatus());
        assertEquals(0, mensagemClienteRegistroRepository.countByStatus(StatusMensagemCliente.ENVIADA));
        verify(gerenciadorConexoesService, never()).registrarUsoConexao();
    }

    @Test
    @DisplayName("Falha antes do envio de abordagem inicial automatica encerra sem manter pendente")
    void falhaAntesDoEnvioDeAbordagemInicialAutomaticaEncerraSemManterPendente() {
        habilitarEscritaRealMockada();
        Long projetoId = sequencia.incrementAndGet();
        when(scraperService.enviarPergunta(eq(projetoId), anyString())).thenThrow(new MarketplaceAdapterException(
            com.freelasatende.model.enums.PlataformaFreela.FREELAS_99,
            MarketplaceAdapterErrorType.FALHA_AUTENTICACAO,
            "Workana form de pergunta retornou HTTP 403"
        ));
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(projetoId)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Projeto Workana")
            .resumo("Abordagem inicial automatica | Ja foi atendido?")
            .payloadJson("{\"projetoId\":" + projetoId
                + ",\"tituloProjeto\":\"Projeto Workana\""
                + ",\"mensagem\":\"Ja foi atendido?\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);

        String retorno = autoAtendimentoService.processarFeedback(acao.getId(), true, "BOM_TOM");

        AcaoPendente atualizada = acaoPendenteRepository.findById(acao.getId()).orElseThrow();
        assertTrue(retorno.contains("foi encerrada"));
        assertFalse(retorno.contains("continua pendente"));
        assertEquals(StatusAcaoPendente.ERRO, atualizada.getStatus());
        assertEquals(0, mensagemClienteRegistroRepository.countByStatus(StatusMensagemCliente.ENVIADA));
        verify(scraperService).enviarPergunta(eq(projetoId), eq("Ja foi atendido?"));
        verify(gerenciadorConexoesService, never()).registrarUsoConexao();
    }

    @Test
    @DisplayName("Falha antes do envio da conversa mantém ação pendente")
    void falhaAntesDoEnvioDaConversaMantemAcaoPendente() {
        habilitarEscritaRealMockada();
        Long projetoId = sequencia.incrementAndGet();
        Long conversaId = sequencia.incrementAndGet();
        when(scraperService.listarMensagensConversa(conversaId)).thenReturn(List.of());
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(projetoId)
            .conversaId(conversaId)
            .tipo(TipoAcaoPendente.RESPONDER_CONVERSA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.LIVRE)
            .tituloProjeto("Projeto conversa instavel")
            .resumo("Responder cliente")
            .payloadJson("{\"projetoId\":" + projetoId
                + ",\"conversaId\":" + conversaId
                + ",\"tituloProjeto\":\"Projeto conversa instavel\""
                + ",\"mensagem\":\"Perfeito, vou te responder por aqui.\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);

        String retorno = autoAtendimentoService.processarFeedback(acao.getId(), true, "BOM_TOM");

        AcaoPendente atualizada = acaoPendenteRepository.findById(acao.getId()).orElseThrow();
        assertTrue(retorno.contains("continua pendente"));
        assertEquals(StatusAcaoPendente.PENDENTE, atualizada.getStatus());
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
    }

    @Test
    @DisplayName("Resposta desatualizada por mensagem nova mantém ação pendente")
    void respostaDesatualizadaPorMensagemNovaMantemAcaoPendente() {
        habilitarEscritaRealMockada();
        Long projetoId = sequencia.incrementAndGet();
        Long conversaId = sequencia.incrementAndGet();
        Long mensagemGatilhoId = sequencia.incrementAndGet();
        when(scraperService.listarMensagensConversa(conversaId)).thenReturn(List.of(
            MensagemConversa.builder()
                .id(mensagemGatilhoId + 1)
                .idConversa(conversaId)
                .texto("Nova mensagem antes da aprovacao")
                .dataCriacaoMillis(System.currentTimeMillis())
                .enviadaPorMim(false)
                .build()
        ));
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(projetoId)
            .conversaId(conversaId)
            .tipo(TipoAcaoPendente.RESPONDER_CONVERSA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.LIVRE)
            .tituloProjeto("Projeto conversa atualizada")
            .resumo("Responder cliente")
            .payloadJson("{\"projetoId\":" + projetoId
                + ",\"conversaId\":" + conversaId
                + ",\"tituloProjeto\":\"Projeto conversa atualizada\""
                + ",\"mensagem\":\"Sim, consigo seguir por aqui.\""
                + ",\"mensagemGatilhoId\":" + mensagemGatilhoId + "}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);

        String retorno = autoAtendimentoService.processarFeedback(acao.getId(), true, "BOM_TOM");

        AcaoPendente atualizada = acaoPendenteRepository.findById(acao.getId()).orElseThrow();
        assertTrue(retorno.contains("continua pendente"));
        assertEquals(StatusAcaoPendente.PENDENTE, atualizada.getStatus());
        assertEquals(0, mensagemClienteRegistroRepository.countByStatus(StatusMensagemCliente.ENVIADA));
        verify(scraperService, never()).enviarMensagemConversa(anyLong(), anyString());
    }

    @Test
    @DisplayName("403 ao enviar conversa mantém ação pendente e não registra envio")
    void falha403AoEnviarConversaMantemAcaoPendenteENaoRegistraEnvio() {
        habilitarEscritaRealMockada();
        Long projetoId = sequencia.incrementAndGet();
        Long conversaId = sequencia.incrementAndGet();
        Long mensagemGatilhoId = sequencia.incrementAndGet();
        when(scraperService.listarMensagensConversa(conversaId)).thenReturn(List.of(
            MensagemConversa.builder()
                .id(mensagemGatilhoId)
                .idConversa(conversaId)
                .texto("Pode seguir?")
                .dataCriacaoMillis(System.currentTimeMillis())
                .enviadaPorMim(false)
                .build()
        ));
        when(scraperService.enviarMensagemConversa(eq(conversaId), anyString())).thenThrow(new MarketplaceAdapterException(
            com.freelasatende.model.enums.PlataformaFreela.FREELAS_99,
            MarketplaceAdapterErrorType.FALHA_AUTENTICACAO,
            "Workana envio de mensagem retornou HTTP 403: Acesso Negado"
        ));
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(projetoId)
            .conversaId(conversaId)
            .tipo(TipoAcaoPendente.RESPONDER_CONVERSA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.LIVRE)
            .tituloProjeto("Projeto conversa 403")
            .resumo("Responder cliente")
            .payloadJson("{\"projetoId\":" + projetoId
                + ",\"conversaId\":" + conversaId
                + ",\"tituloProjeto\":\"Projeto conversa 403\""
                + ",\"mensagem\":\"Sim, consigo seguir por aqui.\""
                + ",\"mensagemGatilhoId\":" + mensagemGatilhoId + "}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);

        String retorno = autoAtendimentoService.processarFeedback(acao.getId(), true, "BOM_TOM");

        AcaoPendente atualizada = acaoPendenteRepository.findById(acao.getId()).orElseThrow();
        assertTrue(retorno.contains("continua pendente"));
        assertEquals(StatusAcaoPendente.PENDENTE, atualizada.getStatus());
        assertEquals(0, mensagemClienteRegistroRepository.countByStatus(StatusMensagemCliente.ENVIADA));
        verify(scraperService).enviarMensagemConversa(eq(conversaId), eq("Sim, consigo seguir por aqui."));
    }

    @Test
    @DisplayName("404 ao enviar conversa removida finaliza erro sem manter pendente")
    void conversaRemovidaAoEnviarFinalizaErroSemManterPendente() {
        habilitarEscritaRealMockada();
        Long projetoId = sequencia.incrementAndGet();
        Long conversaId = sequencia.incrementAndGet();
        Long mensagemGatilhoId = sequencia.incrementAndGet();
        when(scraperService.listarMensagensConversa(conversaId)).thenReturn(List.of(
            MensagemConversa.builder()
                .id(mensagemGatilhoId)
                .idConversa(conversaId)
                .texto("Pode seguir?")
                .dataCriacaoMillis(System.currentTimeMillis())
                .enviadaPorMim(false)
                .build()
        ));
        when(scraperService.enviarMensagemConversa(eq(conversaId), anyString())).thenThrow(new MarketplaceAdapterException(
            com.freelasatende.model.enums.PlataformaFreela.FREELAS_99,
            MarketplaceAdapterErrorType.CONVERSA_INACESSIVEL,
            "Conversa inacessivel ou removida: HTTP 404"
        ));
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(projetoId)
            .conversaId(conversaId)
            .tipo(TipoAcaoPendente.RESPONDER_CONVERSA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.LIVRE)
            .tituloProjeto("Projeto conversa removida")
            .resumo("Responder cliente")
            .payloadJson("{\"projetoId\":" + projetoId
                + ",\"conversaId\":" + conversaId
                + ",\"tituloProjeto\":\"Projeto conversa removida\""
                + ",\"mensagem\":\"Sim, consigo seguir por aqui.\""
                + ",\"mensagemGatilhoId\":" + mensagemGatilhoId + "}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);

        String retorno = autoAtendimentoService.processarFeedback(acao.getId(), true, "BOM_TOM");

        AcaoPendente atualizada = acaoPendenteRepository.findById(acao.getId()).orElseThrow();
        assertTrue(retorno.contains("falhou ao executar"));
        assertFalse(retorno.contains("continua pendente"));
        assertEquals(StatusAcaoPendente.ERRO, atualizada.getStatus());
        assertEquals(0, mensagemClienteRegistroRepository.countByStatus(StatusMensagemCliente.ENVIADA));
        verify(scraperService).enviarMensagemConversa(eq(conversaId), eq("Sim, consigo seguir por aqui."));
    }
}
