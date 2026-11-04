package com.freelasatende.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLIntegrityConstraintViolationException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViolacaoProjetoUnicoTest {

    @Test
    void reconheceSomenteARestricaoUnicaDeProjetos() {
        DataIntegrityViolationException duplicado = new DataIntegrityViolationException("could not execute statement",
            new SQLIntegrityConstraintViolationException(
                "Duplicate entry 'FREELAS_99-123' for key 'projetos.uk_projetos_plataforma_id_externo'"));
        DataIntegrityViolationException h2 = new DataIntegrityViolationException(
            "Unique index or primary key violation: \"PUBLIC.UK_PROJETOS_PLATAFORMA_ID_EXTERNO_INDEX_D\"");
        DataIntegrityViolationException notNull = new DataIntegrityViolationException("could not execute statement",
            new SQLIntegrityConstraintViolationException("Column 'titulo' cannot be null"));

        assertTrue(ViolacaoProjetoUnico.ehDuplicado(duplicado));
        assertTrue(ViolacaoProjetoUnico.ehDuplicado(h2));
        assertTrue(ViolacaoProjetoUnico.ehDuplicado(new DataIntegrityViolationException("x",
            new SQLIntegrityConstraintViolationException("Duplicate entry '123' for key 'projetos.PRIMARY'"))));
        assertTrue(ViolacaoProjetoUnico.ehDuplicado(new DataIntegrityViolationException(
            "Unique index or primary key violation: \"PRIMARY KEY ON PUBLIC.PROJETOS(ID) ( /* key:1 */ 123)\"")));
        assertFalse(ViolacaoProjetoUnico.ehDuplicado(new DataIntegrityViolationException(
            "Unique index or primary key violation: \"PRIMARY KEY ON PUBLIC.INTERACOES(ID)\"")));
        assertFalse(ViolacaoProjetoUnico.ehDuplicado(notNull));
        assertFalse(ViolacaoProjetoUnico.ehDuplicado(null));
    }
}
