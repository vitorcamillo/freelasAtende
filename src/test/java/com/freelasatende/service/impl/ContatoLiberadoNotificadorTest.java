package com.freelasatende.service.impl;

import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.repository.InteracaoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O aviso de contato liberado chega como mensagem de sistema, que o fluxo de
 * resposta descarta. Estes testes fixam que ele vira alerta, uma unica vez.
 */
class ContatoLiberadoNotificadorTest {

    private static final String AVISO = "O empregador nao respondeu ao seu interesse. Portanto "
        + "divulgamos agora os dados de contato do empregador. Se desejar, voce pode conta-los "
        + "diretamente: Nome do cliente: Pedro Henrique E-mail: pedro@empresa.com "
        + "Telefone: (11) 98765-4321";

    private InteracaoRepository interacaoRepository;
    private AutoAtendimentoConversaHandler.Hooks hooks;
    private ConversaResumo conversa;
    private Projeto projeto;

    @BeforeEach
    void setup() {
        interacaoRepository = mock(InteracaoRepository.class);
        hooks = mock(AutoAtendimentoConversaHandler.Hooks.class);
        conversa = ConversaResumo.builder()
            .plataforma(PlataformaFreela.FREELANCER_BR)
            .idConversa(77L)
            .nomeProjeto("Banco de Dados")
            .nomeCliente("Pedro Henrique")
            .build();
        projeto = Projeto.builder()
            .id(12L)
            .plataforma(PlataformaFreela.FREELANCER_BR)
            .titulo("Banco de Dados")
            .build();
    }

    private MensagemConversa mensagem(long id, String texto, boolean sistema, boolean minha) {
        return MensagemConversa.builder()
            .plataforma(PlataformaFreela.FREELANCER_BR)
            .id(id)
            .idConversa(77L)
            .texto(texto)
            .sistema(sistema)
            .enviadaPorMim(minha)
            .excluida(false)
            .build();
    }

    @Test
    @DisplayName("mensagem de sistema com contato liberado vira alerta no Telegram")
    void avisaContatoLiberado() {
        when(interacaoRepository.existsByTipoAndConteudoContaining(any(), anyString())).thenReturn(false);

        int alertados = ContatoLiberadoNotificador.notificar(
            conversa, projeto, List.of(mensagem(5L, AVISO, true, false)),
            ModoSistema.LIVRE, interacaoRepository, hooks);

        assertEquals(1, alertados);
        ArgumentCaptor<String> alerta = ArgumentCaptor.forClass(String.class);
        verify(hooks).alertarContatoLiberado(alerta.capture());
        assertTrue(alerta.getValue().contains("Consegui os contatos do cliente"));
        assertTrue(alerta.getValue().contains("pedro@empresa.com"));
        assertTrue(alerta.getValue().contains("(11) 98765-4321"));
        assertTrue(alerta.getValue().contains("Banco de Dados"));
        verify(hooks).registrarInteracao(
            eq(PlataformaFreela.FREELANCER_BR), eq(12L), anyString(),
            eq(TipoInteracao.MENSAGEM_RECEBIDA), anyString(), eq(ModoSistema.LIVRE));
    }

    @Test
    @DisplayName("nao repete alerta de mensagem ja registrada")
    void naoRepeteAlerta() {
        when(interacaoRepository.existsByTipoAndConteudoContaining(any(), anyString())).thenReturn(true);

        int alertados = ContatoLiberadoNotificador.notificar(
            conversa, projeto, List.of(mensagem(5L, AVISO, true, false)),
            ModoSistema.LIVRE, interacaoRepository, hooks);

        assertEquals(0, alertados);
        verify(hooks, never()).alertarContatoLiberado(anyString());
    }

    @Test
    @DisplayName("conversa comum nao gera alerta")
    void conversaComumNaoAlerta() {
        when(interacaoRepository.existsByTipoAndConteudoContaining(any(), anyString())).thenReturn(false);

        int alertados = ContatoLiberadoNotificador.notificar(
            conversa, projeto,
            List.of(mensagem(6L, "Ola, ainda esta em aberto?", false, false),
                    mensagem(7L, "Obrigado pelo contato.", false, true)),
            ModoSistema.LIVRE, interacaoRepository, hooks);

        assertEquals(0, alertados);
        verify(hooks, never()).alertarContatoLiberado(anyString());
    }

    @Test
    @DisplayName("a chave de idempotencia distingue plataforma, conversa e mensagem")
    void chaveEhEstavel() {
        String a = ContatoLiberadoNotificador.chave(PlataformaFreela.FREELANCER_BR, 77L, 5L);
        assertEquals("contato-liberado|plataforma:" + PlataformaFreela.FREELANCER_BR.key() + "|conversa:77|mensagem:5", a);
        assertTrue(!a.equals(ContatoLiberadoNotificador.chave(PlataformaFreela.FREELANCER, 77L, 5L)));
    }
}

