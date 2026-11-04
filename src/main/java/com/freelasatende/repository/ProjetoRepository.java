package com.freelasatende.repository;

import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusProjeto;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository para operações com projetos.
 */
@Repository
public interface ProjetoRepository extends JpaRepository<Projeto, Long> {

    Optional<Projeto> findByPlataformaAndIdExterno(PlataformaFreela plataforma, Long idExterno);

    /**
     * Reivindica um projeto ainda nao gravado com um INSERT minimo: entre instancias, a
     * segunda esbarra na chave primaria ou no indice unico (plataforma, id_externo). O save
     * da entidade nao serve aqui porque, com id atribuido, vira merge e atualiza em silencio.
     */
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = "insert into projetos "
        + "(id, plataforma, id_externo, titulo, status, foi_processado, data_processamento) "
        + "values (:id, :plataforma, :idExterno, :titulo, :status, false, :agora)")
    int inserirReivindicacao(
        @Param("id") Long id,
        @Param("plataforma") String plataforma,
        @Param("idExterno") Long idExterno,
        @Param("titulo") String titulo,
        @Param("status") String status,
        @Param("agora") LocalDateTime agora
    );

    /**
     * Reivindica um projeto ja gravado e ainda nao concluido: vence se nao houver
     * reivindicacao ativa (data de processamento vazia ou mais antiga que
     * {@code expiraAntesDe}). Projeto concluido (foiProcessado) nunca e retomado aqui.
     * Marca foiProcessado=false (em andamento) na mesma instrucao: uma segunda instancia
     * que leia depois encontra a reivindicacao ativa e recebe 0.
     */
    @Transactional
    @Modifying
    @Query("update Projeto p set p.dataProcessamento = :agora, p.foiProcessado = false where p.id = :id "
        + "and (p.foiProcessado is null or p.foiProcessado = false) "
        + "and (p.dataProcessamento is null or p.dataProcessamento < :expiraAntesDe)")
    int reivindicarProcessamento(
        @Param("id") Long id,
        @Param("expiraAntesDe") LocalDateTime expiraAntesDe,
        @Param("agora") LocalDateTime agora
    );

    /**
     * Confirma e renova a reivindicacao antes de um efeito: so altera a linha se a data de
     * processamento ainda for o token desta execucao (e o projeto nao foi concluido). 0
     * linhas = outra instancia tomou o projeto (prazo vencido) e esta execucao deve parar.
     */
    @Transactional
    @Modifying
    @Query("update Projeto p set p.dataProcessamento = :novo where p.id = :id and p.dataProcessamento = :token "
        + "and (p.foiProcessado is null or p.foiProcessado = false)")
    int renovarReivindicacao(
        @Param("id") Long id,
        @Param("token") LocalDateTime token,
        @Param("novo") LocalDateTime novo
    );

    /**
     * Fencing: confirma, sem mudar nada, que a data de processamento ainda e o token desta
     * execucao. Na transacao de uma gravacao, o UPDATE trava a linha ate o commit, entao uma
     * reivindicacao concorrente espera e nao intercala. 0 linhas = reserva perdida.
     */
    @Transactional
    @Modifying
    @Query("update Projeto p set p.dataProcessamento = :token where p.id = :id and p.dataProcessamento = :token")
    int confirmarDonoDaReivindicacao(@Param("id") Long id, @Param("token") LocalDateTime token);

    /**
     * Reprocessamento explicito do operador: tambem retoma projeto concluido (ex.: ignorado),
     * mas nunca uma reivindicacao ativa de outra instancia (nao concluido e recente).
     */
    @Transactional
    @Modifying
    @Query("update Projeto p set p.dataProcessamento = :agora, p.foiProcessado = false where p.id = :id "
        + "and (p.foiProcessado = true or p.dataProcessamento is null or p.dataProcessamento < :expiraAntesDe)")
    int reivindicarReprocessamentoExplicito(
        @Param("id") Long id,
        @Param("expiraAntesDe") LocalDateTime expiraAntesDe,
        @Param("agora") LocalDateTime agora
    );

    List<Projeto> findByStatus(StatusProjeto status);

    List<Projeto> findByFoiProcessadoFalseOrderByDataCriacaoDesc();

    List<Projeto> findByClienteId(Long clienteId);

    List<Projeto> findByDataProcessamentoAfterOrderByDataProcessamentoDesc(
        LocalDateTime data, Pageable pageable);

    List<Projeto> findByStatusAndDataProcessamentoAfterOrderByDataProcessamentoDesc(
        StatusProjeto status, LocalDateTime data, Pageable pageable);

    long countByFoiProcessadoTrueAndDataProcessamentoAfter(LocalDateTime data);

    long countByStatusAndDataProcessamentoAfter(StatusProjeto status, LocalDateTime data);
}
