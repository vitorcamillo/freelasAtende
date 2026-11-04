package com.freelasatende.util;

import lombok.extern.slf4j.Slf4j;

import com.freelasatende.service.interfaceServico.IaProvider;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Utilitário de retry com backoff exponencial + jitter.
 * Inclui circuit breaker simples.
 */
@Slf4j
public final class RetryUtils {

    private RetryUtils() {}

    /**
     * Executa uma operação com retry automático usando backoff exponencial.
     *
     * @param operacao   lambda que executa a operação
     * @param maxRetries número máximo de tentativas
     * @param nomeOp     nome para logging
     * @param <T>        tipo de retorno
     * @return resultado da operação
     * @throws Exception se todas as tentativas falharem
     */
    public static <T> T comRetry(Supplier<T> operacao, int maxRetries, String nomeOp)
            throws Exception {
        Exception ultimoErro = null;

        for (int tentativa = 1; tentativa <= maxRetries; tentativa++) {
            try {
                return operacao.get();
            } catch (Exception e) {
                ultimoErro = e;
                if (naoRetriavel(e)) {
                    log.warn("{} - falha nao-retriavel: {}", nomeOp, e.getMessage());
                    throw e;
                }
                if (tentativa < maxRetries) {
                    long delay = calcularBackoff(tentativa);
                    log.warn("{} - tentativa {}/{} falhou: {}. Retry em {}ms",
                        nomeOp, tentativa, maxRetries, descricaoErro(e), delay);
                    Thread.sleep(delay);
                }
            }
        }

        log.error("{} - todas as {} tentativas falharam", nomeOp, maxRetries);
        throw ultimoErro;
    }

    /**
     * Versão para operações que podem lançar checked exceptions.
     */
    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    public static <T> T comRetryChecked(CheckedSupplier<T> operacao, int maxRetries, String nomeOp)
            throws Exception {
        Exception ultimoErro = null;

        for (int tentativa = 1; tentativa <= maxRetries; tentativa++) {
            try {
                return operacao.get();
            } catch (Exception e) {
                ultimoErro = e;
                if (naoRetriavel(e)) {
                    log.warn("{} - falha nao-retriavel: {}", nomeOp, e.getMessage());
                    throw e;
                }
                if (tentativa < maxRetries) {
                    long delay = calcularBackoff(tentativa);
                    log.warn("{} - tentativa {}/{} falhou: {}. Retry em {}ms",
                        nomeOp, tentativa, maxRetries, descricaoErro(e), delay);
                    Thread.sleep(delay);
                }
            }
        }

        log.error("{} - todas as {} tentativas falharam", nomeOp, maxRetries);
        throw ultimoErro;
    }

    /**
     * Calcula delay com backoff exponencial + jitter.
     * Base: 1s, 2s, 4s, 8s... com ±30% jitter.
     */
    public static long calcularBackoff(int tentativa) {
        long baseMs = 1000L * (long) Math.pow(2, tentativa - 1);
        long maxMs = Math.min(baseMs, 30_000); // Máx 30 segundos
        long jitter = (long) (maxMs * 0.3 * (ThreadLocalRandom.current().nextDouble() - 0.5));
        return maxMs + jitter;
    }

    private static String descricaoErro(Exception erro) {
        if (erro instanceof IaProvider.IaProviderException iaErro) {
            String base = "falha de IA HTTP " + iaErro.getStatusCode();
            // Status <= 0 significa que a requisicao nem chegou a ter resposta
            // HTTP (DNS/rede/timeout). Nesses casos a mensagem carrega a causa
            // real; para erros HTTP ela ja esta no corpo logado a parte.
            if (iaErro.getStatusCode() <= 0 && erro.getMessage() != null && !erro.getMessage().isBlank()) {
                base += " (" + erro.getMessage() + ")";
            }
            return base;
        }
        return erro.getMessage() != null ? erro.getMessage() : erro.getClass().getSimpleName();
    }

    private static boolean naoRetriavel(Exception e) {
        return e instanceof IaProvider.IaProviderException iaException
            && !iaException.isRetriable();
    }
}
