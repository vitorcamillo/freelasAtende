package com.freelasatende.repository;

import com.freelasatende.model.entity.MensagemClienteRegistro;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusMensagemCliente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MensagemClienteRegistroRepository extends JpaRepository<MensagemClienteRegistro, Long> {

    List<MensagemClienteRegistro> findTop50ByOrderByDataDesc();

    List<MensagemClienteRegistro> findTop20ByStatusOrderByDataDesc(StatusMensagemCliente status);

    long countByStatus(StatusMensagemCliente status);

    boolean existsByFingerprintAndStatusIn(String fingerprint, Collection<StatusMensagemCliente> statuses);

    boolean existsByPlataformaAndFingerprintAndStatusIn(
        PlataformaFreela plataforma,
        String fingerprint,
        Collection<StatusMensagemCliente> statuses
    );

    boolean existsByFingerprintAndProjetoIdAndStatusIn(
        String fingerprint,
        Long projetoId,
        Collection<StatusMensagemCliente> statuses
    );

    boolean existsByPlataformaAndFingerprintAndProjetoIdAndStatusIn(
        PlataformaFreela plataforma,
        String fingerprint,
        Long projetoId,
        Collection<StatusMensagemCliente> statuses
    );

    boolean existsByFingerprintAndConversaIdAndStatusIn(
        String fingerprint,
        Long conversaId,
        Collection<StatusMensagemCliente> statuses
    );

    boolean existsByPlataformaAndFingerprintAndConversaIdAndStatusIn(
        PlataformaFreela plataforma,
        String fingerprint,
        Long conversaId,
        Collection<StatusMensagemCliente> statuses
    );

    boolean existsByProjetoIdAndStatusIn(Long projetoId, Collection<StatusMensagemCliente> statuses);

    boolean existsByPlataformaAndProjetoIdAndStatusIn(
        PlataformaFreela plataforma,
        Long projetoId,
        Collection<StatusMensagemCliente> statuses
    );

    boolean existsByConversaIdAndStatusIn(Long conversaId, Collection<StatusMensagemCliente> statuses);

    boolean existsByPlataformaAndConversaIdAndStatusIn(
        PlataformaFreela plataforma,
        Long conversaId,
        Collection<StatusMensagemCliente> statuses
    );

    Optional<MensagemClienteRegistro> findFirstByProjetoIdAndStatusInOrderByDataDesc(
        Long projetoId,
        Collection<StatusMensagemCliente> statuses
    );

    Optional<MensagemClienteRegistro> findFirstByPlataformaAndProjetoIdAndStatusInOrderByDataDesc(
        PlataformaFreela plataforma,
        Long projetoId,
        Collection<StatusMensagemCliente> statuses
    );

    Optional<MensagemClienteRegistro> findFirstByConversaIdAndStatusInOrderByDataDesc(
        Long conversaId,
        Collection<StatusMensagemCliente> statuses
    );

    Optional<MensagemClienteRegistro> findFirstByPlataformaAndConversaIdAndStatusInOrderByDataDesc(
        PlataformaFreela plataforma,
        Long conversaId,
        Collection<StatusMensagemCliente> statuses
    );

    List<MensagemClienteRegistro> findByProjetoIdAndStatus(Long projetoId, StatusMensagemCliente status);

    List<MensagemClienteRegistro> findByPlataformaAndProjetoIdAndStatus(
        PlataformaFreela plataforma,
        Long projetoId,
        StatusMensagemCliente status
    );

    List<MensagemClienteRegistro> findByConversaIdAndStatus(Long conversaId, StatusMensagemCliente status);

    List<MensagemClienteRegistro> findByPlataformaAndConversaIdAndStatus(
        PlataformaFreela plataforma,
        Long conversaId,
        StatusMensagemCliente status
    );
}
