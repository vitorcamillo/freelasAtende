package com.freelasatende.repository;

import com.freelasatende.model.entity.MlLabAgent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MlLabAgentRepository extends JpaRepository<MlLabAgent, Long> {

    List<MlLabAgent> findByRunIdOrderByScoreTotalDesc(Long runId);

    Optional<MlLabAgent> findByRunIdAndAgentKey(Long runId, String agentKey);

    long countByRunId(Long runId);

    /**
     * Grava so os campos do chat: salvar a entidade inteira sobrescreveria
     * score, regras e memoria que um ciclo concorrente acabou de atualizar.
     */
    @Transactional
    @Modifying
    @Query("update MlLabAgent a set a.ultimaResposta = :resposta, a.dataAtualizacao = :agora where a.id = :id")
    int registrarRespostaChat(@Param("id") Long id, @Param("resposta") String resposta, @Param("agora") LocalDateTime agora);
}
