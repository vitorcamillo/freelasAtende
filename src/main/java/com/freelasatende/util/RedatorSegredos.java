package com.freelasatende.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Redator unico de segredos para textos que chegam ao painel operacional
 * (GET /api/dashboard/logs, stream SSE /logs/stream e /errors/recent).
 * Mascara pares chave=valor de token/senha/password/cookie/apiKey,
 * "Authorization: Bearer ..." e chaves estilo sk-..., trocando o valor por ***.
 *
 * <p>Os pares chave=valor sao reconhecidos por uma varredura linear (cada caractere
 * visto no maximo duas vezes), sem regex com retrocesso: as versoes por regex
 * estouravam a pilha ou ficavam quadraticas com texto adversarial longo.</p>
 */
public final class RedatorSegredos {

    private static final String MASCARA = "***";

    private static final String[] TERMOS_SENSIVEIS = {
        "token", "senha", "password", "secret", "cookie", "authorization", "apikey", "api_key", "api-key"
    };

    /**
     * "Bearer <valor>" solto, com ou sem prefixo "Authorization:". Roda depois dos pares
     * chave=valor (que ja mascaram o valor citado inteiro, inclusive com aspa escapada).
     * O valor para em aspa, virgula ou ponto e virgula para nao desalinhar o texto seguinte.
     */
    private static final Pattern BEARER = Pattern.compile(
        "(?i)(authorization\\s*+:\\s*+)?bearer\\s++[^\\s\"',;]+");

    /** Chaves de API estilo OpenAI/compativeis em texto livre. */
    private static final Pattern CHAVE_SK = Pattern.compile("sk-[A-Za-z0-9_-]{8,}");

    private RedatorSegredos() {
    }

    public static String mascarar(String texto) {
        if (texto == null || texto.isEmpty()) {
            return texto;
        }
        String resultado = mascararPares(texto);
        resultado = BEARER.matcher(resultado).replaceAll("$1" + MASCARA);
        return CHAVE_SK.matcher(resultado).replaceAll(MASCARA);
    }

    /**
     * Varre o texto procurando "chave = valor" / "chave: valor" / "chave": "valor".
     * Chave sensivel: troca o valor por ***. Cookie sem aspas mascara ate o fim da linha
     * (todos os pares do cabecalho); cookie entre aspas em JSON (seguido de , } ou ])
     * mascara so o valor citado. Chave comum: segue a varredura logo depois do nome,
     * sem consumir o valor (um par sensivel dentro dele ainda e encontrado).
     */
    private static String mascararPares(String s) {
        int n = s.length();
        StringBuilder saida = new StringBuilder(n);
        int i = 0;
        while (i < n) {
            if (!ehCaractereDeChave(s.charAt(i)) || (i > 0 && ehCaractereDeChave(s.charAt(i - 1)))) {
                saida.append(s.charAt(i++));
                continue;
            }
            int fimChave = i;
            while (fimChave < n && ehCaractereDeChave(s.charAt(fimChave))) {
                fimChave++;
            }
            String chave = s.substring(i, fimChave);
            int inicioValor = inicioDoValor(s, fimChave);
            if (inicioValor < 0 || !ehSensivel(chave)) {
                saida.append(chave);
                i = fimChave;
                continue;
            }
            // Cookie e Authorization ("Basic xyz", "Bearer xyz") sao cabecalhos: o valor e a linha.
            String minuscula = chave.toLowerCase(Locale.ROOT);
            boolean cabecalho = minuscula.contains("cookie") || minuscula.contains("authorization");
            saida.append(cabecalho ? s.substring(i, inicioValor) : chave + "=").append(MASCARA);
            i = fimDoValor(s, inicioValor, cabecalho);
        }
        return saida.toString();
    }

    /**
     * Posicao do valor depois de [aspa] espacos (: ou =) espacos; -1 sem separador. A aspa
     * pode vir escapada (JSON dentro de string de log: \"token\": \"valor\").
     */
    private static int inicioDoValor(String s, int j) {
        int n = s.length();
        if (j + 1 < n && s.charAt(j) == '\\' && ehAspa(s.charAt(j + 1))) {
            j += 2;
        } else if (j < n && ehAspa(s.charAt(j))) {
            j++;
        }
        while (j < n && Character.isWhitespace(s.charAt(j))) {
            j++;
        }
        if (j >= n || (s.charAt(j) != ':' && s.charAt(j) != '=')) {
            return -1;
        }
        j++;
        while (j < n && Character.isWhitespace(s.charAt(j))) {
            j++;
        }
        return j;
    }

    private static int fimDoValor(String s, int j, boolean cookie) {
        int n = s.length();
        if (j < n && (ehAspa(s.charAt(j)) || (j + 1 < n && s.charAt(j) == '\\' && ehAspa(s.charAt(j + 1))))) {
            int fechamento = fimDoValorCitado(s, j);
            if (fechamento < 0) {
                // Aspa sem fechamento na linha (conteudo truncado): mascara conservadoramente a linha.
                return fimDaLinha(s, j);
            }
            if (!cookie || seguidoDeFimDeCampoJson(s, fechamento)) {
                return fechamento;
            }
        }
        if (cookie) {
            return fimDaLinha(s, j);
        }
        int k = fimDoTermo(s, j);
        if (s.regionMatches(true, j, "bearer", 0, 6) && k == j + 6) {
            // "token=Bearer abc": o segredo e o termo seguinte ao "Bearer".
            int depois = k;
            while (depois < n && Character.isWhitespace(s.charAt(depois)) && s.charAt(depois) != '\n') {
                depois++;
            }
            k = fimDoTermo(s, depois);
        }
        return k;
    }

    /**
     * Posicao logo apos a aspa que fecha o valor citado em j (aspa simples, dupla ou
     * escapada \\"), ou -1 se nao fecha na mesma linha. Uma sequencia de L barras seguida
     * da aspa fecha o valor se L for par no valor comum, ou se L == 1 no valor escapado
     * (JSON dentro de string: a aspa interna vira \\\\\\" e nao fecha). A busca para na
     * quebra de linha: varrer o resto do texto deixava o custo quadratico.
     */
    private static int fimDoValorCitado(String s, int j) {
        boolean escapada = s.charAt(j) == '\\';
        char aspa = s.charAt(escapada ? j + 1 : j);
        int n = s.length();
        int k = escapada ? j + 2 : j + 1;
        while (k < n && s.charAt(k) != '\n' && s.charAt(k) != '\r') {
            int barras = 0;
            while (k + barras < n && s.charAt(k + barras) == '\\') {
                barras++;
            }
            int depois = k + barras;
            if (depois < n && s.charAt(depois) == aspa) {
                boolean fecha = escapada ? barras == 1 : barras % 2 == 0;
                if (fecha) {
                    return depois + 1;
                }
                k = depois + 1;
            } else {
                k = Math.max(depois, k + 1);
            }
        }
        return -1;
    }

    private static boolean seguidoDeFimDeCampoJson(String s, int k) {
        while (k < s.length() && Character.isWhitespace(s.charAt(k))) {
            k++;
        }
        return k < s.length() && (s.charAt(k) == ',' || s.charAt(k) == '}' || s.charAt(k) == ']');
    }

    private static int fimDaLinha(String s, int k) {
        while (k < s.length() && s.charAt(k) != '\n' && s.charAt(k) != '\r') {
            k++;
        }
        return k;
    }

    /**
     * Fim de um valor sem aspas: ate o proximo espaco, mas um trecho citado no caminho
     * ({"token":123,"password":"a b"}) e pulado inteiro, para o espaco dentro dele nao
     * cortar a mascara no meio do segredo seguinte. Mascara a mais, nunca a menos.
     */
    private static int fimDoTermo(String s, int k) {
        int n = s.length();
        while (k < n && !Character.isWhitespace(s.charAt(k))) {
            if (ehAspa(s.charAt(k)) || (s.charAt(k) == '\\' && k + 1 < n && ehAspa(s.charAt(k + 1)))) {
                int fechamento = fimDoValorCitado(s, k);
                if (fechamento < 0) {
                    return fimDaLinha(s, k);
                }
                k = fechamento;
            } else {
                k++;
            }
        }
        return k;
    }

    private static boolean ehAspa(char c) {
        return c == '"' || c == '\'';
    }

    private static boolean ehCaractereDeChave(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-';
    }

    private static boolean ehSensivel(String chave) {
        String minuscula = chave.toLowerCase(Locale.ROOT);
        for (String termo : TERMOS_SENSIVEIS) {
            if (minuscula.contains(termo)) {
                return true;
            }
        }
        return false;
    }
}
