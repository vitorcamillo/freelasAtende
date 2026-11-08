package com.freelasatende.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerChromeContractTest {

    @Test
    void imagemDeProducaoIncluiChromeEDisplayVirtualParaLoginAssistido() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));

        assertTrue(dockerfile.contains("chromium"));
        assertTrue(dockerfile.contains("xvfb"));
        assertTrue(dockerfile.contains("CHROME_BIN=/usr/bin/chromium"));
        assertTrue(dockerfile.contains("Xvfb :99"));
    }
}
