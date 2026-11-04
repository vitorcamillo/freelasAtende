package com.freelasatende.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Serializa a abertura de sessoes Chrome reais entre TODAS as plataformas
 * (99Freelas, Upwork, Freelancer.com, Freelancer.com.br).
 *
 * <p>Causa real (incidente medido em producao): o container roda com 1GB de
 * memoria total (Java + Chrome + Xvfb). Duas sessoes Chrome simultaneas — por
 * exemplo login automatico do Upwork rodando ao mesmo tempo que o discovery do
 * .com.br — estouram a memoria e o Docker mata o container (OOM), que reinicia
 * sozinho. Evidencia: reinicio do processo as 03:31 sem nenhum log de shutdown
 * graciosos, poucos minutos depois de Upwork e .com.br terem aberto Chrome
 * quase ao mesmo tempo.</p>
 *
 * <p>Cada ponto que chama {@code new JavaDriverless(...)} deve adquirir esta
 * permissao antes e liberar depois, mesmo em caso de erro.</p>
 */
@Slf4j
@Component
class ChromeSessaoLock {

    /** Uma sessao Chrome por vez no processo inteiro. */
    private final Semaphore permissao = new Semaphore(1, true);

    /** Teto de espera pela vez: nao vale a pena esperar mais que isso por login/discovery. */
    private static final long TIMEOUT_ESPERA_SEGUNDOS = 60;

    /**
     * Tenta adquirir a permissao. Retorna {@code false} se outra sessao Chrome
     * ja estiver aberta e a espera exceder o timeout — o chamador deve desistir
     * dessa rodada em vez de abrir um segundo Chrome.
     */
    boolean adquirir(String quem) {
        try {
            boolean ok = permissao.tryAcquire(TIMEOUT_ESPERA_SEGUNDOS, TimeUnit.SECONDS);
            if (!ok) {
                log.warn("{}: outra sessao Chrome ja em uso ha mais de {}s; adiando para a proxima rodada "
                    + "(evita estourar a memoria do container com 2 Chromes simultaneos).",
                    quem, TIMEOUT_ESPERA_SEGUNDOS);
            }
            return ok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    void liberar() {
        permissao.release();
    }
}
