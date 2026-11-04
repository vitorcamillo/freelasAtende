package com.freelasatende.service;

import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.StatusProjeto;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.model.enums.TipoInteracao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AutoAtendimentoServiceImplWorkanaTest extends AutoAtendimentoServiceImplTestSupport {

    @Test
    @DisplayName("Workana usa registry real e cria pendencia do core quando escrita esta desligada")
    void workanaUsaRegistryRealSemCairNoAdapter99Freelas() throws Exception {
        Projeto projeto = Projeto.builder()
            .id(PlataformaFreela.WORKANA.idInterno(12345L))
            .idExterno(12345L)
            .plataforma(PlataformaFreela.WORKANA)
            .titulo("Criar landing page em React")
            .descricao("Preciso criar uma landing page responsiva para campanha.")
            .urlProjeto("https://www.workana.com/job/criar-landing-page-em-react")
            .foiProcessado(false)
            .build();

        assertDoesNotThrow(() -> autoAtendimentoService.processarProjeto(projeto, ModoSistema.REGULAR));

        verify(scraperService, never()).buscarDetalhesProjeto(eq(12345L), anyString());
        verify(scraperService, never()).buscarCliente(eq(12345L));
        assertEquals(1, acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE));
        assertEquals(StatusProjeto.EM_ANALISE, projeto.getStatus());
        AcaoPendente pendente = acaoPendenteRepository.findAll().get(0);
        assertEquals(PlataformaFreela.WORKANA, pendente.getPlataforma());
        assertEquals(TipoAcaoPendente.ENVIAR_PERGUNTA, pendente.getTipo());
    }

    @Test
    @DisplayName("Workana com form de envio bloqueado nao gasta IA em novos projetos durante cooldown")
    void workanaComEnvioBloqueadoNaoGastaIaEmNovosProjetos() {
        habilitarEscritaRealMockada();
        acaoPendenteRepository.save(AcaoPendente.builder()
            .plataforma(PlataformaFreela.WORKANA)
            .projetoId(PlataformaFreela.WORKANA.idInterno(111L))
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.ERRO)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Projeto Workana bloqueado")
            .resumo("Abordagem inicial automatica | Ainda esta disponivel?")
            .observacaoUsuario("Envio automatico nao executado por falha de sessao/autorizacao do marketplace. "
                + "Nao criar pendencia de aprovacao para abordagem inicial automatica.")
            .dataCriacao(LocalDateTime.now().minusMinutes(5))
            .dataAtualizacao(LocalDateTime.now().minusMinutes(5))
            .build());

        Projeto projeto = Projeto.builder()
            .id(PlataformaFreela.WORKANA.idInterno(222L))
            .idExterno(222L)
            .plataforma(PlataformaFreela.WORKANA)
            .titulo("Novo projeto Workana")
            .descricao("Preciso criar uma landing page responsiva.")
            .urlProjeto("https://www.workana.com/job/novo-projeto-workana")
            .foiProcessado(false)
            .build();

        autoAtendimentoService.processarProjeto(projeto, ModoSistema.REGULAR);

        verify(avaliadorIaService, never()).avaliarProjeto(eq(projeto));
        verify(scraperService, never()).buscarCliente(eq(222L));
        assertEquals(1, acaoPendenteRepository.countByStatus(StatusAcaoPendente.ERRO));
        assertEquals(0, acaoPendenteRepository.countByStatus(StatusAcaoPendente.EXECUTADA));
    }

    @Test
    @DisplayName("Feedback de acao Workana registra historico na plataforma da acao")
    void feedbackWorkanaRegistraHistoricoNaPlataformaDaAcao() {
        Long projetoId = PlataformaFreela.WORKANA.idInterno(777L);
        AcaoPendente acao = acaoPendenteRepository.save(AcaoPendente.builder()
            .plataforma(PlataformaFreela.WORKANA)
            .projetoId(projetoId)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Projeto Workana")
            .payloadJson("{\"plataforma\":\"WORKANA\",\"projetoId\":" + projetoId + ",\"mensagem\":\"Ja foi atendido?\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build());

        autoAtendimentoService.processarFeedback(acao.getId(), false, "ROBOTICO");

        assertTrue(interacaoRepository.findAll().stream().anyMatch(interacao ->
            interacao.getPlataforma() == PlataformaFreela.WORKANA
                && interacao.getProjetoId().equals(projetoId)
                && interacao.getTipo() == TipoInteracao.REJEICAO_USUARIO
        ));
    }
}
