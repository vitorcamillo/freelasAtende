package com.freelasatende.service.impl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonHttpProcessTest {

    @Test
    void stdoutNaoContemStderr() throws Exception {
        String saida = PythonHttpProcess.run(
            List.of("sh", "-c", "echo aviso >&2; echo {\\\"ok\\\":1}"),
            null,
            Duration.ofSeconds(10));

        assertTrue(saida.contains("\"ok\":1"), "stdout deveria conter o JSON, mas foi: " + saida);
        assertFalse(saida.contains("aviso"), "stderr nao deveria se misturar ao stdout");
    }

    @Test
    void timeoutRealInterrompeProcesso() {
        long inicio = System.nanoTime();
        assertThrows(TimeoutException.class, () ->
            PythonHttpProcess.run(List.of("sh", "-c", "sleep 30"), null, Duration.ofSeconds(1)));
        long decoradoMs = (System.nanoTime() - inicio) / 1_000_000;

        assertTrue(decoradoMs < 8_000, "timeout de 1 s deveria disparar em menos de ~8 s, levou " + decoradoMs + " ms");
    }

    @Test
    void stdinChegaAoProcesso() throws Exception {
        String saida = PythonHttpProcess.run(
            List.of("sh", "-c", "cat"),
            "abc",
            Duration.ofSeconds(10));

        assertEquals("abc", saida);
    }
}
