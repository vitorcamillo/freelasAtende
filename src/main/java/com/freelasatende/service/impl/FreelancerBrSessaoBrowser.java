package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.FreelancerBrConfig;
import io.github.selenium.javaDriverless.JavaDriverless;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Login, exportacao de cookies e espera do desafio no Chrome do Freelancer.com.br. */
@Slf4j
final class FreelancerBrSessaoBrowser {

    private FreelancerBrSessaoBrowser() {
    }

    static void preencherLogin(JavaDriverless driver, FreelancerBrConfig config) {
        driver.executeScript(
            "(() => {"
            + "const set = (el, valor) => {"
            + "  const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;"
            + "  setter.call(el, valor);"
            + "  el.dispatchEvent(new Event('input', {bubbles:true}));"
            + "  el.dispatchEvent(new Event('change', {bubbles:true}));"
            + "};"
            + "const form = document.querySelector('#headerLoginForm') "
            + "  || document.querySelector(\"form[action*='conecte-se']\");"
            + "if (!form) return false;"
            + "const email = form.querySelector(\"input[name='emailAddress']\");"
            + "const senha = form.querySelector(\"input[name='password']\");"
            + "if (!email || !senha) return false;"
            + "set(email, " + jsString(config.getEmail()) + ");"
            + "set(senha, " + jsString(config.getSenha()) + ");"
            // keepMeLoggedIn estende a validade da sessao emitida pelo Laravel.
            + "const manter = form.querySelector(\"input[name='keepMeLoggedIn']\");"
            + "if (manter && !manter.checked) manter.click();"
            + "form.submit();"
            + "return true;"
            + "})()");
    }

    /** Grava os cookies do Chrome no arquivo lido pelo transporte HTTP. */
    static boolean exportarCookies(JavaDriverless driver, FreelancerBrConfig config,
                                   ObjectMapper objectMapper) throws Exception {
        List<Map<String, Object>> cookies = driver.getCookies();
        Map<String, String> mapa = new HashMap<>();
        for (Map<String, Object> cookie : cookies) {
            Object nome = cookie.get("name");
            Object valor = cookie.get("value");
            if (nome != null && valor != null && !valor.toString().isBlank()) {
                mapa.put(nome.toString(), valor.toString());
            }
        }
        if (!mapa.containsKey("freelancer_live_session")) {
            return false;
        }
        Path destino = Path.of(config.getCookiesArquivo());
        if (destino.getParent() != null) {
            Files.createDirectories(destino.getParent());
        }
        Files.writeString(destino, objectMapper.writeValueAsString(mapa));
        // O cf_clearance fica atrelado ao User-Agent do navegador que o gerou.
        // Guarda o UA real do Chrome para o transporte HTTP usar o mesmo, em
        // vez do default configurado (que difere do Chrome do container).
        Object ua = driver.executeScript("navigator.userAgent");
        if (ua != null && !ua.toString().isBlank()) {
            Files.writeString(Path.of(config.getCookiesArquivo() + ".ua"), ua.toString());
        }
        log.info("Freelancer.com.br: sessao renovada e cookies exportados ({} cookies).", mapa.size());
        return true;
    }

    /** Reconhece a pagina do desafio pelo mesmo criterio do helper de espera. */
    static boolean pareceDesafio(String html) {
        if (html == null || html.isBlank()) {
            return true;
        }
        String minusculo = html.toLowerCase();
        return minusculo.contains("just a moment")
            || minusculo.contains("verifying you are human")
            || minusculo.contains("verificando se voc")
            || minusculo.contains("challenges.cloudflare.com");
    }

    /** O desafio do Cloudflare resolve sozinho em alguns segundos no Chrome real. */
    static void aguardarSaidaDoDesafio(JavaDriverless driver) throws Exception {
        long limite = System.currentTimeMillis() + 45_000L;
        String t = "";
        String c = "";
        while (System.currentTimeMillis() < limite) {
            // O desafio atual do Cloudflare ("Verifying you are human") renderiza
            // um checkbox do Turnstile que precisa de clique real de mouse (CDP),
            // nao basta aguardar. O helper ja validado no 99Freelas cuida do
            // scroll, da geometria e do clique com jitter humano.
            LoginDriverlessDomHelper.clicarTurnstileAutomaticamente(
                driver,
                "iframe[src*='challenges.cloudflare.com'], iframe[src*='turnstile']",
                ".cf-turnstile, div[data-sitekey]");
            // O Turnstile detecta o clique sintetico via CDP e nao completa o
            // desafio; o xdotool dispara evento X11 real no ponto do checkbox,
            // indistinguivel de um humano no mesmo display :99.
            LoginDriverlessDomHelper.clicarTurnstilePorXdotool(
                driver,
                "iframe[src*='challenges.cloudflare.com'], iframe[src*='turnstile']",
                ".cf-turnstile, div[data-sitekey]");
            Object titulo = driver.executeScript("document.title");
            t = titulo == null ? "" : titulo.toString().toLowerCase();
            // O desafio v3 do Cloudflare nao muda o titulo: usa "Verifying you
            // are human" no corpo. Detecta pelos dois sinais antes de declarar
            // falha por timeout.
            Object corpo = driver.executeScript("document.body ? document.body.innerText.slice(0, 500) : ''");
            c = corpo == null ? "" : corpo.toString().toLowerCase();
            boolean emDesafio = t.isBlank()
                || t.contains("just a moment")
                || t.contains("um momento")
                || c.contains("verifying you are human")
                || c.contains("verificando se você é humano");
            if (!emDesafio) {
                return;
            }
            TimeUnit.SECONDS.sleep(2);
        }
        log.warn("Freelancer.com.br: desafio do Cloudflare nao liberou dentro do tempo.");
        log.warn("Freelancer.com.br: diagnostico do desafio: titulo=[{}] corpo=[{}]",
            t, c.length() > 180 ? c.substring(0, 180) : c);
    }

    private static String jsString(String valor) {
        return "'" + valor.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
