package com.freelasatende.config;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

@Slf4j
public final class EnvFileLoader {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private EnvFileLoader() {
    }

    public static int loadIfPresent(Path arquivo) {
        if (arquivo == null || !Files.isRegularFile(arquivo)) {
            return 0;
        }

        int carregadas = 0;
        try {
            for (String linha : Files.readAllLines(arquivo)) {
                if (loadLine(linha)) {
                    carregadas++;
                }
            }
        } catch (IOException e) {
            log.warn("Nao foi possivel carregar variaveis do .env: {}", e.getMessage());
        }
        if (carregadas > 0) {
            log.info(".env carregado com {} variavel(is). Valores sensiveis nao serao exibidos.", carregadas);
        }
        return carregadas;
    }

    static boolean loadLine(String linha) {
        if (linha == null) {
            return false;
        }
        String texto = linha.trim();
        if (texto.isBlank() || texto.startsWith("#")) {
            return false;
        }
        if (texto.startsWith("export ")) {
            texto = texto.substring("export ".length()).trim();
        }

        int separador = texto.indexOf('=');
        if (separador <= 0) {
            return false;
        }

        String chave = texto.substring(0, separador).trim();
        if (!KEY_PATTERN.matcher(chave).matches()) {
            return false;
        }
        if (System.getProperty(chave) != null || System.getenv(chave) != null) {
            return false;
        }

        String valor = stripInlineComment(texto.substring(separador + 1).trim());
        valor = stripQuotes(valor);
        System.setProperty(chave, valor);
        return true;
    }

    private static String stripInlineComment(String valor) {
        boolean single = false;
        boolean dbl = false;
        for (int i = 0; i < valor.length(); i++) {
            char c = valor.charAt(i);
            if (c == '\'' && !dbl) {
                single = !single;
            } else if (c == '"' && !single) {
                dbl = !dbl;
            } else if (c == '#' && !single && !dbl && (i == 0 || Character.isWhitespace(valor.charAt(i - 1)))) {
                return valor.substring(0, i).trim();
            }
        }
        return valor;
    }

    private static String stripQuotes(String valor) {
        if (valor.length() >= 2) {
            char inicio = valor.charAt(0);
            char fim = valor.charAt(valor.length() - 1);
            if ((inicio == '"' && fim == '"') || (inicio == '\'' && fim == '\'')) {
                return valor.substring(1, valor.length() - 1);
            }
        }
        return valor;
    }
}
