package com.freelasatende.service.impl;

import com.freelasatende.service.interfaceServico.TelegramService;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Guarda de recusa do login 99Freelas.
 *
 * <p>Quando o site responde "senha inválida/incorreta" ao login automatico, pausa novas
 * tentativas com as mesmas credenciais (evita bloquear a conta) e notifica uma unica vez.
 * Nunca guarda nem loga a senha: apenas a impressao digital SHA-256 de email+senha.</p>
 */
@Slf4j
final class LoginRecusaGuard {

    /** Minimo entre notificacoes "login assistido aberto" no Telegram. */
    private static final long INTERVALO_LOGIN_ASSISTIDO_MILLIS = 60L * 60L * 1000L;

    /** Texto fixo enviado pelo Telegram ao abrir o navegador do login assistido. */
    private static final String MENSAGEM_LOGIN_ASSISTIDO =
        "🔐 Login assistido aberto.\n\n"
        + "O sistema vai preencher o login, acionar o desafio no navegador "
        + "e exportar os cookies quando a sessão entrar.";

    private static final String MENSAGEM_SENHA_REJEITADA =
        "⛔ 99Freelas rejeitou a senha do login automatico (\"%s\").\n\n"
        + "Login automatico pausado para nao bloquear a conta. Atualize a senha do 99Freelas "
        + "na configuracao (Coolify) e o sistema volta a tentar sozinho.";

    /** Impressao digital das credenciais rejeitadas; null = sem bloqueio. */
    private volatile String digitalRejeitada;

    /** Ultimo envio da notificacao "login assistido aberto" (throttle de 60 min). */
    private long ultimoLoginAssistidoMillis;

    private final Object lock = new Object();

    /**
     * Verifica se a mensagem da pagina indica senha invalida/incorreta.
     * Normaliza minusculas e remove acentos antes de comparar.
     */
    static boolean mensagemIndicaSenhaInvalida(String mensagem) {
        if (mensagem == null || mensagem.isBlank()) {
            return false;
        }
        String normalizada = Normalizer.normalize(mensagem, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT);
        return normalizada.contains("senha")
            && (normalizada.contains("invalid") || normalizada.contains("incorret"));
    }

    /**
     * True se as credenciais atuais sao as mesmas rejeitadas anteriormente.
     * Credenciais diferentes (senha atualizada) limpam o bloqueio e devolvem false.
     */
    boolean bloqueada(String email, String senha) {
        synchronized (lock) {
            String digital = this.digitalRejeitada;
            if (digital == null) {
                return false;
            }
            if (digital.equals(gerarImpressaoDigital(email, senha))) {
                return true;
            }
            // Usuario atualizou a senha: libera novas tentativas.
            this.digitalRejeitada = null;
            return false;
        }
    }

    /**
     * Registra falha de login. Se a mensagem da pagina aponta senha invalida, bloqueia
     * as credenciais e, somente na transicao para bloqueado, notifica uma unica vez.
     */
    void registrarFalha(String email, String senha, String mensagemPagina, TelegramService telegram) {
        if (!mensagemIndicaSenhaInvalida(mensagemPagina)) {
            return;
        }
        String digital = gerarImpressaoDigital(email, senha);
        String notificar = null;
        synchronized (lock) {
            boolean jaBloqueada = digital.equals(this.digitalRejeitada);
            this.digitalRejeitada = digital;
            if (!jaBloqueada) {
                notificar = String.format(MENSAGEM_SENHA_REJEITADA, mensagemPagina);
            }
        }
        // Notificacao fora do lock para nao segurar monitores durante I/O de rede.
        if (notificar != null) {
            log.warn("Login 99Freelas pausado: site rejeitou a senha do login automatico; aguardando atualizacao da senha");
            if (telegram != null) {
                telegram.enviarNotificacao(notificar);
            }
        }
    }

    /** Envia a notificacao de login assistido respeitando o throttle de 60 min. */
    void notificarLoginAssistido(TelegramService telegram) {
        if (telegram != null && deveNotificarLoginAssistido(System.currentTimeMillis())) {
            telegram.enviarNotificacao(MENSAGEM_LOGIN_ASSISTIDO);
        }
    }

    /** True se passou o intervalo minimo desde o ultimo envio (registra o envio). */
    boolean deveNotificarLoginAssistido(long agoraMillis) {
        synchronized (lock) {
            long ultimo = ultimoLoginAssistidoMillis;
            if (ultimo != 0 && agoraMillis - ultimo < INTERVALO_LOGIN_ASSISTIDO_MILLIS) {
                return false;
            }
            ultimoLoginAssistidoMillis = agoraMillis;
            return true;
        }
    }

    /** Zera o bloqueio apos login bem-sucedido (nao reseta o throttle de notificacao). */
    void limpar() {
        synchronized (lock) {
            digitalRejeitada = null;
        }
    }

    /** SHA-256 hex de email + NUL + senha; a senha nunca e guardada nem logada. */
    private static String gerarImpressaoDigital(String email, String senha) {
        String base = (email == null ? "" : email) + "\u0000" + (senha == null ? "" : senha);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(base.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 e obrigatorio em todo JDK padrao; nunca deve ocorrer.
            throw new IllegalStateException("SHA-256 indisponivel no JDK", e);
        }
    }
}
