package com.freelasatende.service.impl;

import java.text.Normalizer;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reconhece o aviso em que o marketplace libera os dados de contato do cliente.
 *
 * <p>Freelancer manda isso como mensagem de sistema quando o empregador nao
 * responde ao interesse: "Portanto divulgamos agora os dados de contato do
 * empregador. Se desejar, voce pode conta-los diretamente: Nome do cliente...".
 * Como o fluxo de conversa descarta mensagem de sistema, esse dado -- que vale
 * contato direto com o cliente -- sumia sem ninguem ver.</p>
 *
 * <p>A extracao e tolerante de proposito: mesmo quando um campo nao casa, o
 * texto integral vai junto no alerta, entao o operador nunca perde o contato
 * por causa de uma variacao de formato.</p>
 */
final class ContatoLiberadoDetector {

    record ContatoLiberado(String nome, String email, String telefone, String textoIntegral) {
        boolean temAlgumDado() {
            return !email.isBlank() || !telefone.isBlank() || !nome.isBlank();
        }
    }

    private static final List<String> GATILHOS = List.of(
        "divulgamos agora os dados de contato",
        "dados de contato do empregador",
        "dados de contato do cliente",
        "voce pode conta-los diretamente",
        "pode contata-los diretamente",
        "releasing the contact details",
        "contact details of the employer",
        "you may contact them directly",
        "you can contact them directly"
    );

    private static final Pattern EMAIL = Pattern.compile(
        "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private static final Pattern TELEFONE = Pattern.compile(
        "(?:\\+?\\d{1,3}[\\s.-]?)?(?:\\(?\\d{2,3}\\)?[\\s.-]?)?\\d{4,5}[\\s.-]?\\d{4}");

    private static final Pattern NOME = Pattern.compile(
        "(?i)nome\\s+(?:do\\s+)?(?:cliente|contato|empregador|usuario)?\\s*[:\\-]\\s*([^\\n|;]{2,80})");

    private static final Pattern NOME_EN = Pattern.compile(
        "(?i)(?:contact\\s+)?name\\s*[:\\-]\\s*([^\\n|;]{2,80})");

    private ContatoLiberadoDetector() {
    }

    static Optional<ContatoLiberado> detectar(String texto) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        String normalizado = normalizar(texto);
        boolean gatilho = GATILHOS.stream().anyMatch(normalizado::contains);
        if (!gatilho) {
            return Optional.empty();
        }
        ContatoLiberado contato = new ContatoLiberado(
            primeiroGrupo(NOME, texto).or(() -> primeiroGrupo(NOME_EN, texto)).orElse(""),
            primeiraOcorrencia(EMAIL, texto),
            telefoneValido(texto),
            texto.trim()
        );
        return Optional.of(contato);
    }

    private static String normalizar(String texto) {
        String semAcento = Normalizer.normalize(texto, Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return semAcento.toLowerCase().replaceAll("\\s+", " ");
    }

    private static Optional<String> primeiroGrupo(Pattern padrao, String texto) {
        Matcher m = padrao.matcher(texto);
        if (!m.find()) {
            return Optional.empty();
        }
        String valor = cortarNoProximoRotulo(m.group(1)).trim();
        return valor.isBlank() ? Optional.empty() : Optional.of(valor);
    }

    /**
     * O aviso costuma vir em linha unica ("Nome: X E-mail: y Telefone: z"), entao
     * o nome precisa parar no proximo rotulo, senao engole o resto da mensagem.
     */
    private static String cortarNoProximoRotulo(String valor) {
        String[] rotulos = {"e-mail", "email", "telefone", "phone", "celular", "whatsapp", "skype"};
        String minusculo = valor.toLowerCase();
        int corte = valor.length();
        for (String rotulo : rotulos) {
            int pos = minusculo.indexOf(rotulo);
            if (pos > 0 && pos < corte) {
                corte = pos;
            }
        }
        return valor.substring(0, corte).replaceAll("[\s,;:|-]+$", "");
    }

    private static String primeiraOcorrencia(Pattern padrao, String texto) {
        Matcher m = padrao.matcher(texto);
        return m.find() ? m.group().trim() : "";
    }

    /**
     * Telefone so conta quando tem 8 digitos ou mais. Sem isso, numero de
     * projeto e data ("13-09-2026") entrariam como telefone.
     */
    private static String telefoneValido(String texto) {
        Matcher m = TELEFONE.matcher(texto);
        while (m.find()) {
            String candidato = m.group().trim();
            int digitos = candidato.replaceAll("\\D", "").length();
            if (digitos >= 8 && digitos <= 15) {
                return candidato;
            }
        }
        return "";
    }
}

