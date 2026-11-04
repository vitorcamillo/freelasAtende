package com.freelasatende.repository;

import com.freelasatende.model.entity.Cliente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository para operações com clientes.
 */
@Repository
public interface ClienteRepository extends JpaRepository<Cliente, Long> {
}
