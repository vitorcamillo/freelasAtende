package com.freelasatende.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Em MySQL, quem manda no schema e o DatabaseSchemaInitializer.
 *
 * <p>Com ddl-auto=update o Hibernate reemitia ALTER de enum em todo boot e o
 * inicializador reconvertia para VARCHAR(40) logo depois -- cabo de guerra que
 * reconstruia tabelas de centenas de milhares de linhas e travou dois deploys
 * (45min e 15min) esperando lock de metadados, com a aplicacao fora do ar.</p>
 */
class MysqlDdlAutoContractTest {

    private static final Path PERFIL_MYSQL = Path.of("src/main/resources/application-mysql.yml");

    @Test
    @DisplayName("perfil mysql nao pode deixar o Hibernate emitir DDL por padrao")
    void ddlAutoDesligadoPorPadrao() throws Exception {
        String conteudo = Files.readString(PERFIL_MYSQL);
        assertTrue(conteudo.contains("ddl-auto: ${DB_DDL_AUTO:none}"),
            "o padrao precisa ser none, com escape por env para emergencia");
        assertFalse(conteudo.contains("ddl-auto: update"),
            "update reintroduz o cabo de guerra de ALTER com o DatabaseSchemaInitializer");
    }
}

