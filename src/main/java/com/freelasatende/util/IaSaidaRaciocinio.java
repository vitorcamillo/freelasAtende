package com.freelasatende.util;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Limpa a saida da IA removendo raciocinio interno do modelo.
 *
 * Modelos gratuitos via OpenRouter (raciocinio) devolvem no content blocos
 * <think>...</think> ou prosa de pensamento ("Okay, the client is asking..."),
 * e isso chegaria ao cliente como rascunho. Alem disso o max_tokens e
 * consumido pelo raciocinio e o content volta vazio ou cortado no meio de um
 * bloco aberto.
 */
public final class IaSaidaRaciocinio {

    private static final String TAGS = "think|thinking|reasoning|reflection";

    /** Blocos fechados: removidos por inteiro. */
    private static final Pattern BLOCOS_FECHADOS = Pattern.compile("(?is)<(" + TAGS + ")>.*?</\\1>");

    /** Fechamento orfao (sem abertura): raciocinio terminou, mensagem final vem depois. */
    private static final Pattern FECHAMENTO_ORFAO = Pattern.compile("(?i)</(" + TAGS + ")>");

    /** Abertura sem fechamento: texto cortado no meio do raciocinio. */
    private static final Pattern ABERTURA_ORFA = Pattern.compile("(?i)<(" + TAGS + ")>");

    private static final Pattern[] INICIOS_RACIOCINIO = {
        // "okay" so e raciocinio com segundo sinal em ingles ("Okay, posso fazer sim" e legitimo)
        Pattern.compile("^okay[,.]\\s+(the|i|let|we|so|first|here)\\b"),
        Pattern.compile("^alright[,.]"),
        Pattern.compile("^let me "),
        Pattern.compile("^we need to "),
        Pattern.compile("^i need to "),
        Pattern.compile("^the (user|client) (is asking|is requesting|wants|asked|said)"),
        Pattern.compile("^o cliente (esta perguntando|esta querendo|quer|pediu|disse)"),
        Pattern.compile("^here is a draft"),
        Pattern.compile("^first, i "),
        Pattern.compile("^preciso responder")
    };

    private static final Pattern[] TRECHOS_RACIOCINIO = {
        Pattern.compile("the user (wants|asked|said|is asking|is requesting|is looking)"),
        Pattern.compile("the client (wants|asked|said|is asking|is requesting|is looking)"),
        Pattern.compile("o cliente (quer|pediu|disse|esta perguntando|esta pedindo|esta querendo|esta solicitando) "),
        Pattern.compile("(my|the) response should"),
        Pattern.compile("a resposta deve"),
        Pattern.compile("draft (a|the) (reply|response)")
    };

    private IaSaidaRaciocinio() {
    }

    /**
     * Remove blocos de raciocinio do texto devolvido pelo modelo:
     * pares fechados por inteiro; fechamento orfao descarta tudo ate a ultima
     * tag (inclusive); abertura sem fechamento descarta da tag ate o fim.
     * Retorna trim() do resultado (pode ficar vazio).
     */
    public static String removerBlocos(String texto) {
        if (texto == null) {
            return null;
        }
        String atual = BLOCOS_FECHADOS.matcher(texto).replaceAll("");

        int fimUltimoFechamento = -1;
        Matcher fechamentos = FECHAMENTO_ORFAO.matcher(atual);
        while (fechamentos.find()) {
            fimUltimoFechamento = fechamentos.end();
        }
        if (fimUltimoFechamento >= 0) {
            atual = atual.substring(fimUltimoFechamento);
        } else {
            Matcher abertura = ABERTURA_ORFA.matcher(atual);
            if (abertura.find()) {
                atual = atual.substring(0, abertura.start());
            }
        }
        return atual.trim();
    }

    /**
     * Heuristica de prosa de raciocinio sem tags: o modelo pensou em voz alta
     * em vez de devolver a mensagem final. Falso positivo esperado aqui e
     * seguranca: o texto nunca vai ao cliente como rascunho.
     */
    public static boolean pareceRaciocinio(String texto) {
        if (texto == null || texto.isBlank()) {
            return false;
        }
        String normalizado = normalizar(texto);
        for (Pattern inicio : INICIOS_RACIOCINIO) {
            if (inicio.matcher(normalizado).find()) {
                return true;
            }
        }
        // Um trecho isolado aparece em resposta tecnica legitima
        // ("the user is redirected...", "a resposta deve vir em JSON"):
        // so marca raciocinio com 2 ou mais sinais distintos.
        int sinais = 0;
        for (Pattern trecho : TRECHOS_RACIOCINIO) {
            if (trecho.matcher(normalizado).find()) {
                sinais++;
            }
        }
        return sinais >= 2;
    }

    private static String normalizar(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase()
            .trim();
    }
}
