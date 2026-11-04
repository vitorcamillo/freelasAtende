package com.freelasatende.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseSchemaInitializerTest {

    @Test
    void converteEnumsLegadosDePlataformaParaVarcharQuandoSchemaDesatualizado() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(jdbcTemplate.queryForList(anyString(), org.mockito.ArgumentMatchers.eq(String.class),
            org.mockito.ArgumentMatchers.eq("plataforma")))
            .thenReturn(List.of("projetos", "interacoes", "mensagens_cliente_registro"));
        // Schema desatualizado: coluna ainda com o tipo antigo. Simula ausencia de
        // metadado (queryForList vazio) para forcar o ALTER nos dois casos.
        when(jdbcTemplate.queryForList(anyString(), org.mockito.ArgumentMatchers.eq(String.class),
            org.mockito.ArgumentMatchers.eq("projetos"), org.mockito.ArgumentMatchers.eq("subcategoria")))
            .thenReturn(List.of());
        when(jdbcTemplate.queryForList(anyString(), org.mockito.ArgumentMatchers.eq(String.class),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("plataforma")))
            .thenReturn(List.of());

        new DatabaseSchemaInitializer(dataSource, jdbcTemplate)
            .run(new DefaultApplicationArguments(new String[0]));

        verify(jdbcTemplate).execute("ALTER TABLE projetos MODIFY subcategoria TEXT");
        verify(jdbcTemplate, times(3)).execute(org.mockito.ArgumentMatchers.contains("MODIFY plataforma VARCHAR(40)"));
    }

    @Test
    void naoAlteraSchemaForaDoMySql() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("H2");

        new DatabaseSchemaInitializer(dataSource, jdbcTemplate)
            .run(new DefaultApplicationArguments(new String[0]));

        verify(jdbcTemplate, never()).execute(anyString());
    }

    /**
     * Regressao real: com o schema ja no formato esperado (caso mais comum no
     * boot normal, fora do primeiro deploy), o initializer NAO deve tentar o
     * ALTER — evita o lock de metadados que travava o container novo enquanto o
     * antigo ainda estava rodando durante o rolling deploy.
     */
    @Test
    void naoTentaAlterQuandoSchemaJaEstaAtualizado() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(jdbcTemplate.queryForList(anyString(), org.mockito.ArgumentMatchers.eq(String.class),
            org.mockito.ArgumentMatchers.eq("plataforma")))
            .thenReturn(List.of("projetos"));
        when(jdbcTemplate.queryForList(anyString(), org.mockito.ArgumentMatchers.eq(String.class),
            org.mockito.ArgumentMatchers.eq("projetos"), org.mockito.ArgumentMatchers.eq("subcategoria")))
            .thenReturn(List.of("text"));
        when(jdbcTemplate.queryForList(anyString(), org.mockito.ArgumentMatchers.eq(String.class),
            org.mockito.ArgumentMatchers.eq("projetos"), org.mockito.ArgumentMatchers.eq("plataforma")))
            .thenReturn(List.of("varchar"));
        when(jdbcTemplate.queryForList(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class),
            org.mockito.ArgumentMatchers.eq("projetos"), org.mockito.ArgumentMatchers.eq("plataforma")))
            .thenReturn(List.of(40));
        when(jdbcTemplate.queryForList(contains("information_schema.statistics"),
            org.mockito.ArgumentMatchers.eq(String.class), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(List.of("idx_existente"));

        new DatabaseSchemaInitializer(dataSource, jdbcTemplate)
            .run(new DefaultApplicationArguments(new String[0]));

        verify(jdbcTemplate, never()).execute(org.mockito.ArgumentMatchers.contains("ALTER TABLE"));
    }

    /**
     * Regressao real: se o ALTER travar por lock de metadados (outro container
     * do rolling deploy segurando a tabela), o boot nao pode morrer — o sistema
     * segue no ar com o schema da versao anterior, que ja funcionava.
     */
    @Test
    void bootSeguePraFrenteMesmoSeAlterFalharPorLock() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(jdbcTemplate.queryForList(anyString(), org.mockito.ArgumentMatchers.eq(String.class),
            org.mockito.ArgumentMatchers.eq("plataforma")))
            .thenReturn(List.of());
        when(jdbcTemplate.queryForList(anyString(), org.mockito.ArgumentMatchers.eq(String.class),
            org.mockito.ArgumentMatchers.eq("projetos"), org.mockito.ArgumentMatchers.eq("subcategoria")))
            .thenReturn(List.of());
        org.mockito.Mockito.doThrow(new org.springframework.dao.QueryTimeoutException("Lock wait timeout exceeded"))
            .when(jdbcTemplate).execute("ALTER TABLE projetos MODIFY subcategoria TEXT");

        assertDoesNotThrow(() -> new DatabaseSchemaInitializer(dataSource, jdbcTemplate)
            .run(new DefaultApplicationArguments(new String[0])));
    }

    /**
     * Regressao real (producao 18/10/2026): acoes_pendentes so tinha a chave primaria e as
     * consultas por plataforma/projeto/conversa/tipo/status varriam os 110 MB da tabela a cada
     * chamada — 718 GiB lidos em 14 dias, o maior leitor do disco compartilhado do servidor.
     */
    @Test
    void criaIndicesDeAcoesPendentesSoQuandoAusentes() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(jdbcTemplate.queryForList(anyString(), org.mockito.ArgumentMatchers.eq(String.class),
            org.mockito.ArgumentMatchers.eq("plataforma")))
            .thenReturn(List.of());
        when(jdbcTemplate.queryForList(anyString(), org.mockito.ArgumentMatchers.eq(String.class),
            org.mockito.ArgumentMatchers.eq("projetos"), org.mockito.ArgumentMatchers.eq("subcategoria")))
            .thenReturn(List.of("text"));
        // Indices de interacoes e projetos ja existem; os de acoes_pendentes nao.
        when(jdbcTemplate.queryForList(contains("information_schema.statistics"),
            org.mockito.ArgumentMatchers.eq(String.class), org.mockito.ArgumentMatchers.eq("interacoes"),
            org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(List.of("existe"));
        when(jdbcTemplate.queryForList(contains("information_schema.statistics"),
            org.mockito.ArgumentMatchers.eq(String.class), org.mockito.ArgumentMatchers.eq("projetos"),
            org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(List.of("existe"));
        when(jdbcTemplate.queryForList(contains("information_schema.statistics"),
            org.mockito.ArgumentMatchers.eq(String.class), org.mockito.ArgumentMatchers.eq("acoes_pendentes"),
            org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(List.of());

        new DatabaseSchemaInitializer(dataSource, jdbcTemplate)
            .run(new DefaultApplicationArguments(new String[0]));

        verify(jdbcTemplate).execute(contains(
            "idx_acoes_plataforma_projeto_tipo_status (plataforma, projeto_id, tipo, status, data_criacao)"));
        verify(jdbcTemplate).execute(contains(
            "idx_acoes_plataforma_conversa_tipo_status (plataforma, conversa_id, tipo, status, data_criacao)"));
        verify(jdbcTemplate).execute(contains("idx_acoes_status (status)"));
        verify(jdbcTemplate, times(3)).execute(contains("ALTER TABLE acoes_pendentes ADD INDEX"));
        verify(jdbcTemplate, times(3)).execute(contains("ALGORITHM=INPLACE, LOCK=NONE"));
        verify(jdbcTemplate, never()).execute(contains("ALTER TABLE interacoes"));
    }

    private JdbcTemplate jdbcComIndicesExistentesExcetoUnicoDeProjetos() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), org.mockito.ArgumentMatchers.eq(String.class),
            org.mockito.ArgumentMatchers.eq("plataforma")))
            .thenReturn(List.of());
        when(jdbcTemplate.queryForList(anyString(), org.mockito.ArgumentMatchers.eq(String.class),
            org.mockito.ArgumentMatchers.eq("projetos"), org.mockito.ArgumentMatchers.eq("subcategoria")))
            .thenReturn(List.of("text"));
        when(jdbcTemplate.queryForList(contains("information_schema.statistics"),
            org.mockito.ArgumentMatchers.eq(String.class), anyString(), anyString()))
            .thenReturn(List.of("existe"));
        when(jdbcTemplate.queryForList(contains("information_schema.statistics"),
            org.mockito.ArgumentMatchers.eq(String.class), org.mockito.ArgumentMatchers.eq("projetos"),
            org.mockito.ArgumentMatchers.eq("uk_projetos_plataforma_id_externo")))
            .thenReturn(List.of());
        return jdbcTemplate;
    }

    @Test
    void criaIndiceDeDataDeInteracoesQuandoAusente() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcComIndicesExistentesExcetoUnicoDeProjetos();
        when(jdbcTemplate.queryForList(contains("information_schema.statistics"),
            org.mockito.ArgumentMatchers.eq(String.class), org.mockito.ArgumentMatchers.eq("interacoes"),
            org.mockito.ArgumentMatchers.eq("idx_interacoes_data")))
            .thenReturn(List.of());

        new DatabaseSchemaInitializer(dataSourceMySql(), jdbcTemplate)
            .run(new DefaultApplicationArguments(new String[0]));

        verify(jdbcTemplate).execute(contains(
            "ALTER TABLE interacoes ADD INDEX idx_interacoes_data (data)"));
    }

    @Test
    void naoRecriaIndiceDeDataDeInteracoesQuandoJaExiste() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcComIndicesExistentesExcetoUnicoDeProjetos();

        new DatabaseSchemaInitializer(dataSourceMySql(), jdbcTemplate)
            .run(new DefaultApplicationArguments(new String[0]));

        verify(jdbcTemplate, never()).execute(contains("ADD INDEX idx_interacoes_data"));
    }

    @Test
    void falhaPorLockAoCriarIndiceDeDataNaoDerrubaBoot() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcComIndicesExistentesExcetoUnicoDeProjetos();
        when(jdbcTemplate.queryForList(contains("information_schema.statistics"),
            org.mockito.ArgumentMatchers.eq(String.class), org.mockito.ArgumentMatchers.eq("interacoes"),
            org.mockito.ArgumentMatchers.eq("idx_interacoes_data")))
            .thenReturn(List.of());
        org.mockito.Mockito.doThrow(new org.springframework.dao.QueryTimeoutException("Lock wait timeout exceeded"))
            .when(jdbcTemplate).execute(contains("ADD INDEX idx_interacoes_data"));

        assertDoesNotThrow(() -> new DatabaseSchemaInitializer(dataSourceMySql(), jdbcTemplate)
            .run(new DefaultApplicationArguments(new String[0])));

        verify(jdbcTemplate).execute(contains("ADD INDEX idx_interacoes_data"));
    }

    private DataSource dataSourceMySql() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        return dataSource;
    }

    @Test
    void criaIndiceUnicoDeProjetosQuandoNaoHaDuplicatas() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcComIndicesExistentesExcetoUnicoDeProjetos();
        when(jdbcTemplate.queryForList(contains("HAVING COUNT(*) > 1"), org.mockito.ArgumentMatchers.eq(Long.class)))
            .thenReturn(List.of(0L));

        new DatabaseSchemaInitializer(dataSourceMySql(), jdbcTemplate)
            .run(new DefaultApplicationArguments(new String[0]));

        verify(jdbcTemplate).execute(contains(
            "ALTER TABLE projetos ADD UNIQUE INDEX uk_projetos_plataforma_id_externo (plataforma, id_externo)"));
    }

    @Test
    void naoCriaIndiceUnicoDeProjetosComDuplicatasEBootSegue() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcComIndicesExistentesExcetoUnicoDeProjetos();
        when(jdbcTemplate.queryForList(contains("HAVING COUNT(*) > 1"), org.mockito.ArgumentMatchers.eq(Long.class)))
            .thenReturn(List.of(2L));

        assertDoesNotThrow(() -> new DatabaseSchemaInitializer(dataSourceMySql(), jdbcTemplate)
            .run(new DefaultApplicationArguments(new String[0])));

        verify(jdbcTemplate, never()).execute(contains("ADD UNIQUE INDEX"));
    }

    @Test
    void falhaAoCriarIndiceUnicoDeProjetosNaoDerrubaBoot() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcComIndicesExistentesExcetoUnicoDeProjetos();
        when(jdbcTemplate.queryForList(contains("HAVING COUNT(*) > 1"), org.mockito.ArgumentMatchers.eq(Long.class)))
            .thenReturn(List.of(0L));
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("Duplicate entry"))
            .when(jdbcTemplate).execute(contains("ADD UNIQUE INDEX"));

        assertDoesNotThrow(() -> new DatabaseSchemaInitializer(dataSourceMySql(), jdbcTemplate)
            .run(new DefaultApplicationArguments(new String[0])));
    }
}
