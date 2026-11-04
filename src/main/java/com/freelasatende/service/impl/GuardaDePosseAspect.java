package com.freelasatende.service.impl;

import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.entity.Projeto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * Fencing por gravacao: um save de projeto, acao ou interacao de um projeto reservado pela
 * thread atual (ReservaDeProjeto) roda numa transacao curta que comeca com
 * UPDATE projetos ... WHERE id=? AND data_processamento=token. A linha fica travada so ate o
 * commit desse save (nenhuma chamada externa dentro). 0 linhas = posse perdida para outra
 * instancia: nada e gravado e sai ReivindicacaoPerdidaException; notificacoes que vem depois
 * da gravacao nao acontecem. Fora de reserva, o save segue normal.
 */
@Aspect
@Component
class GuardaDePosseAspect {

    // EntityManager (e nao ProjetoRepository): depender do repositorio criaria o bean antes do
    // auto-proxy e o save de projeto ficaria fora deste aspecto.
    @PersistenceContext
    private EntityManager entityManager;

    private final TransactionTemplate transacao;

    GuardaDePosseAspect(PlatformTransactionManager transactionManager) {
        this.transacao = new TransactionTemplate(transactionManager);
    }

    // target(...) e nao this(...): o proxy AOP de alguns repositorios Spring Data nao casava
    // com this(...) e o save de projeto/acao passava sem o fencing.
    @Around("execution(* save(..)) && ("
        + "target(com.freelasatende.repository.ProjetoRepository) "
        + "|| target(com.freelasatende.repository.AcaoPendenteRepository) "
        + "|| target(com.freelasatende.repository.InteracaoRepository))")
    Object gravarComPosse(ProceedingJoinPoint chamada) throws Throwable {
        Object entidade = chamada.getArgs().length > 0 ? chamada.getArgs()[0] : null;
        Long projetoId = projetoDa(entidade);
        LocalDateTime token = ReservaDeProjeto.tokenAtual(projetoId);
        if (token == null) {
            return chamada.proceed();
        }
        try {
            if (entidade instanceof Projeto projeto) {
                // Antes da confirmacao: a data de processamento gravada continua sendo o token
                // da reserva (o proximo save ainda reconhece a posse) e, se a entidade estiver
                // gerenciada, nenhuma data alterada pelo chamador chega ao banco antes do fencing.
                projeto.setDataProcessamento(token);
            }
            return transacao.execute(status -> {
                if (confirmarDono(projetoId, token) == 0) {
                    throw new ReivindicacaoPerdidaException(projetoId);
                }
                try {
                    return chamada.proceed();
                } catch (Throwable erro) {
                    throw new FalhaNaGravacao(erro);
                }
            });
        } catch (FalhaNaGravacao e) {
            throw e.getCause();
        }
    }

    /** Leva a falha do save para fora da transacao (que faz rollback) sem perder o tipo original. */
    private static final class FalhaNaGravacao extends RuntimeException {
        FalhaNaGravacao(Throwable causa) {
            super(causa);
        }
    }

    /** Mesmo UPDATE de ProjetoRepository#confirmarDonoDaReivindicacao: trava a linha ate o commit. */
    private int confirmarDono(Long projetoId, LocalDateTime token) {
        // FlushMode COMMIT: sem autoflush antes do UPDATE, alteracoes pendentes de entidades
        // gerenciadas so vao ao banco depois de a posse ser confirmada.
        return entityManager.createQuery("update Projeto p set p.dataProcessamento = :token "
                + "where p.id = :id and p.dataProcessamento = :token")
            .setFlushMode(FlushModeType.COMMIT)
            .setParameter("token", token)
            .setParameter("id", projetoId)
            .executeUpdate();
    }

    private static Long projetoDa(Object entidade) {
        if (entidade instanceof Projeto projeto) {
            return projeto.getId();
        }
        if (entidade instanceof AcaoPendente acao) {
            return acao.getProjetoId();
        }
        if (entidade instanceof Interacao interacao) {
            return interacao.getProjetoId();
        }
        return null;
    }
}
