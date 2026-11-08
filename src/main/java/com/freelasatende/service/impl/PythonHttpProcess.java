package com.freelasatende.service.impl;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executa o subprocesso Python do transporte HTTP ({@code scripts/freelas_http.py})
 * com timeout real e stdout/stderr separados.
 *
 * <p>O stdout vai para arquivo temporario via {@link ProcessBuilder.Redirect#to(File)},
 * o stderr para outro arquivo (as warnings do Python/curl_cffi nao contaminam o
 * JSON lido pelo mapper). O stdin recebe o corpo da requisicao e e fechado;
 * {@link Process#waitFor(long, TimeUnit)} limita a execucao — antes disso o
 * {@code transferTo} bloqueava ate o fim do processo e o timeout nunca agia.</p>
 *
 * <p>Em timeout: {@link Process#destroyForcibly()} seguido de ate 5 s de espera.
 * Sinalizacao de timeout usa {@link java.util.concurrent.TimeoutException}.</p>
 */
final class PythonHttpProcess {

    private static final int STDERR_SNIPPET_CHARS = 500;
    private static final Duration ESPERA_POS_DESTROY = Duration.ofSeconds(5);

    private PythonHttpProcess() {
    }

    /**
     * Roda o comando, escreve {@code stdinBody} (UTF-8, pode ser nulo/vazio) no
     * stdin e devolve o stdout completo (UTF-8). Se o stdout vier vazio e o
     * processo sair com codigo != 0, lanca {@link IOException} com os primeiros
     * ~500 caracteres do stderr.
     *
     * @throws TimeoutException se o processo nao terminar dentro de {@code timeout}
     */
    static String run(List<String> command, String stdinBody, Duration timeout)
            throws IOException, InterruptedException, TimeoutException {
        Path stdoutArquivo = Files.createTempFile("freelas-http-out", ".txt");
        Path stderrArquivo = Files.createTempFile("freelas-http-err", ".txt");
        Process processo = null;
        try {
            processo = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.to(stdoutArquivo.toFile()))
                .redirectError(ProcessBuilder.Redirect.to(stderrArquivo.toFile()))
                .start();

            try (OutputStream stdin = processo.getOutputStream()) {
                if (stdinBody != null && !stdinBody.isEmpty()) {
                    stdin.write(stdinBody.getBytes(StandardCharsets.UTF_8));
                }
            }

            if (!processo.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new TimeoutException(
                    "Processo nao terminou em " + timeout.toSeconds() + "s: " + command);
            }

            String stdout = Files.readString(stdoutArquivo, StandardCharsets.UTF_8);
            if (stdout.isEmpty() && processo.exitValue() != 0) {
                String stderr = Files.readString(stderrArquivo, StandardCharsets.UTF_8);
                throw new IOException("Processo saiu com codigo " + processo.exitValue()
                    + " e stdout vazio. stderr: " + snippet(stderr));
            }
            return stdout;
        } finally {
            if (processo != null && processo.isAlive()) {
                processo.destroyForcibly();
                processo.waitFor(ESPERA_POS_DESTROY.toMillis(), TimeUnit.MILLISECONDS);
            }
            Files.deleteIfExists(stdoutArquivo);
            Files.deleteIfExists(stderrArquivo);
        }
    }

    private static String snippet(String stderr) {
        String texto = stderr == null ? "" : stderr.trim();
        return texto.length() <= STDERR_SNIPPET_CHARS
            ? texto
            : texto.substring(0, STDERR_SNIPPET_CHARS);
    }
}
