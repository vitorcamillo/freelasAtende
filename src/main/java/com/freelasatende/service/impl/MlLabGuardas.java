package com.freelasatende.service.impl;

import com.freelasatende.exception.TransicaoInvalidaException;
import com.freelasatende.model.dto.MlLabDtos;
import com.freelasatende.model.entity.MlLabRuleCandidate;
import com.freelasatende.model.entity.MlLabRun;
import com.freelasatende.model.enums.MlLabCandidateStatus;
import com.freelasatende.model.enums.MlLabRunStatus;

import java.util.Locale;

/**
 * Guardas de transicao e validacao de entrada do ML Lab (ACHADO FA-A6): recusa
 * pausa de run nunca iniciado, parada redundante de run ja parado, mensagem de
 * chat vazia e aprovacao repetida de candidata; normaliza parametros de criacao
 * de run. Pura e estatica: so valida/normaliza, nao toca repositorio nem grava.
 */
final class MlLabGuardas {

    /** Resposta idempotente quando a candidata ja estava aprovada. */
    static final String MSG_CANDIDATA_JA_APROVADA = "Regra candidata ja estava aprovada; nenhuma acao repetida.";

    private static final int DEFAULT_CYCLES = 100;
    private static final int MIN_CYCLES = 1;
    private static final int MAX_CYCLES = 500;
    private static final int DEFAULT_MAX_TOKENS = 1800;
    private static final int MIN_MAX_TOKENS = 512;
    private static final int MAX_MAX_TOKENS = 6000;
    private static final String SELECTION_TOP10 = "TOP10";
    private static final String SELECTION_MULTI_CRITERIA = "MULTI_CRITERIA";

    private MlLabGuardas() {
    }

    /** Run em CRIADO nunca entrou em execucao: nao ha execucao para pausar. */
    static void exigirRunEmExecucaoParaPausar(MlLabRun run) {
        if (run.getStatus() == MlLabRunStatus.CRIADO) {
            throw new TransicaoInvalidaException("Run nao iniciado: nao ha execucao para pausar.");
        }
    }

    /** Run ja PARADO: parar de novo seria regravacao sem mudanca de estado. */
    static void exigirRunNaoParadoParaParar(MlLabRun run) {
        if (run.getStatus() == MlLabRunStatus.PARADO) {
            throw new TransicaoInvalidaException("Run ja esta parado.");
        }
    }

    /** Valida request/mensagem do chat e devolve a mensagem pronta para uso. */
    static String exigirMensagemChat(MlLabDtos.ChatRequest request) {
        String mensagem = request != null ? request.getMensagem() : null;
        if (mensagem == null || mensagem.isBlank()) {
            throw new IllegalArgumentException("Mensagem do chat e obrigatoria.");
        }
        return mensagem;
    }

    /** True quando a candidata ja esta APROVADA: reaprovar nao pode regravar nem duplicar regra. */
    static boolean candidataJaAprovada(MlLabRuleCandidate candidate) {
        return candidate.getStatus() == MlLabCandidateStatus.APROVADA;
    }

    static int normalizarCiclos(Integer ciclos) {
        if (ciclos == null || ciclos <= 0) return DEFAULT_CYCLES;
        return Math.max(MIN_CYCLES, Math.min(MAX_CYCLES, ciclos));
    }

    static int normalizarMaxTokens(Integer maxTokens) {
        if (maxTokens == null || maxTokens <= 0) return DEFAULT_MAX_TOKENS;
        return Math.max(MIN_MAX_TOKENS, Math.min(MAX_MAX_TOKENS, maxTokens));
    }

    static int normalizarFaixa(Integer value, int fallback, int min, int max) {
        if (value == null) return fallback;
        return Math.max(min, Math.min(max, value));
    }

    static String normalizarSelectionMode(String value) {
        if (value == null || value.isBlank()) return SELECTION_TOP10;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return SELECTION_MULTI_CRITERIA.equals(normalized) ? SELECTION_MULTI_CRITERIA : SELECTION_TOP10;
    }

    static String textoOuPadrao(String valor, String padrao) { return valor == null || valor.isBlank() ? padrao : valor.trim(); }
}
