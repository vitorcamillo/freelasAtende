package com.freelasatende.repository;

import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AcaoPendenteRepository extends JpaRepository<AcaoPendente, Long> {

    List<AcaoPendente> findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente status);

    /**
     * Transicao de status atomica: so altera a acao se ela ainda estiver no
     * status esperado. Retorna a quantidade de linhas alteradas (0 significa que
     * outra thread/processo ja processou a acao).
     */
    @Transactional
    @Modifying
    @Query("update AcaoPendente a set a.status = :novoStatus, a.dataAtualizacao = :agora "
        + "where a.id = :acaoId and a.status = :statusAtual")
    int transicaoAtomicaDeStatus(
        @Param("acaoId") Long acaoId,
        @Param("statusAtual") StatusAcaoPendente statusAtual,
        @Param("novoStatus") StatusAcaoPendente novoStatus,
        @Param("agora") LocalDateTime agora
    );

    /**
     * Descarta a acao de uma execucao que perdeu a posse do projeto: UPDATE direto (fora do
     * fencing de gravacao), so enquanto ela ainda estiver PENDENTE ou APROVADA.
     */
    @Transactional
    @Modifying
    @Query("update AcaoPendente a set a.status = com.freelasatende.model.enums.StatusAcaoPendente.REJEITADA, "
        + "a.observacaoUsuario = :observacao, a.dataAtualizacao = :agora where a.id = :acaoId "
        + "and a.status in (com.freelasatende.model.enums.StatusAcaoPendente.PENDENTE, "
        + "com.freelasatende.model.enums.StatusAcaoPendente.APROVADA)")
    int descartarAcaoDeExecucaoSemPosse(
        @Param("acaoId") Long acaoId,
        @Param("observacao") String observacao,
        @Param("agora") LocalDateTime agora
    );

    long countByStatus(StatusAcaoPendente status);

    long countByProjetoIdAndStatus(Long projetoId, StatusAcaoPendente status);

    long countByPlataformaAndProjetoIdAndStatus(
        PlataformaFreela plataforma,
        Long projetoId,
        StatusAcaoPendente status
    );

    long countByPlataformaAndProjetoIdAndTipoAndStatusIn(
        PlataformaFreela plataforma,
        Long projetoId,
        TipoAcaoPendente tipo,
        Collection<StatusAcaoPendente> statuses
    );

    long countByConversaIdAndStatus(Long conversaId, StatusAcaoPendente status);

    String OUTRAS_ABERTAS = "select count(a) from AcaoPendente a where a.plataforma = :plataforma and a.id <> :id "
        + "and (a.status = com.freelasatende.model.enums.StatusAcaoPendente.PENDENTE "
        + "or (a.status = com.freelasatende.model.enums.StatusAcaoPendente.APROVADA "
        + "and a.dataAtualizacao >= :aprovadaDesde)) and ";

    /**
     * Outras acoes abertas do mesmo projeto, sem contar a propria: PENDENTE ou APROVADA
     * recente (em processamento; a aprovacao reivindica a acao antes da politica).
     */
    @Query(OUTRAS_ABERTAS + "a.projetoId = :projetoId")
    long contarOutrasAbertasDoProjeto(
        @Param("plataforma") PlataformaFreela plataforma,
        @Param("projetoId") Long projetoId,
        @Param("id") Long id,
        @Param("aprovadaDesde") LocalDateTime aprovadaDesde
    );

    /** Mesmo criterio de {@link #contarOutrasAbertasDoProjeto} por conversa. */
    @Query(OUTRAS_ABERTAS + "a.conversaId = :conversaId")
    long contarOutrasAbertasDaConversa(
        @Param("plataforma") PlataformaFreela plataforma,
        @Param("conversaId") Long conversaId,
        @Param("id") Long id,
        @Param("aprovadaDesde") LocalDateTime aprovadaDesde
    );

    long countByPlataformaAndConversaIdAndStatus(
        PlataformaFreela plataforma,
        Long conversaId,
        StatusAcaoPendente status
    );

    Optional<AcaoPendente> findFirstByProjetoIdAndStatusOrderByDataCriacaoDesc(Long projetoId, StatusAcaoPendente status);

    Optional<AcaoPendente> findFirstByPlataformaAndProjetoIdAndStatusOrderByDataCriacaoDesc(
        PlataformaFreela plataforma,
        Long projetoId,
        StatusAcaoPendente status
    );

    Optional<AcaoPendente> findFirstByPlataformaAndProjetoIdAndTipoAndStatusOrderByDataCriacaoDesc(
        PlataformaFreela plataforma,
        Long projetoId,
        TipoAcaoPendente tipo,
        StatusAcaoPendente status
    );

    Optional<AcaoPendente> findFirstByPlataformaAndTipoAndStatusOrderByDataAtualizacaoDesc(
        PlataformaFreela plataforma,
        TipoAcaoPendente tipo,
        StatusAcaoPendente status
    );

    List<AcaoPendente> findByPlataformaAndProjetoIdAndTipoAndStatusOrderByDataCriacaoDesc(
        PlataformaFreela plataforma,
        Long projetoId,
        TipoAcaoPendente tipo,
        StatusAcaoPendente status
    );

    Optional<AcaoPendente> findFirstByConversaIdAndTipoAndStatusOrderByDataCriacaoDesc(
        Long conversaId, TipoAcaoPendente tipo, StatusAcaoPendente status
    );

    Optional<AcaoPendente> findFirstByPlataformaAndConversaIdAndTipoAndStatusOrderByDataCriacaoDesc(
        PlataformaFreela plataforma,
        Long conversaId,
        TipoAcaoPendente tipo,
        StatusAcaoPendente status
    );
}
