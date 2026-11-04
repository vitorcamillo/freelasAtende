package com.freelasatende.repository;

import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.TipoInteracao;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Repositorio para Interacao.
 */
@Repository
public interface InteracaoRepository extends JpaRepository<Interacao, Long> {

    List<Interacao> findByProjetoIdOrderByDataDesc(Long projetoId);

    List<Interacao> findByPlataformaAndProjetoIdOrderByDataDesc(PlataformaFreela plataforma, Long projetoId);

    List<Interacao> findTop100ByOrderByDataDesc();

    List<Interacao> findAllByOrderByDataDesc(Pageable pageable);

    List<Interacao> findByTipoOrderByDataDesc(TipoInteracao tipo);

    List<Interacao> findByDataBetweenOrderByDataDesc(LocalDateTime inicio, LocalDateTime fim);

    List<Interacao> findByTipoAndDataBefore(TipoInteracao tipo, LocalDateTime data);

    int countByDataBetween(LocalDateTime inicio, LocalDateTime fim);

    boolean existsByProjetoId(Long projetoId);

    boolean existsByPlataformaAndProjetoId(PlataformaFreela plataforma, Long projetoId);

    boolean existsByProjetoIdAndTipoNotIn(Long projetoId, Collection<TipoInteracao> tipos);

    boolean existsByPlataformaAndProjetoIdAndTipoNotIn(
        PlataformaFreela plataforma,
        Long projetoId,
        Collection<TipoInteracao> tipos
    );

    /**
     * Seleciona o ultimo erro de cada projeto elegivel.
     *
     * <p>A agregacao por projeto evita executar COUNT e MAX novamente para cada
     * linha candidata. A consulta e nativa porque o derived table permite ao
     * MySQL usar o indice (projeto_id, tipo, data) em uma unica leitura por
     * grupo, sem alterar o resultado (inclusive duplicatas com a mesma data).</p>
     *
     * <p>Os tipos entram como texto ({@code EnumType.STRING} na coluna): em consulta
     * nativa o Hibernate 6 envia enum como ordinal, e {@code tipo = 18} nunca casa
     * com {@code 'ERRO_SISTEMA'} — o reprocessamento pararia em silencio.</p>
     */
    @Query(value = """
        select i.*
        from interacoes i
        join (
            select projeto_id, count(*) as quantidade_erros, max(data) as ultima_data
            from interacoes
            where tipo = :tipoErro
            group by projeto_id
        ) erros on erros.projeto_id = i.projeto_id
        where i.tipo = :tipoErro
          and i.data >= :desde
          and erros.quantidade_erros < :maxTentativas
          and i.data = erros.ultima_data
          and not exists (
              select 1
              from interacoes t
              where t.projeto_id = i.projeto_id
                and t.tipo not in (:tiposNaoConclusivos)
          )
        order by i.data asc
        """, nativeQuery = true)
    List<Interacao> findErrosTecnicosSemTratamentoPorNome(
        String tipoErro,
        LocalDateTime desde,
        long maxTentativas,
        Collection<String> tiposNaoConclusivos,
        Pageable pageable
    );

    default List<Interacao> findErrosTecnicosSemTratamento(
        TipoInteracao tipoErro,
        LocalDateTime desde,
        long maxTentativas,
        Collection<TipoInteracao> tiposNaoConclusivos,
        Pageable pageable
    ) {
        return findErrosTecnicosSemTratamentoPorNome(
            tipoErro.name(),
            desde,
            maxTentativas,
            tiposNaoConclusivos.stream().map(Enum::name).toList(),
            pageable
        );
    }

    boolean existsByTipoAndConteudoContaining(TipoInteracao tipo, String conteudo);
}
