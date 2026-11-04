package com.freelasatende.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerChromeContractTest {

    @Test
    void imagemDeProducaoIncluiChromeEDisplayVirtualParaLoginAssistido() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));

        assertTrue(dockerfile.contains("google-chrome-stable"));
        assertTrue(dockerfile.contains("xvfb"));
        assertTrue(dockerfile.contains("CHROME_BIN=/usr/bin/google-chrome"));
        assertTrue(dockerfile.contains("CHROMIUM_BIN=/usr/bin/google-chrome"));
        assertTrue(dockerfile.contains("Xvfb :99"));
    }
}
