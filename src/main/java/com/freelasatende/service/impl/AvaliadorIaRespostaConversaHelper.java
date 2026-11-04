package com.freelasatende.service.impl;

import com.freelasatende.config.IaConfig;
import com.freelasatende.model.dto.QualidadeRascunhoDTO;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.service.interfaceServico.IaProvider;
import com.freelasatende.service.interfaceServico.QualidadeRascunhoService;
import com.freelasatende.util.IaSafetyPolicy;
import com.freelasatende.util.IaSaidaRaciocinio;
import com.freelasatende.util.RetryUtils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class AvaliadorIaRespostaConversaHelper {

    private static final int LIMITE_RESPOSTA_CONVERSA = 700;
    private static final int MAX_TENTATIVAS_RESPOSTA_CONVERSA_VALIDA = 5;
    // Modelos com raciocinio gastam o limite pensando e devolvem content vazio.
    private static final int MAX_TOKENS_RESPOSTA_CONVERSA = 6000;

    private AvaliadorIaRespostaConversaHelper() {
    }

    static final class RespostaConversaReprovadaException extends IllegalStateException {
        private final String resposta;

        RespostaConversaReprovadaException(String resposta, String motivo) {
            super(motivo);
            this.resposta = resposta;
        }

        String getResposta() {
            return resposta;
        }
    }

    static String gerarRespostaValida(
        IaProvider iaProvider,
        IaConfig iaConfig,
        IaSafetyPolicy iaSafetyPolicy,
        QualidadeRascunhoService qualidadeRascunhoService,
        Projeto projeto,
        String promptBase
    ) throws Exception {
        RuntimeException ultimaFalha = null;
        for (int tentativa = 1; tentativa <= MAX_TENTATIVAS_RESPOSTA_CONVERSA_VALIDA; tentativa++) {
            String prompt = promptComCorrecao(promptBase, tentativa, ultimaFalha);
            String resposta = RetryUtils.comRetryChecked(
                () -> iaProvider.enviar(prompt, MAX_TOKENS_RESPOSTA_CONVERSA),
                iaConfig.getMaxRetries(),
                "resposta-conversa-" + (projeto != null ? projeto.getId() : "sem-projeto") + "-tentativa-" + tentativa
            );

            try {
                return validar(resposta, iaSafetyPolicy, qualidadeRascunhoService);
            } catch (RuntimeException e) {
                ultimaFalha = e;
                if (tentativa < MAX_TENTATIVAS_RESPOSTA_CONVERSA_VALIDA) {
                    log.warn(
                        "Resposta de conversa invalida na tentativa {}/{}; solicitando nova geracao: {}",
                        tentativa,
                        MAX_TENTATIVAS_RESPOSTA_CONVERSA_VALIDA,
                        e.getMessage()
                    );
                }
            }
        }
        throw ultimaFalha != null
            ? ultimaFalha
            : new IllegalStateException("IA nao gerou resposta de conversa valida.");
    }

    static boolean falhaEsperada(RuntimeException e) {
        String mensagem = e.getMessage();
        return mensagem != null && (mensagem.startsWith("IA gerou resposta de conversa ")
            || mensagem.startsWith("IA retornou resposta de conversa vazia"));
    }

    private static String validar(
        String resposta,
        IaSafetyPolicy iaSafetyPolicy,
        QualidadeRascunhoService qualidadeRascunhoService
    ) {
        resposta = IaSaidaRaciocinio.removerBlocos(resposta);
        if (resposta == null || resposta.isBlank()) {
            throw new IllegalStateException("IA retornou resposta de conversa vazia.");
        }
        if (IaSaidaRaciocinio.pareceRaciocinio(resposta)) {
            throw new IllegalStateException("IA gerou resposta de conversa com raciocinio interno em vez da mensagem final.");
        }

        String saneada = iaSafetyPolicy.sanitize(resposta, LIMITE_RESPOSTA_CONVERSA);
        if (saneada == null || saneada.isBlank()) {
            throw new IllegalStateException("IA gerou resposta de conversa bloqueada pela politica de seguranca.");
        }
        if (AvaliadorIaRuntimeHelper.ehRespostaNegativaCurta(saneada)
            || saneada.length() < 12
            || fechamentoPrecoceDeEscopo(saneada)) {
            throw new RespostaConversaReprovadaException(
                saneada,
                "IA gerou resposta de conversa reprovada pela qualidade: regra=conversa_invalida."
            );
        }
        validarQualidade(qualidadeRascunhoService, saneada);
        return saneada;
    }

    private static boolean fechamentoPrecoceDeEscopo(String resposta) {
        String texto = java.text.Normalizer.normalize(resposta, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase();
        return texto.contains("ja da para fechar")
            || texto.contains("ja da pra fechar")
            || texto.contains("base do escopo")
            || texto.contains("fechar a base")
            || texto.contains("avancar na proxima etapa")
            || texto.contains("monto a proposta")
            || texto.contains("passar uma proposta")
            || texto.contains("organizar prazo e valor");
    }

    private static void validarQualidade(QualidadeRascunhoService qualidadeRascunhoService, String texto) {
        QualidadeRascunhoDTO qualidade = qualidadeRascunhoService.avaliar(texto);
        if (qualidade != null && "APROVAR".equalsIgnoreCase(qualidade.getDecisaoRecomendada())) {
            return;
        }
        throw new RespostaConversaReprovadaException(
            texto,
            "IA gerou resposta de conversa reprovada pela qualidade: " + diagnosticoQualidade(qualidade)
        );
    }

    private static String diagnosticoQualidade(QualidadeRascunhoDTO qualidade) {
        if (qualidade == null) {
            return "qualidade=indisponivel";
        }
        return "decisao=" + valor(qualidade.getDecisaoRecomendada())
            + "; comentario=" + valor(qualidade.getComentario())
            + "; sinais=" + valor(qualidade.getSinais())
            + "; scores={naturalidade=" + valor(qualidade.getScoreNaturalidade())
            + ", clareza=" + valor(qualidade.getScoreClareza())
            + ", risco=" + valor(qualidade.getScoreRisco())
            + ", duplicidade=" + valor(qualidade.getScoreDuplicidade())
            + ", confianca=" + valor(qualidade.getScoreConfianca()) + "}";
    }

    private static String valor(Object valor) {
        return valor != null ? valor.toString() : "null";
    }

    private static String promptComCorrecao(String promptBase, int tentativa, RuntimeException falhaAnterior) {
        if (tentativa <= 1 || falhaAnterior == null) {
            return promptBase;
        }
        String motivo = falhaAnterior.getMessage() != null ? falhaAnterior.getMessage() : falhaAnterior.getClass().getSimpleName();
        return promptBase + String.format("""

            A tentativa anterior foi descartada pelo sistema: %s

            Gere uma NOVA mensagem final real para o cliente agora.
            Obrigatorio:
            - Nao retorne vazio
            - Nao use placeholder
            - Nao diga que precisa reescrever ou ensinar
            - Nao explique a falha
            - Responda somente com o texto que pode ir para o cliente
            """, motivo);
    }
}
