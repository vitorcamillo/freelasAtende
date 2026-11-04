package com.freelasatende.repository;

import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.enums.TipoInteracao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Semantica da consulta de erros tecnicos pendentes, executada no banco.
 *
 * <p>Em 25/09/2026 a versao com subconsultas correlacionadas examinou 26 bilhoes
 * de linhas e saturou o disco do servidor; a reescrita precisa devolver
 * exatamente os mesmos projetos.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
class InteracaoRepositoryErrosTecnicosTest {

    private static final EnumSet<TipoInteracao> NAO_CONCLUSIVOS =
        EnumSet.of(TipoInteracao.ERRO_SISTEMA, TipoInteracao.MENSAGEM_ERRO);

    @Autowired
    private InteracaoRepository repository;

    private final LocalDateTime agora = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    private Interacao salvar(long projeto, TipoInteracao tipo, LocalDateTime data) {
        return repository.save(Interacao.builder()
            .projetoId(projeto).projetoTitulo("p" + projeto)
            .tipo(tipo).conteudo("x").data(data).build());
    }

    @Test
    @DisplayName("devolve so o ultimo erro de projetos elegiveis, na ordem da data")
    void devolveUltimoErroDosProjetosElegiveis() {
        salvar(1, TipoInteracao.ERRO_SISTEMA, agora.minusHours(3));
        Interacao ultimoDo1 = salvar(1, TipoInteracao.ERRO_SISTEMA, agora.minusHours(1));
        salvar(2, TipoInteracao.ERRO_SISTEMA, agora.minusHours(2));
        salvar(2, TipoInteracao.PROPOSTA_ENVIADA, agora.minusHours(2));
        salvar(3, TipoInteracao.ERRO_SISTEMA, agora.minusHours(4));
        salvar(3, TipoInteracao.ERRO_SISTEMA, agora.minusHours(3));
        salvar(3, TipoInteracao.ERRO_SISTEMA, agora.minusHours(2));
        salvar(4, TipoInteracao.ERRO_SISTEMA, agora.minusDays(2));
        Interacao erroDo5 = salvar(5, TipoInteracao.ERRO_SISTEMA, agora.minusMinutes(30));
        salvar(5, TipoInteracao.MENSAGEM_ERRO, agora.minusMinutes(20));

        List<Interacao> pendentes = repository.findErrosTecnicosSemTratamento(
            TipoInteracao.ERRO_SISTEMA, agora.minusDays(1), 3, NAO_CONCLUSIVOS,
            PageRequest.of(0, 10));

        assertEquals(List.of(ultimoDo1.getId(), erroDo5.getId()),
            pendentes.stream().map(Interacao::getId).toList(),
            "1: ultimo erro; 2: tem interacao conclusiva; 3: esgotou 3 tentativas; "
                + "4: fora da janela; 5: erro seguido de mensagem de erro segue elegivel");
    }

    @Test
    @DisplayName("respeita o limite da pagina")
    void respeitaLimiteDaPagina() {
        salvar(1, TipoInteracao.ERRO_SISTEMA, agora.minusHours(2));
        salvar(5, TipoInteracao.ERRO_SISTEMA, agora.minusHours(1));

        assertEquals(1, repository.findErrosTecnicosSemTratamento(
            TipoInteracao.ERRO_SISTEMA, agora.minusDays(1), 3, NAO_CONCLUSIVOS,
            PageRequest.of(0, 1)).size());
    }
}
