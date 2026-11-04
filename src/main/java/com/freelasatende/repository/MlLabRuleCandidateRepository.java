package com.freelasatende.repository;

import com.freelasatende.model.entity.MlLabRuleCandidate;
import com.freelasatende.model.enums.MlLabCandidateStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MlLabRuleCandidateRepository extends JpaRepository<MlLabRuleCandidate, Long> {

    List<MlLabRuleCandidate> findTop20ByRunIdOrderByScoreDesc(Long runId);

    List<MlLabRuleCandidate> findByRunIdAndStatusOrderByScoreDesc(Long runId, MlLabCandidateStatus status);

    /**
     * Aprovacao atomica: so muda a candidata que ainda nao estava APROVADA.
     * Retorna 0 quando outra aprovacao concorrente ja venceu a corrida. Limpa o contexto
     * JPA depois do UPDATE: o overview devolvido rele a candidata com o status do banco.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update MlLabRuleCandidate c set c.status = com.freelasatende.model.enums.MlLabCandidateStatus.APROVADA, "
        + "c.dataDecisao = :agora where c.id = :id "
        + "and c.status <> com.freelasatende.model.enums.MlLabCandidateStatus.APROVADA")
    int aprovarSeAindaNaoAprovada(@Param("id") Long id, @Param("agora") LocalDateTime agora);
}
