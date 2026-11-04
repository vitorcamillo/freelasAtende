package com.freelasatende.service.impl;

import com.freelasatende.config.IaConfig;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.dto.QualidadeRascunhoDTO;
import com.freelasatende.service.interfaceServico.IaProvider;
import com.freelasatende.service.interfaceServico.QualidadeRascunhoService;
import com.freelasatende.util.IaSafetyPolicy;
import com.freelasatende.util.RetryUtils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class AvaliadorIaPropostaHelper {

    private static final int LIMITE_PROPOSTA = 900;
    private static final int MAX_TENTATIVAS_PROPOSTA_VALIDA = 3;

    private AvaliadorIaPropostaHelper() {
    }

    static String gerarTextoValido(
        IaProvider iaProvider,
        IaConfig iaConfig,
        IaSafetyPolicy iaSafetyPolicy,
        QualidadeRascunhoService qualidadeRascunhoService,
        Projeto projeto,
        String promptBase
    ) throws Exception {
        RuntimeException ultimaFalha = null;
        String ultimoTextoSano = null;
        for (int tentativa = 1; tentativa <= MAX_TENTATIVAS_PROPOSTA_VALIDA; tentativa++) {
            String prompt = promptComCorrecao(promptBase, tentativa, ultimaFalha);
            String resposta = RetryUtils.comRetryChecked(
                // Modelos de raciocinio (glm-5.3-flash, Gemini free) gastam o
                // max_tokens com reasoning e devolvem content vazio; medido em
                // producao em 17/09 com orcamento de 700. O excedente nao e
                // cobrado: a API cobra pelo token realmente usado.
                () -> iaProvider.enviar(prompt, 3000),
                iaConfig.getMaxRetries(),
                "gerar-proposta-" + (projeto != null ? projeto.getId() : "sem-projeto") + "-tentativa-" + tentativa
            );

            try {
                return validar(resposta, iaSafetyPolicy, qualidadeRascunhoService);
            } catch (RuntimeException e) {
                ultimaFalha = e;
                if (resposta != null && !resposta.isBlank()) {
                    String saneada = iaSafetyPolicy.sanitize(resposta, LIMITE_PROPOSTA);
                    if (saneada != null && !saneada.isBlank()) {
                        ultimoTextoSano = saneada;
                    }
                }
                if (tentativa < MAX_TENTATIVAS_PROPOSTA_VALIDA) {
                    log.warn(
                        "Texto de proposta invalido na tentativa {}/{}; solicitando nova geracao: {}",
                        tentativa,
                        MAX_TENTATIVAS_PROPOSTA_VALIDA,
                        e.getMessage()
                    );
                }
            }
        }
        // Nao perder o projeto inteiro porque o modelo falhou na redacao: com texto
        // sanizado disponivel, entrega a melhor versao gerada em vez de propagar erro.
        if (ultimoTextoSano != null && !ultimoTextoSano.isBlank()) {
            log.warn("Usando melhor rascunho de proposta apos esgotar tentativas de geracao ideal.");
            return ultimoTextoSano;
        }
        throw ultimaFalha != null
            ? ultimaFalha
            : new IllegalStateException("IA nao gerou texto de proposta valido.");
    }

    private static String validar(
        String resposta,
        IaSafetyPolicy iaSafetyPolicy,
        QualidadeRascunhoService qualidadeRascunhoService
    ) {
        if (resposta == null || resposta.isBlank()) {
            throw new IllegalStateException("IA retornou texto de proposta vazio.");
        }

        String saneada = iaSafetyPolicy.sanitize(resposta, LIMITE_PROPOSTA);
        if (saneada == null || saneada.isBlank()) {
            throw new IllegalStateException("IA gerou texto de proposta bloqueado pela politica de seguranca.");
        }
        if (AvaliadorIaRuntimeHelper.textoPropostaInvalido(saneada)) {
            throw new IllegalStateException("IA gerou texto de proposta reprovado pela qualidade.");
        }
        // Repassa os sinais do juiz para a proxima tentativa saber o que corrigir,
        // em vez de receber apenas "reprovado pela qualidade".
        QualidadeRascunhoDTO qualidade = qualidadeRascunhoService.avaliar(saneada);
        if (!"APROVAR".equalsIgnoreCase(qualidade.getDecisaoRecomendada())) {
            String sinais = qualidade.getSinais() != null && !qualidade.getSinais().isEmpty()
                ? String.join(", ", qualidade.getSinais())
                : "nao especificado";
            throw new IllegalStateException(
                "IA gerou texto de proposta reprovado pela qualidade (sinais: " + sinais + ")."
            );
        }
        if (AvaliadorIaRuntimeHelper.contemCaracteresCjk(saneada)) {
            throw new IllegalStateException("IA gerou texto de proposta com caracteres de outro idioma (CJK).");
        }
        return saneada;
    }

    private static String promptComCorrecao(String promptBase, int tentativa, RuntimeException falhaAnterior) {
        if (tentativa <= 1 || falhaAnterior == null) {
            return promptBase;
        }
        String motivo = falhaAnterior.getMessage() != null ? falhaAnterior.getMessage() : falhaAnterior.getClass().getSimpleName();
        return promptBase + String.format("""

            A tentativa anterior foi descartada pelo sistema: %s

            Gere uma NOVA proposta final real para o cliente agora.
            Obrigatorio:
            - Nao retorne vazio
            - Se o motivo citar "texto_longo" ou "texto_longo_demais", escreva bem mais curto, alvo de 350 caracteres
            - Se o motivo citar "ideia_repetida", diga cada coisa uma unica vez e remova parafrase do que ja foi dito
            - Nao use lista, placeholder, saudacao formal ou texto de template
            - Nao fale de desconto, novo valor, prazo fechado ou contato fora da plataforma
            - Nao explique a falha
            - Responda somente com o texto que pode ir para o cliente
            """, motivo);
    }
}
