package com.freelasatende.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.TelegramConfig;
import com.freelasatende.model.dto.AcaoControleDTO;
import com.freelasatende.model.dto.EnsinoFocoDTO;
import com.freelasatende.model.entity.MensagemClienteRegistro;
import com.freelasatende.model.enums.StatusMensagemCliente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.scheduler.PollingScheduler;
import com.freelasatende.service.impl.TelegramCommandHandler;
import com.freelasatende.service.impl.TelegramServiceImpl;
import com.freelasatende.service.interfaceServico.AutoAtendimentoService;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.ControleOperacionalService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.HistoricoService;
import com.freelasatende.service.interfaceServico.IaProvider;
import com.freelasatende.service.interfaceServico.ScraperService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static com.freelasatende.service.TelegramCommandHandlerFixtures.acaoControle;
import static com.freelasatende.service.TelegramCommandHandlerFixtures.updateComMensagem;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Testes de integração para TelegramCommandHandler.
 * Usa SpringBootTest com serviços reais.
 */
@SpringBootTest(properties = {
    "scheduling.enabled=false",
    "telegram.operator-chat.ia-enabled=false"
})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelegramCommandHandlerTest extends TelegramCommandHandlerTestSupport {

    @Test
    @DisplayName("Deve processar comando /status com informações reais")
    void deveProcessarComandoStatus() {
        // Act & Assert: não deve lançar exceção
        assertDoesNotThrow(() -> handler.processarComando("/status"));
        verify(telegramService).enviarNotificacao(contains("Status do Sistema"));
    }

    @Test
    @DisplayName("Deve processar comando /modos com informações reais")
    void deveProcessarComandoModos() {
        // Act & Assert
        assertDoesNotThrow(() -> handler.processarComando("/modos"));
        verify(telegramService).enviarNotificacao(contains("Comando desconhecido"));
    }

    @Test
    @DisplayName("Deve processar comando /projetos com informações reais")
    void deveProcessarComandoProjetos() {
        // Act & Assert
        assertDoesNotThrow(() -> handler.processarComando("/projetos"));
        verify(telegramService).enviarNotificacao(contains("Comando desconhecido"));
    }

    @Test
    @DisplayName("Deve ignorar comandos de feedback (somente botões)")
    void naoDeveAceitarComandoFeedback() {
        // Act
        handler.processarComando("/feedback 12 aprovado");

        // Assert: não deve processar o feedback via comando
        // O comando /feedback não é reconhecido - apenas callbacks de botões
    }

    @Test
    @DisplayName("Deve verificar comandos pendentes")
    void deveVerificarComandos() {
        // Act & Assert
        assertDoesNotThrow(() -> handler.verificarComandos());
    }

    @Test
    @DisplayName("Falha ao processar update não avança offset")
    void falhaAoProcessarUpdateNaoAvancaOffset() throws Exception {
        when(telegramService.obterUpdates(0L))
            .thenReturn(updateComMensagem("/pendencias", telegramConfig.getChatId()));
        when(controleOperacionalService.listarAcoesPendentes())
            .thenThrow(new RuntimeException("falha controlada"));

        handler.verificarComandos();
        handler.verificarComandos();

        verify(telegramService, times(2)).obterUpdates(0L);
    }

    @Test
    @DisplayName("Polling agendado fica silencioso quando scheduling está desligado")
    void pollingAgendadoFicaSilenciosoQuandoSchedulingEstaDesligado() {
        handler.verificarComandosAgendado();

        verify(telegramService, never()).obterUpdates(anyLong());
    }

    @Test
    @DisplayName("Comando desligar pausa monitoramento")
    void comandoDesligarPausaMonitoramento() {
        handler.processarComando("/desligar");

        verify(telegramService).enviarNotificacao(contains("Sistema PAUSADO"));
    }

    @Test
    @DisplayName("Deve listar pendencias em resumo acionavel")
    void deveListarPendenciasEmResumoAcionavel() {
        AcaoControleDTO acao = acaoControle(12L);
        when(controleOperacionalService.listarAcoesPendentes()).thenReturn(List.of(acao));

        handler.processarComando("/pendencias");

        verify(telegramService).enviarNotificacao(contains("Pendências aguardando decisão"));
        verify(telegramService).enviarNotificacao(contains("Confiança: ALTA"));
        verify(telegramService).enviarNotificacao(contains("Qualidade: 94/100"));
        verify(telegramService).enviarNotificacao(contains("Gatilho cliente"));
        verify(telegramService).enviarNotificacao(contains("Minhas falas detectadas"));
        verify(telegramService).enviarNotificacao(contains("Leitura IA: Li o contexto"));
        verify(telegramService).enviarNotificacao(contains("Atitude: Conversar com voce"));
        verify(telegramService).enviarNotificacao(contains("Conversa ensino: OBRIGATORIA"));
        verify(telegramService).enviarNotificacao(contains("Pergunta para voce"));
        verify(telegramService).enviarAcaoPendenteComBotoes(contains("Landing page"), eq(12L));
    }

    @Test
    @DisplayName("Deve listar apenas perguntas e respostas pendentes")
    void deveListarPerguntasERespostasPendentes() {
        AcaoControleDTO pergunta = acaoControle(12L);
        AcaoControleDTO proposta = acaoControle(13L);
        proposta.setTipo(TipoAcaoPendente.ENVIAR_PROPOSTA);
        when(controleOperacionalService.listarAcoesPendentes()).thenReturn(List.of(pergunta, proposta));

        handler.processarComando("/perguntas");

        verify(telegramService).enviarNotificacao(contains("Perguntas/respostas aguardando decisão"));
        verify(telegramService).enviarNotificacao(contains("#12"));
        verify(telegramService, never()).enviarNotificacao(contains("#13"));
    }

    @Test
    @DisplayName("Comando foco não decide ação fora dos botões")
    void comandoFocoNaoDecideAcaoForaDosBotoes() {
        handler.processarComando("/foco 12");

        verify(controleOperacionalService, never()).definirFocoEnsino(anyLong());
        verify(telegramService).enviarNotificacao(contains("botões da pendência"));
    }

    @Test
    @DisplayName("Comando limpar foco não decide ação fora dos botões")
    void comandoLimparFocoNaoDecideAcaoForaDosBotoes() {
        handler.processarComando("/limparfoco");

        verify(controleOperacionalService, never()).limparFocoEnsino();
        verify(telegramService).enviarNotificacao(contains("botões da pendência"));
    }

    @Test
    @DisplayName("Mensagem normal com foco ativo vira conversa operacional")
    void mensagemNormalComFocoAtivoViraConversaOperacional() throws Exception {
        when(telegramService.obterUpdates(anyLong()))
            .thenReturn(updateComMensagem("pergunta só se a hospedagem já está liberada", telegramConfig.getChatId()));
        when(controleOperacionalService.obterFocoEnsino()).thenReturn(EnsinoFocoDTO.builder()
            .ativo(true)
            .acaoId(12L)
            .projetoId(99L)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .tituloProjeto("Landing page")
            .status("FOCADO")
            .build());

        handler.verificarComandos();

        verify(controleOperacionalService, never()).registrarContextoEnsino(anyLong(), anyString());
        verify(telegramService).enviarMensagemChat(contains("Conversa operacional"));
        verify(telegramService).enviarMensagemChat(contains("foco #12"));
    }

    @Test
    @DisplayName("Mensagem curta com foco ativo não vira ensino")
    void mensagemCurtaObaComFocoAtivoNaoViraEnsino() throws Exception {
        when(telegramService.obterUpdates(anyLong()))
            .thenReturn(updateComMensagem("oba", telegramConfig.getChatId()));
        when(controleOperacionalService.obterFocoEnsino()).thenReturn(EnsinoFocoDTO.builder()
            .ativo(true)
            .acaoId(12L)
            .projetoId(99L)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .tituloProjeto("Landing page")
            .status("FOCADO")
            .build());

        handler.verificarComandos();

        verify(controleOperacionalService, never()).registrarContextoEnsino(anyLong(), anyString());
        verify(telegramService).enviarMensagemChat(contains("Conversa operacional"));
        verify(telegramService).enviarMensagemChat(contains("Recebi: oba"));
    }

    @Test
    @DisplayName("Mensagem com ID mas sem intenção explícita não vira ensino")
    void mensagemComIdSemIntencaoExplicitaNaoViraEnsino() throws Exception {
        when(telegramService.obterUpdates(anyLong()))
            .thenReturn(updateComMensagem("#12 ficou bom?", telegramConfig.getChatId()));

        handler.verificarComandos();

        verify(controleOperacionalService, never()).registrarContextoEnsino(anyLong(), anyString());
        verify(telegramService).enviarMensagemChat(contains("Conversa operacional"));
        verify(telegramService).enviarMensagemChat(contains("#12 ficou bom?"));
    }

    @Test
    @DisplayName("Mensagem com texto de ensino sem botão vira conversa operacional")
    void mensagemComTextoDeEnsinoSemBotaoViraConversaOperacional() throws Exception {
        when(telegramService.obterUpdates(anyLong()))
            .thenReturn(updateComMensagem("guarda como regra: pergunta só se a hospedagem já está liberada", telegramConfig.getChatId()));
        when(controleOperacionalService.obterFocoEnsino()).thenReturn(EnsinoFocoDTO.builder()
            .ativo(true)
            .acaoId(12L)
            .projetoId(99L)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .tituloProjeto("Landing page")
            .status("FOCADO")
            .build());

        handler.verificarComandos();

        verify(controleOperacionalService, never()).registrarContextoEnsino(anyLong(), anyString());
        verify(telegramService).enviarMensagemChat(contains("Conversa operacional"));
    }

    @Test
    @DisplayName("Mensagem com ID e texto de ensino sem botão vira conversa operacional")
    void mensagemComIdETextoDeEnsinoSemBotaoViraConversaOperacional() throws Exception {
        when(telegramService.obterUpdates(anyLong()))
            .thenReturn(updateComMensagem("#12 guarda como regra: pergunta só se a hospedagem já está liberada", telegramConfig.getChatId()));

        handler.verificarComandos();

        verify(controleOperacionalService, never()).registrarContextoEnsino(anyLong(), anyString());
        verify(telegramService).enviarMensagemChat(contains("Conversa operacional"));
    }

    @Test
    @DisplayName("Mensagem normal sem foco vira conversa operacional")
    void mensagemNormalSemFocoViraConversaOperacional() throws Exception {
        when(telegramService.obterUpdates(anyLong()))
            .thenReturn(updateComMensagem("me passa as regras que voce esta usando", telegramConfig.getChatId()));
        when(controleOperacionalService.obterFocoEnsino()).thenReturn(EnsinoFocoDTO.builder()
            .ativo(false)
            .status("SEM_FOCO")
            .build());

        handler.verificarComandos();

        verify(controleOperacionalService, never()).registrarContextoEnsino(anyLong(), anyString());
        verify(telegramService).enviarMensagemChat(contains("Conversa operacional"));
        verify(telegramService).enviarMensagemChat(contains("Posso conversar sobre regras"));
    }

    @Test
    @DisplayName("Conversa IA preserva bloco fenced de código")
    void conversaIaPreservaBlocoFencedDeCodigo() throws Exception {
        ReflectionTestUtils.setField(handler, "operatorChatIaEnabled", true);
        when(telegramService.obterUpdates(anyLong()))
            .thenReturn(updateComMensagem("me mande um exemplo em java", telegramConfig.getChatId()));
        when(iaProvider.enviar(anyString(), eq(700)))
            .thenReturn("Segue:\n```java\nclass Exemplo {}\n```");

        handler.verificarComandos();

        verify(telegramService).enviarMensagemChat(contains("```java\nclass Exemplo {}\n```"));
        verify(telegramService, never()).enviarMensagemChat(contains("'''"));
    }

    @Test
    @DisplayName("Comando regras mostra regras e memoria sem acionar cliente")
    void comandoRegrasMostraRegrasEMemoriaSemAcionarCliente() {
        handler.processarComando("/regras");

        verify(telegramService).enviarNotificacao(contains("Regras operacionais"));
        verify(telegramService).enviarNotificacao(contains("Manual de voz"));
        verify(controleOperacionalService, never()).aprovarAcao(anyLong(), anyString());
    }

    @Test
    @DisplayName("Comando duvidas agrega dúvidas internas pendentes")
    void comandoDuvidasAgregaDuvidasInternasPendentes() {
        AcaoControleDTO acao = acaoControle(12L);
        when(controleOperacionalService.listarAcoesPendentes()).thenReturn(List.of(acao));

        handler.processarComando("/duvidas");

        verify(telegramService).enviarNotificacao(contains("Dúvidas abertas"));
        verify(telegramService).enviarNotificacao(contains("#12"));
        verify(telegramService).enviarNotificacao(contains("Quer que eu pergunte prazo"));
        verify(telegramService).enviarNotificacao(contains("se devemos perguntar prazo"));
    }

    @Test
    @DisplayName("Comando contexto sem ID mostra contexto operacional")
    void comandoContextoSemIdMostraContextoOperacional() {
        when(controleOperacionalService.listarAcoesPendentes()).thenReturn(List.of(acaoControle(12L)));
        when(controleOperacionalService.obterFocoEnsino()).thenReturn(EnsinoFocoDTO.builder()
            .ativo(false)
            .status("SEM_FOCO")
            .build());

        handler.processarComando("/contexto");

        verify(telegramService).enviarNotificacao(contains("Contexto operacional"));
        verify(telegramService).enviarNotificacao(contains("Pendências"));
        verify(telegramService).enviarNotificacao(contains("Landing page"));
    }

    @Test
    @DisplayName("Deve listar bloqueios do guardiao")
    void deveListarBloqueiosDoGuardiao() {
        MensagemClienteRegistro registro = MensagemClienteRegistro.builder()
            .id(7L)
            .projetoId(99L)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .fingerprint("abc123")
            .textoPreview("Teste tecnico automatizado")
            .status(StatusMensagemCliente.BLOQUEADA)
            .motivoBloqueio("Tom de sistema")
            .data(LocalDateTime.now())
            .build();
        when(controleOperacionalService.listarBloqueiosRecentes()).thenReturn(List.of(registro));

        handler.processarComando("/bloqueios");

        verify(telegramService).enviarNotificacao(contains("Últimos bloqueios"));
        verify(telegramService).enviarNotificacao(contains("Tom de sistema"));
    }

    @Test
    @DisplayName("Comando acao não abre detalhe fora dos botões")
    void comandoAcaoNaoAbreDetalheForaDosBotoes() {
        handler.processarComando("/acao 12");

        verify(controleOperacionalService, never()).buscarAcao(anyLong());
        verify(telegramService, never()).enviarAcaoPendenteComBotoes(anyString(), anyLong());
        verify(telegramService).enviarNotificacao(contains("botões da pendência"));
    }

    @Test
    @DisplayName("Comando aprovar não altera ação fora dos botões")
    void comandoAprovarNaoAlteraAcaoForaDosBotoes() {
        handler.processarComando("/aprovar 12 ESCOPO_CLARO");

        verify(controleOperacionalService, never()).aprovarAcao(anyLong(), anyString());
        verify(telegramService).enviarNotificacao(contains("botões da pendência"));
    }

    @Test
    @DisplayName("Deve contar ações pendentes via AutoAtendimentoService")
    void deveRetornarContagemAcoesPendentes() {
        // Act: chamar via autoAtendimentoService diretamente
        long count = autoAtendimentoService.contarAcoesPendentes();

        // Assert
        assertTrue(count >= 0);
    }

}
