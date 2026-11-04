package com.freelasatende.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Politica de seguranca para respostas geradas por IA.
 * Bloqueia conteudos de risco.
 *
 * ATENCAO: saida ao cliente precisa ser conservadora. Qualquer tentativa de
 * levar contato/pagamento para fora da plataforma deve ser bloqueada.
 */
@Slf4j
@Service
public class IaSafetyPolicy {

    /**
     * Padroes que indicam tentativa de passar contato pessoal (BLOQUEAR).
     * Apenas bloqueia quando ha sinais de intento de contato direto.
     */
    private static final List<Pattern> BLOQUEIOS_CONTATO = List.of(
        Pattern.compile("(?i)(telefone|celular|cel|numero|contato).{0,20}(\\(?\\d{2}\\)?[\\s.-]*)?9?[\\s.-]*\\d{4}[\\s.-]*\\d{4}"),
        Pattern.compile("(?i)\\b(\\(?\\d{2}\\)?[\\s.-]*)?9[\\s.-]*\\d{4}[\\s.-]*\\d{4}\\b"),
        Pattern.compile("(?i)(meu|minha)?\\s*zap\\s*[:.]?\\s*\\d"),
        Pattern.compile("(?i)numero\\s+(do|de)?\\s*(zap|whatsapp|telefone|whats)\\s*[:.]?\\s*\\d"),
        Pattern.compile("(?i)(meu|minha)?\\s*(e-?mail|email|gmail|hotmail|outlook)\\s*[:.]?\\s*\\w+@"),
        Pattern.compile("(?i)[a-z0-9._%+-]+\\s*@\\s*[a-z0-9.-]+\\s*\\.\\s*[a-z]{2,}"),
        Pattern.compile("(?i)(manda|mande)\\s+(um\\s+)?(zap|whats|wpp|inbox)"),
        Pattern.compile("(?i)(me\\s+)?(chama|contacta|contata)\\s+(no|na)\\s+(zap|whats|wpp|telegram|instagram)"),
        Pattern.compile("(?i)(whats|zap|wpp)\\s+(aqui|pra\\s+mim|no\\s+meu)\\s*[:.]?\\s*\\d"),
        Pattern.compile("(?i)(pagar|fechar|transferir|depositar)\\s+(por|via|no|na)\\s+(pix|transferencia|deposito)"),
        Pattern.compile("(?i)(pagar|fechar|combinar|acertar).{0,40}fora\\s+da\\s+plataforma"),
        Pattern.compile("(?i)\\bpor\\s+fora\\b"),
        Pattern.compile("(?i)economizar\\s+taxa"),
        Pattern.compile("(?i)(meu|minha)?\\s*pix\\s*[:.]?\\s*\\d"),
        Pattern.compile("(?i)100\\s*%"),
        Pattern.compile("(?i)garantid[ao]"),
        Pattern.compile("(?i)sem\\s+risco"),
        Pattern.compile("(?i)resultado\\s+garant"),
        Pattern.compile("(?i)ja\\s+analisei\\s+seu\\s+(codigo|sistema|banco)"),
        Pattern.compile("(?i)(ignore|desconsidere|esqueca|esqueça).{0,40}(instruc|regra|politica|política)"),
        Pattern.compile("(?i)regra\\s+nova\\s*:"),
        // "me liga no celular/telefone/numero" mesmo sem numero na sequencia
        Pattern.compile("(?i)(me\\s+)?(liga|ligue|ligar|chama|chame)\\s+(no|pelo|para\\s+o|pro)\\s+(meu\\s+)?(celular|telefone|numero)"),
        // pedido explicito de contato pessoal ("manda seu telefone", "me passe seu email")
        Pattern.compile("(?i)(manda|mande|mandar|me\\s+(passe|passa|passar|envia|envie|enviar))\\s+(seu|o|a)\\s+(telefone|celular|numero|whats|zap|e-?mail)"),
        Pattern.compile("(?i)(qual|fala|informa|passar?)\\s+(o\\s+)?seu\\s+(telefone|celular|numero|whats|zap)"),
        // "fazer transferencia direta" (sem "pagamento direto", comum em integracao Stripe/gateway)
        Pattern.compile("(?i)(transferencia|deposito)\\s+diret[oa]"),
        // transferencia/deposito perto de conta bancaria/pagamento (ex.: "pagamento por transferencia para minha conta")
        Pattern.compile("(?i)(transferencia|deposito).{0,30}((minha|nossa|sua)\\s+conta|conta\\s+bancari[ao]|banco|pagamento)"),
        Pattern.compile("(?i)(pagamento|conta\\s+bancari[ao]|(minha|nossa|sua)\\s+conta|banco).{0,30}(transferencia|deposito)")
    );

    /**
     * Tokens de canal externo. Casam no texto normalizado aceitando separadores
     * entre as letras (obfuscacao), mas exigem que o token nao esteja grudado em
     * outra letra/digito. O apostrofo NAO e separador ("what's" nao casa "whats")
     * e palavras como "pixel" nao casam "pix".
     */
    private static final List<Pattern> CANAIS_EXTERNOS = Stream.of(
        "whatsapp", "whats", "zap", "wpp", "pix", "gmail", "discord", "telegram"
    ).map(IaSafetyPolicy::padraoTokenCanal).toList();

    /**
     * Tokens longos e inequivocos continuam bloqueando tambem quando aparecem
     * embutidos no texto colado (ex.: "xxwhatsappyy", "w.h.a.t.s.a.p.p").
     */
    private static final List<String> CANAIS_EMBUTIDOS = List.of(
        "whatsapp", "telegram", "discord", "gmail", "zapzap", "whatsap", "whatszap", "zapp"
    );

    /**
     * Constroi o padrao do canal externo: letras do token unidas por qualquer
     * caractere que nao seja letra, digito nem apostrofo, com lookaround
     * exigindo que nao haja letra colada (digito colado BLOQUEIA).
     */
    private static Pattern padraoTokenCanal(String token) {
        StringBuilder letras = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            if (i > 0) {
                letras.append("[^a-z0-9']*");
            }
            letras.append(token.charAt(i));
        }
        return Pattern.compile("(?<![a-z])" + letras + "(?![a-z])");
    }

    /**
     * Remove apostrofo apenas entre letras isoladas ("z'a'p" -&gt; "zap").
     * Apostrofo de contracao e mantido ("what's" continua nao casando "whats").
     */
    private static String removerApostrofosEntreLetrasIsoladas(String texto) {
        StringBuilder resultado = new StringBuilder(texto.length());
        for (int i = 0; i < texto.length(); i++) {
            char atual = texto.charAt(i);
            if (atual == '\'' && sequenciaDeLetras(texto, i - 1, -1) == 1 && sequenciaDeLetras(texto, i + 1, 1) == 1) {
                continue;
            }
            resultado.append(atual);
        }
        return resultado.toString();
    }

    private static int sequenciaDeLetras(String texto, int inicio, int passo) {
        int total = 0;
        for (int i = inicio; i >= 0 && i < texto.length() && Character.isLetter(texto.charAt(i)); i += passo) {
            total++;
        }
        return total;
    }

    public static String sanitize(String texto, int limite) {
        String limpo = limparEspacos(texto);
        if (limpo.isBlank()) {
            return "";
        }
        if (containsUnsafeContent(limpo)) {
            log.debug("Saida da IA bloqueada por politica de seguranca");
            return "";
        }
        if (limpo.length() > limite) {
            return truncarSemCortarFrase(limpo, limite);
        }
        return limpo;
    }

    /**
     * Corta o texto respeitando fronteira de frase, e em ultimo caso de palavra.
     * Evita entregar ao cliente mensagem terminando no meio de uma palavra.
     */
    private static String truncarSemCortarFrase(String texto, int limite) {
        String janela = texto.substring(0, limite);
        // exige aproveitar a maior parte do limite para nao devolver texto curto demais
        int minimoAceitavel = (limite * 6) / 10;

        int fimFrase = ultimoFimDeFrase(janela);
        if (fimFrase >= minimoAceitavel) {
            return janela.substring(0, fimFrase + 1).trim();
        }
        int ultimoEspaco = janela.lastIndexOf(' ');
        if (ultimoEspaco >= minimoAceitavel) {
            return janela.substring(0, ultimoEspaco).trim();
        }
        return janela.trim();
    }

    /**
     * Ultimo ponto/exclamacao/interrogacao que realmente encerra frase.
     * Ignora ponto interno de numero (ex.: "R$ 1.500") exigindo espaco depois.
     */
    private static int ultimoFimDeFrase(String texto) {
        for (int i = texto.length() - 1; i >= 0; i--) {
            char atual = texto.charAt(i);
            if (atual != '.' && atual != '!' && atual != '?') {
                continue;
            }
            boolean fimDaJanela = i == texto.length() - 1;
            if (fimDaJanela || texto.charAt(i + 1) == ' ') {
                return i;
            }
        }
        return -1;
    }

    public static boolean containsUnsafeContent(String texto) {
        String normalizado = normalize(texto);
        if (normalizado.isBlank()) {
            return false;
        }
        String colado = normalizado.replaceAll("[^a-z0-9]", "");
        String semApostrofoObfuscado = removerApostrofosEntreLetrasIsoladas(normalizado);
        for (Pattern padrao : CANAIS_EXTERNOS) {
            if (padrao.matcher(normalizado).find() || padrao.matcher(semApostrofoObfuscado).find()) {
                return true;
            }
        }
        for (String token : CANAIS_EMBUTIDOS) {
            if (colado.contains(token)) {
                return true;
            }
        }
        for (Pattern padrao : BLOQUEIOS_CONTATO) {
            if (padrao.matcher(normalizado).find() || padrao.matcher(colado).find()) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String texto) {
        if (texto == null) {
            return "";
        }
        String semAcento = Normalizer.normalize(texto, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");
        return semAcento.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private static String limparEspacos(String texto) {
        if (texto == null) {
            return "";
        }
        return texto.replaceAll("\\s+", " ").trim();
    }
}
