package com.freelasatende.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvFileLoaderTest {

    private static final String TEST_KEY = "FREELAS_ATENDE_ENV_LOADER_TEST_KEY";
    private static final String EXISTING_KEY = "FREELAS_ATENDE_ENV_LOADER_EXISTING_KEY";

    @AfterEach
    void tearDown() {
        System.clearProperty(TEST_KEY);
        System.clearProperty(EXISTING_KEY);
    }

    @Test
    void carregaLinhaEnvSemLogarValor() {
        boolean carregou = EnvFileLoader.loadLine(TEST_KEY + "='valor seguro' # comentario");

        assertTrue(carregou);
        assertEquals("valor seguro", System.getProperty(TEST_KEY));
    }

    @Test
    void naoSobrescrevePropriedadeExistente() {
        System.setProperty(EXISTING_KEY, "valor-original");

        boolean carregou = EnvFileLoader.loadLine(EXISTING_KEY + "=valor-novo");

        assertFalse(carregou);
        assertEquals("valor-original", System.getProperty(EXISTING_KEY));
    }
}
