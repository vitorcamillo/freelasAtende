package com.freelasatende.service.impl;

import com.freelasatende.model.dto.AcaoControleDTO;
import com.freelasatende.model.enums.MotivoAprendizado;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ControleOperacionalSummaryHelper {

    private ControleOperacionalSummaryHelper() {
    }

    static List<String> motivosAprendizado() {
        return List.of(
            MotivoAprendizado.BOM_TOM.name(),
            MotivoAprendizado.ESCOPO_CLARO.name(),
            MotivoAprendizado.ROBOTICO.name(),
            MotivoAprendizado.GENERICO.name(),
            MotivoAprendizado.RISCO_PLATAFORMA.name(),
            MotivoAprendizado.DUPLICADO.name(),
            MotivoAprendizado.PERGUNTAS_DEMAIS.name(),
            MotivoAprendizado.FORA_DO_TOM.name(),
            MotivoAprendizado.OUTRO.name()
        );
    }

    static Map<String, Object> qualidadeOperacional(List<AcaoControleDTO> pendencias) {
        Map<String, Object> resumo = new LinkedHashMap<>();
        if (pendencias == null || pendencias.isEmpty()) {
            resumo.put("scoreMedioFila", 100);
            resumo.put("pendenciasAlta", 0);
            resumo.put("pendenciasMedia", 0);
            resumo.put("pendenciasBloqueada", 0);
            resumo.put("decisaoAprovar", 0);
            resumo.put("decisaoReescrever", 0);
            resumo.put("decisaoBloquear", 0);
            resumo.put("pendenciasComRisco", 0);
            resumo.put("naturalidadeBaixa", 0);
            resumo.put("resumo", "Fila limpa e sem mensagens aguardando revisao.");
            return resumo;
        }

        int scoreTotal = 0;
        int scoresValidos = 0;
        int alta = 0;
        int media = 0;
        int bloqueada = 0;
        int aprovar = 0;
        int reescrever = 0;
        int bloquear = 0;
        int comRisco = 0;
        int naturalidadeBaixa = 0;

        for (AcaoControleDTO acao : pendencias) {
            if (acao.getScoreConfianca() != null) {
                scoreTotal += acao.getScoreConfianca();
                scoresValidos++;
            }

            String nivel = acao.getNivelConfianca() != null ? acao.getNivelConfianca().toUpperCase() : "";
            if ("ALTA".equals(nivel)) {
                alta++;
            } else if ("BLOQUEADA".equals(nivel)) {
                bloqueada++;
            } else {
                media++;
            }

            String decisao = acao.getDecisaoQualidade() != null ? acao.getDecisaoQualidade().toUpperCase() : "";
            if ("APROVAR".equals(decisao)) {
                aprovar++;
            } else if ("BLOQUEAR".equals(decisao)) {
                bloquear++;
            } else {
                reescrever++;
            }

            if (acao.getSinaisRisco() != null && !acao.getSinaisRisco().isEmpty()) {
                comRisco++;
            }
            if (acao.getScoreNaturalidade() != null && acao.getScoreNaturalidade() < 70) {
                naturalidadeBaixa++;
            }
        }

        int scoreMedio = scoresValidos == 0 ? 0 : Math.round((float) scoreTotal / scoresValidos);
        resumo.put("scoreMedioFila", scoreMedio);
        resumo.put("pendenciasAlta", alta);
        resumo.put("pendenciasMedia", media);
        resumo.put("pendenciasBloqueada", bloqueada);
        resumo.put("decisaoAprovar", aprovar);
        resumo.put("decisaoReescrever", reescrever);
        resumo.put("decisaoBloquear", bloquear);
        resumo.put("pendenciasComRisco", comRisco);
        resumo.put("naturalidadeBaixa", naturalidadeBaixa);
        resumo.put("resumo", resumoQualidade(scoreMedio, bloqueada, reescrever, comRisco));
        return resumo;
    }

    static String motivoOperacao(
        boolean sessaoValida,
        int errosHoje,
        int pendencias,
        int bloqueiosRecentes,
        boolean escritaRealHabilitada,
        boolean simulacaoEnvioHabilitada
    ) {
        if (!sessaoValida) {
            return "Sessao de marketplace invalida ou expirada";
        }
        if (!escritaRealHabilitada && simulacaoEnvioHabilitada) {
            return "Modo simulacao de envio: nenhuma mensagem real sera enviada";
        }
        if (!escritaRealHabilitada) {
            return "Modo leitura/ensino seguro: escrita real desabilitada por real.write.enabled=false";
        }
        if (errosHoje > 0) {
            return "Ha erros registrados hoje; revisar antes de operar em volume";
        }
        if (bloqueiosRecentes > 0) {
            return "Operacao segura, mas ha bloqueios recentes para revisar";
        }
        if (pendencias > 0) {
            return "Operacao segura com pendencias aguardando decisao";
        }
        return "Operacao segura no momento";
    }

    private static String resumoQualidade(int scoreMedio, int bloqueada, int reescrever, int comRisco) {
        if (bloqueada > 0) {
            return "Ha mensagens bloqueadas; revisar antes de aprovar qualquer envio.";
        }
        if (comRisco > 0 || reescrever > 0 || scoreMedio < 78) {
            return "Fila segura, mas com mensagens que merecem reescrita ou leitura cuidadosa.";
        }
        return "Fila com mensagens claras e naturais aguardando decisao humana.";
    }
}
