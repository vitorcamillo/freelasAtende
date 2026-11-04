package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.FreelancerConfig;
import com.freelasatende.service.interfaceServico.TelegramService;
import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Login assistido do Freelancer.com via Chrome real (JavaDriverless), no mesmo
 * padrao do login do 99Freelas ({@link LoginJavaDriverless}) e do Upwork
 * ({@link UpworkLoginDriverless}).
 *
 * <p>Preenche credenciais, trata OTP via {@link MarketplaceOtpHandler} (com
 * leitura automatica por Gmail read-only e fallback Telegram/arquivo) e exporta
 * os cookies de sessao para {@code freelancer.cookies-arquivo} no formato
 * {@code {nome: valor}} lido por {@link FreelancerClient}. Nao envia proposta
 * nem mensagem: apenas autentica.</p>
 *
 * <p>Ativado somente com {@code freelancer.driverless.enabled=true}.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "freelancer.driverless", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class FreelancerLoginDriverless {

    private final FreelancerConfig config;
    private final ObjectMapper objectMapper;
    private final MfaCodeInbox mfaCodeInbox;
    private final ObjectProvider<TelegramService> telegramServiceProvider;
    private final GmailOtpReader gmailOtpReader;
    private final ChromeSessaoLock chromeSessaoLock;

    private static final String[] EMAIL_SELECTORS = {
        "#emailOrUsernameInput",
        "input[name='username']",
        "input[type='email']",
        "input[name='email']"
    };
    private static final String[] SENHA_SELECTORS = {
        "#passwordInput",
        "input[name='password']",
        "input[type='password']"
    };
    private static final String[] OTP_SELECTORS = {
        "input[name='otp']",
        "input[autocomplete='one-time-code']",
        "input[data-test='otp-input']",
        "input[type='tel']",
        "input[name='token']"
    };
    private static final String[] SUBMIT_TEXTOS = {"log in", "login", "continue", "sign in", "verify", "submit"};

    public boolean executarLogin() {
        if (!chromeSessaoLock.adquirir("Freelancer.com")) {
            return false;
        }
        JavaDriverless driver = null;
        try {
            log.info("===========================================");
            log.info("   LOGIN FREELANCER - JavaDriverless");
            log.info("===========================================");

            String email = config.getEmail();
            String senha = config.getSenha();
            if (email.isBlank() || senha.isBlank()) {
                log.warn("Credenciais Freelancer ausentes; defina FREELANCER_EMAIL e FREELANCER_PASSWORD.");
                return false;
            }

            ChromeOptions options = new ChromeOptions();
            options.addArgument("--start-maximized");
            options.addArgument("--no-sandbox");
            options.addArgument("--disable-dev-shm-usage");
            // Container roda com 1GB total (Java+Chrome+Xvfb); reduz o consumo
            // de memoria do Chrome fora do que este login precisa.
            options.addArgument("--disable-extensions");
            options.addArgument("--disable-background-networking");
            if (config.isLoginHeadless()) {
                options.addArgument("--headless=new");
                options.addArgument("--window-size=1440,900");
                options.addArgument("--disable-gpu");
            }

            try {
                driver = new JavaDriverless("FreelancerLogin", options, true);
            } catch (Exception e) {
                log.warn("Falha com profile persistente, tentando temporario: {}", e.getMessage());
                driver = new JavaDriverless("FreelancerLoginTmp-" + System.currentTimeMillis(), options, false);
            }

            String urlLogin = config.getUrlBase() + "/login";
            log.info("Navegando para: {}", urlLogin);
            driver.get(urlLogin);
            TimeUnit.SECONDS.sleep(8);

            String url = driver.getCurrentUrl();
            log.info("URL atual: {}", url);
            if (jaAutenticado(url)) {
                log.info("Sessao Freelancer anterior ainda valida.");
                return exportarESalvarCookies(driver);
            }

            preencher(driver, EMAIL_SELECTORS, email);
            try { driver.screenshot("freelancer_pos_email.png"); } catch (Exception ignore) {}
            preencher(driver, SENHA_SELECTORS, senha);
            try { driver.screenshot("freelancer_pos_senha.png"); } catch (Exception ignore) {}
            clicarSubmit(driver);
            TimeUnit.SECONDS.sleep(6);

            url = driver.getCurrentUrl();
            log.info("URL apos credenciais: {}", url);

            MarketplaceOtpHandler otp = new MarketplaceOtpHandler(
                "Freelancer",
                "data/freelancer-otp.txt",
                OTP_SELECTORS,
                SUBMIT_TEXTOS,
                telegramServiceProvider.getIfAvailable(),
                mfaCodeInbox,
                150_000L,
                gmailOtpReader,
                "from:freelancer.com (verification OR code OR security OR login OR OTP)"
            );
            if (otp.pareceTelaOtp(driver)) {
                url = otp.resolver(driver);
            }

            log.info("URL final Freelancer: {}", url);
            if (jaAutenticado(url)) {
                log.info("LOGIN FREELANCER BEM-SUCEDIDO.");
                return exportarESalvarCookies(driver);
            }

            log.warn("Login Freelancer nao concluiu automaticamente. URL: {}", url);
            try { driver.screenshot("freelancer_login_pendente.png"); } catch (Exception ignore) {}
            TimeUnit.SECONDS.sleep(2);
            url = driver.getCurrentUrl();
            if (jaAutenticado(url)) {
                return exportarESalvarCookies(driver);
            }
            return false;

        } catch (Exception e) {
            log.error("Erro no login Freelancer: {}", e.getMessage(), e);
            return false;
        } finally {
            if (driver != null) {
                try { driver.quit(); } catch (Exception ignore) {}
            }
            chromeSessaoLock.liberar();
        }
    }

    private boolean jaAutenticado(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase();
        boolean naTelaLogin = u.contains("/login") || u.contains("/signup")
            || u.contains("otp") || u.contains("verify") || u.contains("challenge");
        return !naTelaLogin && u.contains("freelancer.com");
    }

    private void preencher(JavaDriverless driver, String[] selectors, String valor) {
        long deadline = System.currentTimeMillis() + 12_000L;
        while (System.currentTimeMillis() < deadline) {
            for (String sel : selectors) {
                if (sel != null && !sel.isBlank() && LoginDriverlessDomHelper.elementoVisivel(driver, sel)) {
                    injetarValor(driver, sel, valor);
                    return;
                }
            }
            try { TimeUnit.MILLISECONDS.sleep(300); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Campo nao encontrado para preencher. Tentados: {}", String.join(", ", selectors));
    }

    private void injetarValor(JavaDriverless driver, String selector, String valor) {
        String safe = valor.replace("\\", "\\\\").replace("'", "\\'");
        driver.executeScript(
            "var el = document.querySelector('" + selector.replace("'", "\\'") + "');"
            + "if (el) {"
            + "  el.focus();"
            + "  var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;"
            + "  setter.call(el, '');"
            + "  var text = '" + safe + "';"
            + "  for (var i = 0; i < text.length; i++) {"
            + "    setter.call(el, el.value + text[i]);"
            + "    el.dispatchEvent(new Event('input', {bubbles:true}));"
            + "  }"
            + "  el.dispatchEvent(new Event('change', {bubbles:true}));"
            + "}"
        );
    }

    private void clicarSubmit(JavaDriverless driver) {
        StringBuilder cond = new StringBuilder();
        for (String t : SUBMIT_TEXTOS) {
            if (cond.length() > 0) {
                cond.append(" || ");
            }
            cond.append("b.innerText.toLowerCase().includes('").append(t).append("')");
        }
        driver.executeScript(
            "var all = Array.from(document.querySelectorAll('button, input[type=submit]'));"
            + "var btn = all.find(function(b){"
            + "  var t=(b.innerText||'').toLowerCase();"
            + "  return b.offsetParent!==null && !t.includes('google') && !t.includes('facebook')"
            + "    && (" + cond + ");"
            + "});"
            // Fallback: primeiro button[type=submit] visivel (login do Freelancer nao tem id/texto estavel).
            + "if (!btn) { btn = all.find(function(b){ return b.type==='submit' && b.offsetParent!==null"
            + "    && !(b.innerText||'').toLowerCase().includes('google') && !(b.innerText||'').toLowerCase().includes('facebook'); }); }"
            + "if (btn) btn.click();"
        );
    }

    private boolean exportarESalvarCookies(JavaDriverless driver) {
        try {
            List<Map<String, Object>> cookies = driver.getCookies();
            log.info("Cookies CDP encontrados: {}", cookies.size());
            Map<String, String> cookieMap = new HashMap<>();
            for (Map<String, Object> cookie : cookies) {
                Object name = cookie.get("name");
                Object value = cookie.get("value");
                if (name != null && value != null) {
                    cookieMap.put(name.toString(), value.toString());
                }
            }
            boolean temSessao = cookieMap.containsKey("GETAFREE_AUTH_HASH_V2")
                || cookieMap.containsKey("GETAFREE_AUTH_HASH")
                || cookieMap.containsKey("gaf_csrf_token")
                || cookieMap.containsKey("freelancer_session");
            if (temSessao) {
                log.info("Cookies de sessao Freelancer encontrados.");
            } else {
                log.warn("Cookies de sessao Freelancer nao reconhecidos. Keys: {}", cookieMap.keySet());
            }
            salvarCookies(cookieMap);
            log.info("{} cookies Freelancer exportados e salvos.", cookieMap.size());
            return !cookieMap.isEmpty() && temSessao;
        } catch (Exception e) {
            log.error("Erro ao exportar cookies Freelancer: {}", e.getMessage());
            return false;
        }
    }

    private void salvarCookies(Map<String, String> cookies) {
        try {
            Path arquivo = Paths.get(config.getCookiesArquivo());
            Path dir = arquivo.getParent();
            if (dir != null && !Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(arquivo.toFile(), cookies);
            log.info("Cookies Freelancer salvos em {}", config.getCookiesArquivo());
        } catch (Exception e) {
            log.error("Erro ao salvar cookies Freelancer: {}", e.getMessage());
        }
    }
}
