package com.freelasatende.repository;

import com.freelasatende.model.entity.InboxConversaAuditoriaRegistro;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InboxConversaAuditoriaRegistroRepository extends JpaRepository<InboxConversaAuditoriaRegistro, Long> {

    List<InboxConversaAuditoriaRegistro> findByAuditoriaIdOrderByIdAsc(Long auditoriaId);
}
