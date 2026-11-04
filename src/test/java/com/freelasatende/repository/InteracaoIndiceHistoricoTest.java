package com.freelasatende.repository;

import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.enums.TipoInteracao;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifica o indice JPA e a integridade da leitura do historico em H2 real. */
@DataJpaTest
@ActiveProfiles("test")
class InteracaoIndiceHistoricoTest {

    @Autowired
    private InteracaoRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void schemaCriaIndiceDeDataEHistoricoMantemConteudoEOrdemDescendente() {
        Integer indices = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES
            WHERE UPPER(TABLE_NAME) = 'INTERACOES' AND UPPER(INDEX_NAME) = 'IDX_INTERACOES_DATA'
            """, Integer.class);
        assertEquals(1, indices, "o schema JPA deve criar o indice idx_interacoes_data em data");

        LocalDateTime maisAntiga = LocalDateTime.of(2026, 10, 20, 10, 0);
        LocalDateTime maisRecente = LocalDateTime.of(2026, 10, 23, 10, 0);
        repository.saveAll(List.of(
            Interacao.builder().projetoId(7L).tipo(TipoInteracao.MENSAGEM_RECEBIDA)
                .conteudo("registro antigo completo").data(maisAntiga).build(),
            Interacao.builder().projetoId(8L).tipo(TipoInteracao.PROPOSTA_ENVIADA)
                .conteudo("registro recente completo").data(maisRecente).build()));
        repository.flush();

        List<Interacao> historico = repository.findTop100ByOrderByDataDesc();
        assertEquals(List.of("registro recente completo", "registro antigo completo"),
            historico.stream().map(Interacao::getConteudo).toList());
        assertEquals(List.of(maisRecente, maisAntiga), historico.stream().map(Interacao::getData).toList());

        // H2 pode preferir table scan em uma tabela minúscula, mesmo com o índice presente.
        // EXPLAIN documenta o plano do H2; ele não mede a escolha de plano nem o desempenho do MySQL.
        String planoH2 = jdbcTemplate.queryForObject(
            "EXPLAIN SELECT * FROM interacoes ORDER BY data DESC", String.class);
        assertTrue(planoH2 != null && !planoH2.isBlank(), "H2 deve retornar o plano EXPLAIN");
    }
}
