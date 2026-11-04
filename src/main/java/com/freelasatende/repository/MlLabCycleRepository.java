package com.freelasatende.repository;

import com.freelasatende.model.entity.MlLabCycle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MlLabCycleRepository extends JpaRepository<MlLabCycle, Long> {

    List<MlLabCycle> findTop10ByRunIdOrderByNumeroDesc(Long runId);

    long countByRunId(Long runId);
}
