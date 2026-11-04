package com.freelasatende.repository;

import com.freelasatende.model.entity.MlLabRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MlLabRunRepository extends JpaRepository<MlLabRun, Long> {

    List<MlLabRun> findTop20ByOrderByDataCriacaoDesc();
}
