package com.freelasatende.service.impl;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.By;
import io.github.selenium.javaDriverless.types.WebElement;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class LoginDriverlessDomHelper {

    private LoginDriverlessDomHelper() {
    }

    /**
     * Le a URL atual sem deixar um timeout de CDP derrubar o fluxo de login.
     *
     * <p>{@code getCurrentUrl()} faz uma ida ao Chrome e, sob carga no container
     * de 1GB, estoura com "Erro ao obter URL". Como a URL aqui so alimenta
     * heuristicas (ja autenticado? tela de OTP?), uma leitura falha deve virar
     * string vazia e nao excecao: quem chama trata a ausencia como "ainda nao
     * concluiu" e segue para a proxima etapa.</p>
     */
    static String urlAtualSegura(JavaDriverless driver) {
        try {
            String url = driver.getCurrentUrl();
            return url == null ? "" : url;
        } catch (Exception e) {
            log.warn("Nao foi possivel ler a URL atual do Chrome: {}", e.getMessage());
            return "";
        }
    }

    static String safeGetString(JavaDriverless driver, String js) {
        try {
            Object val = driver.executeScript(js);
            return val != null ? val.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static WebElement findFirstUsableElement(JavaDriverless driver, String[] selectors) {
        for (String sel : selectors) {
            try {
                WebElement el = driver.findElement(By.css(sel));
                if (el == null) {
                    continue;
                }
                double[] size = el.getSize().get();
                if (size != null && size.length >= 2 && size[0] > 5 && size[1] > 5) {
                    return el;
                }
            } catch (Exception ignore) {
                // segue tentando outros seletores
            }
        }
        return null;
    }

    static boolean elementoVisivel(JavaDriverless driver, String cssSelector) {
        try {
            Object ok = driver.executeScript(
                "(() => {" +
                    "const els = document.querySelectorAll(" + jsString(cssSelector) + ");" +
                    "for (let i = 0; i < els.length; i++) {" +
                    "  const el = els[i];" +
                    "  const r = el.getBoundingClientRect();" +
                    "  const cs = getComputedStyle(el);" +
                    "  if (cs.display !== 'none' && cs.visibility !== 'hidden' && parseFloat(cs.opacity || '1') >= 0.05 && r.width > 5 && r.height > 5) {" +
                    "    return true;" +
                    "  }" +
                    "}" +
                    "return false;" +
                "})()"
            );
            return ok instanceof Boolean b && b;
        } catch (Exception e) {
            return false;
        }
    }

    static String jsString(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
