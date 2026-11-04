package com.freelasatende.service.impl;

import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.repository.InteracaoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoricoServiceImplTest {

    @Test
    @DisplayName("Registro de interação corta conteúdo maior que limite do banco")
    void registrarInteracaoCortaConteudoMaiorQueLimiteDoBanco() {
        InteracaoRepository repository = mock(InteracaoRepository.class);
        HistoricoServiceImpl service = new HistoricoServiceImpl(repository);

        service.registrarInteracao(Interacao.builder()
            .projetoId(1L)
            .projetoTitulo("Projeto")
            .tipo(TipoInteracao.MENSAGEM_RECEBIDA)
            .conteudo("x".repeat(6000))
            .build());

        ArgumentCaptor<Interacao> captor = ArgumentCaptor.forClass(Interacao.class);
        verify(repository).save(captor.capture());
        assertTrue(captor.getValue().getConteudo().length() <= 5000);
        assertTrue(captor.getValue().getConteudo().contains("conteudo cortado"));
    }

    @Test
    @DisplayName("Erro tecnico isolado nao marca projeto como processado")
    void projetoJaProcessadoIgnoraErroTecnicoIsolado() {
        InteracaoRepository repository = mock(InteracaoRepository.class);
        HistoricoServiceImpl service = new HistoricoServiceImpl(repository);
        Long projetoId = 756266L;

        when(repository.existsByProjetoIdAndTipoNotIn(eq(projetoId), any(Collection.class)))
            .thenReturn(false);

        assertFalse(service.projetoJaProcessado(projetoId));
    }

    @Test
    @DisplayName("Interacao conclusiva marca projeto como processado")
    void projetoJaProcessadoConsideraInteracaoConclusiva() {
        InteracaoRepository repository = mock(InteracaoRepository.class);
        HistoricoServiceImpl service = new HistoricoServiceImpl(repository);
        Long projetoId = 756384L;

        when(repository.existsByProjetoIdAndTipoNotIn(eq(projetoId), any(Collection.class)))
            .thenReturn(true);

        assertTrue(service.projetoJaProcessado(projetoId));
    }

    @Test
    @DisplayName("Consulta de processamento usa plataforma quando informada")
    void projetoJaProcessadoConsideraPlataforma() {
        InteracaoRepository repository = mock(InteracaoRepository.class);
        HistoricoServiceImpl service = new HistoricoServiceImpl(repository);
        Long projetoId = PlataformaFreela.TESTE.idInterno(756384L);

        when(repository.existsByPlataformaAndProjetoIdAndTipoNotIn(
            eq(PlataformaFreela.TESTE),
            eq(projetoId),
            any(Collection.class)
        )).thenReturn(true);

        assertTrue(service.projetoJaProcessado(PlataformaFreela.TESTE, projetoId));
    }
}
