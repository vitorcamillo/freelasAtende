package com.freelasatende.service.impl;

import java.util.Locale;

/**
 * Reconhece a violacao da restricao unica (plataforma, id_externo) de projetos ou da
 * chave primaria de projetos (o id e atribuido pela aplicacao), que significam "outra
 * execucao ja gravou este projeto". Qualquer outra violacao de integridade (NOT NULL,
 * tamanho, acoes/interacoes) continua sendo erro de verdade.
 */
final class ViolacaoProjetoUnico {

    static final String NOME_RESTRICAO = "uk_projetos_plataforma_id_externo";

    /** Chave primaria de projetos como MySQL ("for key 'projetos.PRIMARY'") e H2 a descrevem. */
    private static final String[] CHAVE_PRIMARIA_PROJETOS = {"projetos.primary", "primary key on public.projetos("};

    private ViolacaoProjetoUnico() {
    }

    /** True se alguma causa da excecao cita a restricao unica de projetos (MySQL ou H2). */
    static boolean ehDuplicado(Throwable erro) {
        for (Throwable atual = erro; atual != null; atual = atual.getCause() == atual ? null : atual.getCause()) {
            String mensagem = atual.getMessage();
            if (mensagem != null && citaProjetoDuplicado(mensagem.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean citaProjetoDuplicado(String mensagem) {
        if (mensagem.contains(NOME_RESTRICAO)) {
            return true;
        }
        for (String chave : CHAVE_PRIMARIA_PROJETOS) {
            if (mensagem.contains(chave)) {
                return true;
            }
        }
        return false;
    }
}
