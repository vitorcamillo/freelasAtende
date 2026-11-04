package com.freelasatende.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
class DatabaseSchemaInitializer implements ApplicationRunner {

    private static final Pattern IDENTIFICADOR_SQL_SEGURO = Pattern.compile("[A-Za-z0-9_]+");
    /**
     * Teto de espera por lock de metadados do MySQL para esta sessao.
     *
     * <p>Causa real de deploy travado (medido em producao): durante o rolling
     * update, o container ANTIGO ainda esta no ar e conectado ao mesmo MySQL
     * enquanto o container NOVO sobe. Um {@code ALTER TABLE} sem lock timeout
     * fica preso INDEFINIDAMENTE esperando o lock de metadados liberar — e o
     * container novo nunca chega a abrir a porta 8080, o healthcheck falha
     * apos ~3min e o Coolify faz rollback. Com o timeout, a tentativa falha
     * rapido e o {@code isAlteracaoNecessaria} abaixo evita re-executar o
     * ALTER quando o schema ja esta correto (caso mais comum).</p>
     */
    private static final int LOCK_WAIT_TIMEOUT_SEGUNDOS = 10;

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!isMySql()) {
            return;
        }

        jdbcTemplate.execute("SET SESSION lock_wait_timeout = " + LOCK_WAIT_TIMEOUT_SEGUNDOS);

        if (isColunaDiferenteDoEsperado("projetos", "subcategoria", "text")) {
            executarAlterComTimeout("ALTER TABLE projetos MODIFY subcategoria TEXT", "projetos.subcategoria");
        }
        for (String tabela : tabelasComColunaPlataforma()) {
            if (!IDENTIFICADOR_SQL_SEGURO.matcher(tabela).matches()) {
                throw new IllegalStateException("Identificador de tabela inesperado no schema: " + tabela);
            }
            if (isColunaDiferenteDoEsperado(tabela, "plataforma", "varchar")
                || isTamanhoColunaMenorQue(tabela, "plataforma", 40)) {
                executarAlterComTimeout(
                    "ALTER TABLE `" + tabela + "` MODIFY plataforma VARCHAR(40)", tabela + ".plataforma");
            }
        }
        criarIndiceSeAusente("interacoes", "idx_interacoes_projeto_tipo_data",
            "ALTER TABLE interacoes ADD INDEX idx_interacoes_projeto_tipo_data "
                + "(projeto_id, tipo, data), ALGORITHM=INPLACE, LOCK=NONE");
        criarIndiceSeAusente("interacoes", "idx_interacoes_tipo_data",
            "ALTER TABLE interacoes ADD INDEX idx_interacoes_tipo_data "
                + "(tipo, data), ALGORITHM=INPLACE, LOCK=NONE");
        criarIndiceSeAusente("interacoes", "idx_interacoes_plataforma_projeto_tipo",
            "ALTER TABLE interacoes ADD INDEX idx_interacoes_plataforma_projeto_tipo "
                + "(plataforma, projeto_id, tipo), ALGORITHM=INPLACE, LOCK=NONE");
        criarIndiceSeAusente("interacoes", "idx_interacoes_tipo_conteudo",
            "ALTER TABLE interacoes ADD INDEX idx_interacoes_tipo_conteudo "
                + "(tipo, conteudo(255)), ALGORITHM=INPLACE, LOCK=NONE");
        // acoes_pendentes so tinha a chave primaria: as tres consultas abaixo varriam a tabela
        // inteira (110 MB por causa do payload_json) centenas de milhares de vezes. Medido em
        // producao 18/10/2026: 718 GiB lidos do arquivo em 14 dias, o maior leitor do MySQL
        // compartilhado do servidor e do SSD de todos os apps. data_criacao fecha o indice
        // para o ORDER BY ... DESC sair dele, sem filesort.
        criarIndiceSeAusente("acoes_pendentes", "idx_acoes_plataforma_projeto_tipo_status",
            "ALTER TABLE acoes_pendentes ADD INDEX idx_acoes_plataforma_projeto_tipo_status "
                + "(plataforma, projeto_id, tipo, status, data_criacao), ALGORITHM=INPLACE, LOCK=NONE");
        criarIndiceSeAusente("acoes_pendentes", "idx_acoes_plataforma_conversa_tipo_status",
            "ALTER TABLE acoes_pendentes ADD INDEX idx_acoes_plataforma_conversa_tipo_status "
                + "(plataforma, conversa_id, tipo, status, data_criacao), ALGORITHM=INPLACE, LOCK=NONE");
        criarIndiceSeAusente("acoes_pendentes", "idx_acoes_status",
            "ALTER TABLE acoes_pendentes ADD INDEX idx_acoes_status (status), "
                + "ALGORITHM=INPLACE, LOCK=NONE");
        criarIndiceUnicoProjetosSeAusente();
        criarIndiceSeAusente("interacoes", "idx_interacoes_data",
            "ALTER TABLE interacoes ADD INDEX idx_interacoes_data "
                + "(data), ALGORITHM=INPLACE, LOCK=NONE");
        log.info("Schema MySQL verificado: textos longos e marketplaces adicionais suportados");
    }

    /**
     * Roda o ALTER e, se travar por lock (outro container do rolling deploy
     * segurando a tabela), registra e segue sem derrubar o boot — o schema ja
     * atendia na versao anterior e o proximo boot tenta de novo.
     */
    private void executarAlterComTimeout(String sql, String descricao) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            log.warn("Nao foi possivel migrar {} agora (provavel lock de outro container do rolling "
                + "deploy); seguindo o boot, o schema sera reavaliado no proximo start: {}",
                descricao, e.getMessage());
        }
    }

    private void criarIndiceSeAusente(String tabela, String nome, String sql) {
        List<String> indices = jdbcTemplate.queryForList(
            "SELECT INDEX_NAME FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
            String.class, tabela, nome);
        if (indices.isEmpty()) {
            executarAlterComTimeout(sql, tabela + "." + nome);
        }
    }

    /**
     * Restricao unica (plataforma, id_externo) em projetos: impede que duas
     * threads/instancias gravem o mesmo projeto externo duas vezes
     * (ACHADO-FA-CONC-02-1). Antes de criar, conta duplicatas existentes: se
     * houver, NAO cria (o ALTER falharia) e registra WARN com a contagem, sem
     * expor dados dos projetos; a deduplicacao fica como acao manual do
     * operador e o boot segue com o comportamento anterior.
     */
    private void criarIndiceUnicoProjetosSeAusente() {
        String nome = "uk_projetos_plataforma_id_externo";
        List<String> indices = jdbcTemplate.queryForList(
            "SELECT INDEX_NAME FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
            String.class, "projetos", nome);
        if (!indices.isEmpty()) {
            return;
        }
        List<Long> duplicatas = jdbcTemplate.queryForList(
            "SELECT COUNT(*) FROM (SELECT plataforma, id_externo FROM projetos "
                + "WHERE plataforma IS NOT NULL AND id_externo IS NOT NULL "
                + "GROUP BY plataforma, id_externo HAVING COUNT(*) > 1) duplicadas",
            Long.class);
        long totalDuplicatas = duplicatas.isEmpty() ? 0 : duplicatas.get(0);
        if (totalDuplicatas > 0) {
            log.warn("Indice unico {} nao criado: {} combinacao(oes) de plataforma/id externo "
                    + "duplicada(s) na tabela projetos; deduplicar manualmente e reiniciar para recriar o indice.",
                nome, totalDuplicatas);
            return;
        }
        executarAlterComTimeout(
            "ALTER TABLE projetos ADD UNIQUE INDEX " + nome + " (plataforma, id_externo), "
                + "ALGORITHM=INPLACE, LOCK=NONE",
            "projetos." + nome);
    }

    private boolean isColunaDiferenteDoEsperado(String tabela, String coluna, String tipoEsperado) {
        List<String> tipos = jdbcTemplate.queryForList(
            "SELECT DATA_TYPE FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
            String.class, tabela, coluna);
        return tipos.isEmpty() || !tipoEsperado.equalsIgnoreCase(tipos.get(0));
    }

    private boolean isTamanhoColunaMenorQue(String tabela, String coluna, int minimo) {
        List<Integer> tamanhos = jdbcTemplate.queryForList(
            "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
            Integer.class, tabela, coluna);
        return !tamanhos.isEmpty() && tamanhos.get(0) != null && tamanhos.get(0) < minimo;
    }

    private List<String> tabelasComColunaPlataforma() {
        return jdbcTemplate.queryForList(
            "SELECT DISTINCT table_name FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND column_name = ?",
            String.class,
            "plataforma"
        );
    }

    private boolean isMySql() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            return productName != null && productName.toLowerCase().contains("mysql");
        }
    }
}
