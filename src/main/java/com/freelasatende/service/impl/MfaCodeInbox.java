package com.freelasatende.service.impl;

import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ponto de handoff do código MFA entre o poller único do Telegram
 * ({@code TelegramCommandHandler}) e o fluxo de login assistido
 * ({@code LoginMfaHandler}).
 *
 * <p>Telegram {@code getUpdates} é desenhado para um único consumidor. Antes,
 * o login fazia polling próprio em paralelo ao handler de comandos, e os dois
 * disputavam o mesmo stream — o handler operacional acabava consumindo o código
 * numérico como se fosse um número de pedido. Aqui há um único poller; enquanto
 * {@link #aguardandoCodigo()} estiver ativo, mensagens numéricas são roteadas
 * para o login em vez de tratadas como texto livre.</p>
 */
@Component
public class MfaCodeInbox {

    private final AtomicBoolean aguardando = new AtomicBoolean(false);
    private final BlockingQueue<String> fila = new LinkedBlockingQueue<>(1);

    /** Marca início da espera por código MFA e descarta resíduos anteriores. */
    public void iniciarEspera() {
        fila.clear();
        aguardando.set(true);
    }

    /** Encerra a espera (sucesso, timeout ou erro) e limpa a fila. */
    public void encerrarEspera() {
        aguardando.set(false);
        fila.clear();
    }

    /** {@code true} enquanto o login estiver aguardando o código MFA. */
    public boolean aguardandoCodigo() {
        return aguardando.get();
    }

    /**
     * Oferta um texto recebido no Telegram como possível código MFA.
     * Só consome quando há espera ativa e o texto tem 4 a 8 dígitos.
     *
     * @return {@code true} se o texto foi consumido como código MFA.
     */
    public boolean ofertarCodigo(String texto) {
        if (!aguardando.get() || texto == null) {
            return false;
        }
        String digits = texto.replaceAll("[^0-9]", "");
        if (digits.length() >= 4 && digits.length() <= 8) {
            fila.offer(digits);
            return true;
        }
        return false;
    }

    /** Bloqueia até receber um código ou estourar o timeout. {@code null} no timeout. */
    public String aguardar(long timeoutMillis) throws InterruptedException {
        return fila.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }
}
