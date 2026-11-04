package com.freelasatende.repository;

import com.freelasatende.model.entity.InboxAuditoriaRegistro;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InboxAuditoriaRegistroRepository extends JpaRepository<InboxAuditoriaRegistro, Long> {

    Optional<InboxAuditoriaRegistro> findFirstByOrderByDataDesc();
}
