package com.freelasatende.repository;

import com.freelasatende.model.entity.Aprendizado;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository para operações com aprendizados.
 */
@Repository
public interface AprendizadoRepository extends JpaRepository<Aprendizado, Long> {

    List<Aprendizado> findByProjetoIdOrderByDataDesc(Long projetoId);

    List<Aprendizado> findByFoiAprovadoTrueOrderByDataDesc();

    List<Aprendizado> findTop20ByStatusRegraOrderByDataDesc(com.freelasatende.model.enums.StatusRegraAprendida statusRegra);

    long countByStatusRegra(com.freelasatende.model.enums.StatusRegraAprendida statusRegra);
}
