package com.freelasatende.repository;

import com.freelasatende.model.entity.Configuracao;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Repositório para Configuracao.
 */
@Repository
public interface ConfiguracaoRepository extends JpaRepository<Configuracao, Long> {

    Optional<Configuracao> findFirstByOrderByIdAsc();

    /** Contador atual direto do banco (nao da entidade gerenciada, que os updates em lote nao atualizam). */
    @Query("select coalesce(c.conexoesUsadasHoje, 0) from Configuracao c where c.id = :id")
    Optional<Integer> conexoesUsadasHojeAtual(@Param("id") Long id);

    /**
     * Incremento atomico do contador diario: executa no banco em uma unica
     * instrucao, sem leitura-modificacao-escrita, para nao perder incrementos
     * sob concorrencia. Retorna a quantidade de linhas alteradas.
     */
    @Transactional
    @Modifying
    @Query("update Configuracao c set c.conexoesUsadasHoje = coalesce(c.conexoesUsadasHoje, 0) + 1 where c.id = :id")
    int incrementarConexoesUsadasHoje(@Param("id") Long id);

    /**
     * Reserva atomica e condicional de uma conexao (ACHADO-FA-CONC-01-2), com o reset
     * do dia na MESMA instrucao: se o ultimo reset e de antes de {@code inicioDoDia}, o
     * contador recomeca em 1; senao so incrementa abaixo do limite. Separados, um reset
     * atrasado de outra thread na virada do dia zerava uma reserva ja feita.
     * Retorna 0 quando o limite foi alcancado ou a configuracao nao existe mais.
     */
    @Transactional
    @Modifying
    @Query("update Configuracao c set "
        + "c.conexoesUsadasHoje = case when c.dataUltimoReset is null or c.dataUltimoReset < :inicioDoDia "
        + "then 1 else coalesce(c.conexoesUsadasHoje, 0) + 1 end, "
        + "c.dataUltimoReset = case when c.dataUltimoReset is null or c.dataUltimoReset < :inicioDoDia "
        + "then :agora else c.dataUltimoReset end "
        + "where c.id = :id and (c.dataUltimoReset is null or c.dataUltimoReset < :inicioDoDia "
        + "or coalesce(c.conexoesUsadasHoje, 0) < :limite)")
    int reservarConexaoAtomica(
        @Param("id") Long id,
        @Param("limite") int limite,
        @Param("agora") LocalDateTime agora,
        @Param("inicioDoDia") LocalDateTime inicioDoDia
    );

    /**
     * Devolve uma conexao reservada cujo envio falhou depois da reserva.
     * Decremento atomico que nunca deixa o contador negativo e so vale se nao
     * houve reset diario depois da reserva (senao descontaria do dia novo).
     */
    @Transactional
    @Modifying
    @Query("update Configuracao c set c.conexoesUsadasHoje = case when coalesce(c.conexoesUsadasHoje, 0) > 0 "
        + "then c.conexoesUsadasHoje - 1 else 0 end where c.id = :id "
        + "and (c.dataUltimoReset is null or c.dataUltimoReset <= :reservadaEm)")
    int devolverConexao(@Param("id") Long id, @Param("reservadaEm") LocalDateTime reservadaEm);

    /**
     * Reset diario condicional: so zera se o ultimo reset for anterior ao
     * inicio do dia. Duas threads na virada do dia nao zeram reservas ja
     * feitas hoje: so a primeira altera a linha.
     */
    @Transactional
    @Modifying
    @Query("update Configuracao c set c.conexoesUsadasHoje = 0, c.dataUltimoReset = :agora "
        + "where c.id = :id and (c.dataUltimoReset is null or c.dataUltimoReset < :inicioDoDia)")
    int resetarContadorDiarioSeVencido(
        @Param("id") Long id,
        @Param("agora") LocalDateTime agora,
        @Param("inicioDoDia") LocalDateTime inicioDoDia
    );

    /** Grava so a data de renovacao, sem regravar o contador (save da entidade inteira perdia reservas). */
    @Transactional
    @Modifying
    @Query("update Configuracao c set c.dataUltimaRenovacao = :agora where c.id = :id")
    int marcarSessaoRenovada(@Param("id") Long id, @Param("agora") LocalDateTime agora);
}
