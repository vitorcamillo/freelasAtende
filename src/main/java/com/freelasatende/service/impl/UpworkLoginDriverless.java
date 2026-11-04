package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.UpworkConfig;
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
 * Login assistido do Upwork via Chrome real (JavaDriverless), no mesmo padrao
 * do login do 99Freelas ({@link LoginJavaDriverless}).
 *
 * <p>Abre um Chrome real, preenche credenciais, trata
 * verificacao em duas etapas (OTP) via {@link MarketplaceOtpHandler} e exporta
 * os cookies de sessao (incluindo HttpOnly, que o CDP enxerga) para o arquivo
 * configurado em {@code upwork.cookies-arquivo}. O formato salvo e
 * {@code {nome: valor}}, exatamente o que {@link UpworkClient} le.</p>
 *
 * <p>Ativado apenas com {@code upwork.driverless.enabled=true} para nao subir o
 * bean em ambiente de teste/CI. Nao envia proposta nem mensagem: so autentica.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "upwork.driverless", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class UpworkLoginDriverless {

    private final UpworkConfig config;
    private final ObjectMapper objectMapper;
    private final MfaCodeInbox mfaCodeInbox;
    private final ObjectProvider<TelegramService> telegramServiceProvider;
    private final GmailOtpReader gmailOtpReader;
    private final ChromeSessaoLock chromeSessaoLock;

    private static final String[] EMAIL_SELECTORS = {
        "#login_username",
        "input[name='login[username]']",
        "input[type='email']",
        "input[name='username']"
    };
    private static final String[] SENHA_SELECTORS = {
        "#login_password",
        "input[name='login[password]']",
        "input[type='password']"
    };
    private static final String[] OTP_SELECTORS = {
        "#login_answer",
        "input[name='login[answer]']",
        "input[data-test='otp-input']",
        "input[autocomplete='one-time-code']",
        "input[name='otp']",
        "input[type='tel']"
    };
    private static final String[] CONTINUE_TEXTOS = {"continue", "log in", "login", "next"};

    /**
     * Executa o login e exporta os cookies.
     *
     * @return {@code true} se a sessao ficou valida e os cookies foram salvos.
     */
    public boolean executarLogin() {
        if (!chromeSessaoLock.adquirir("Upwork")) {
            return false;
        }
        JavaDriverless driver = null;
        try {
            log.info("===========================================");
            log.info("   LOGIN UPWORK - JavaDriverless");
            log.info("===========================================");

            String email = config.getEmail();
            String senha = config.getSenha();
            if (email.isBlank() || senha.isBlank()) {
                log.warn("Credenciais Upwork ausentes; defina UPWORK_EMAIL e UPWORK_PASSWORD.");
                return false;
            }

            ChromeOptions options = new ChromeOptions();
            options.addArgument("--start-maximized");
            options.addArgument("--no-sandbox");
            options.addArgument("--disable-dev-shm-usage");
            // Container roda com 1GB total (Java+Chrome+Xvfb); reduz o consumo
            // de memoria do Chrome (ChromeSessaoLock ja evita 2 sessoes juntas,
            // mas cada sessao isolada tambem precisa caber com folga).
            options.addArgument("--disable-extensions");
            options.addArgument("--disable-background-networking");
            if (config.isLoginHeadless()) {
                options.addArgument("--headless=new");
                options.addArgument("--window-size=1440,900");
                options.addArgument("--disable-gpu");
            }

            try {
                driver = new JavaDriverless("UpworkLogin", options, true);
            } catch (Exception e) {
                log.warn("Falha com profile persistente, tentando temporario: {}", e.getMessage());
                driver = new JavaDriverless("UpworkLoginTmp-" + System.currentTimeMillis(), options, false);
            }

            String urlLogin = config.getUrlBase() + "/ab/account-security/login";
            log.info("Navegando para: {}", urlLogin);
            // O driver.get() estoura TimeoutException quando o Upwork demora a
            // liberar o onload (medido: 6 de 27 ciclos morriam aqui, antes de
            // qualquer credencial). A pagina em geral ja esta utilizavel nesse
            // ponto, entao o timeout vira aviso e o fluxo continua: quem decide
            // se deu certo e a checagem de URL/seletores logo abaixo.
            try {
                driver.get(urlLogin);
            } catch (Exception e) {
                log.warn("Timeout ao carregar a pagina de login do Upwork ({}); seguindo com a pagina parcial.",
                    e.getMessage());
            }
            TimeUnit.SECONDS.sleep(8);

            String url = LoginDriverlessDomHelper.urlAtualSegura(driver);
            log.info("URL atual: {}", url);
            if (jaAutenticado(url)) {
                log.info("Sessao Upwork anterior ainda valida.");
                return exportarESalvarCookies(driver);
            }

            fecharBannerCookies(driver);

            // Upwork usa fluxo em duas etapas: usuario -> continuar -> senha -> continuar.
            preencher(driver, EMAIL_SELECTORS, email);
            try { driver.screenshot("upwork_pos_email.png"); } catch (Exception ignore) {}
            clicarContinuar(driver);
            TimeUnit.SECONDS.sleep(4);
            fecharBannerCookies(driver);

            preencher(driver, SENHA_SELECTORS, senha);
            try { driver.screenshot("upwork_pos_senha.png"); } catch (Exception ignore) {}
            marcarManterConectado(driver);
            clicarContinuar(driver);
            TimeUnit.SECONDS.sleep(6);

            url = LoginDriverlessDomHelper.urlAtualSegura(driver);
            log.info("URL apos credenciais: {}", url);

            // Etapa opcional: pergunta de seguranca ("Let's make sure it's you").
            if (perguntaSegurancaVisivel(driver)) {
                responderPerguntaSeguranca(driver);
                TimeUnit.SECONDS.sleep(6);
                url = LoginDriverlessDomHelper.urlAtualSegura(driver);
                log.info("URL apos pergunta de seguranca: {}", url);
            }

            MarketplaceOtpHandler otp = new MarketplaceOtpHandler(
                "Upwork",
                "data/upwork-otp.txt",
                OTP_SELECTORS,
                CONTINUE_TEXTOS,
                telegramServiceProvider.getIfAvailable(),
                mfaCodeInbox,
                150_000L,
                gmailOtpReader,
                "from:upwork.com (verification OR code OR security OR login)"
            );
            if (otp.pareceTelaOtp(driver)) {
                url = otp.resolver(driver);
            }

            log.info("URL final Upwork: {}", url);
            if (jaAutenticado(url)) {
                log.info("LOGIN UPWORK BEM-SUCEDIDO.");
                return exportarESalvarCookies(driver);
            }

            log.warn("Login Upwork nao concluiu automaticamente. URL: {}", url);
            try { driver.screenshot("upwork_login_pendente.png"); } catch (Exception ignore) {}
            // A janela segue aberta: se o usuario concluir manualmente, ainda exporta.
            TimeUnit.SECONDS.sleep(2);
            url = LoginDriverlessDomHelper.urlAtualSegura(driver);
            if (jaAutenticado(url)) {
                return exportarESalvarCookies(driver);
            }
            return false;

        } catch (Exception e) {
            log.error("Erro no login Upwork: {}", e.getMessage(), e);
            return false;
        } finally {
            if (driver != null) {
                try { driver.quit(); } catch (Exception ignore) {}
            }
            chromeSessaoLock.liberar();
        }
    }

    static boolean jaAutenticado(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase();
        // "account-security" como substring solta dava falso negativo: apos a
        // pergunta de seguranca o Upwork leva para
        // /ab/account-security/password-and-security, que e area JA LOGADA, e o
        // login bem-sucedido era reportado como "nao concluiu automaticamente"
        // (medido em producao). Casar apenas as telas de autenticacao.
        boolean naTelaLogin = u.contains("/ab/account-security/login")
            || u.endsWith("/login") || u.contains("/login?")
            || u.contains("otp") || u.contains("verify") || u.contains("challenge");
        return !naTelaLogin && u.contains("upwork.com");
    }

    private void preencher(JavaDriverless driver, String[] selectors, String valor) {
        // 12s eram suficientes no desktop, mas no container de 1GB cada
        // executeScript via CDP leva dezenas de segundos: o log real mostrou 3min
        // entre "URL atual" e "Campo nao encontrado", ou seja, o prazo estourava
        // durante a *primeira* varredura de seletores e a senha nunca era digitada.
        long deadline = System.currentTimeMillis() + 90_000L;
        while (System.currentTimeMillis() < deadline) {
            for (String sel : selectors) {
                if (sel != null && !sel.isBlank() && LoginDriverlessDomHelper.elementoVisivel(driver, sel)) {
                    // Um timeout de CDP na injecao nao encerra a busca: volta ao
                    // laco e tenta de novo enquanto houver prazo, em vez de sair
                    // como se o campo tivesse sido preenchido.
                    if (injetarValor(driver, sel, valor)) {
                        return;
                    }
                }
            }
            try { TimeUnit.MILLISECONDS.sleep(300); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Campo nao encontrado para preencher. Tentados: {}", String.join(", ", selectors));
    }

    private boolean injetarValor(JavaDriverless driver, String selector, String valor) {
        String safe = valor.replace("\\", "\\\\").replace("'", "\\'");
        try {
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
            return true;
        } catch (Exception e) {
            // Timeout de CDP sob carga: nao pode abortar o login inteiro. O
            // chamador reavalia o seletor e tenta de novo dentro do prazo.
            log.warn("Falha ao preencher {} no Upwork ({}); tentando novamente.", selector, e.getMessage());
            return false;
        }
    }

    /**
     * Marca "Keep me logged in" na tela de senha antes de submeter. Sem isso a
     * sessao do Upwork expira em poucos dias mesmo com cookies validos salvos
     * (medido: master_access_token/auth_session presentes mas o site redireciona
     * para /ab/account-security/login) porque o backend nunca emite os tokens de
     * longa duracao — o app so tinha essa lacuna aqui; Freelancer.com.br ja
     * marca o equivalente (keepMeLoggedIn) no proprio formulario.
     */
    private void marcarManterConectado(JavaDriverless driver) {
        try {
            driver.executeScript(
                "(() => {"
                + "const candidatos = Array.from(document.querySelectorAll(\"input[type=checkbox]\"));"
                + "let alvo = candidatos.find(c => {"
                + "  const label = (c.closest('label')?.innerText || '')"
                + "    + ' ' + (c.getAttribute('aria-label') || '')"
                + "    + ' ' + (c.id ? (document.querySelector(\"label[for='\" + c.id + \"']\")?.innerText || '') : '');"
                + "  return /keep me logged in|manter.*conectad|lembrar/i.test(label);"
                + "});"
                + "if (!alvo) return false;"
                + "if (!alvo.checked) {"
                + "  alvo.scrollIntoView({block:'center'});"
                + "  alvo.click();"
                + "}"
                + "return alvo.checked;"
                + "})()"
            );
        } catch (Exception e) {
            log.debug("Nao foi possivel marcar 'Keep me logged in' no Upwork: {}", e.getMessage());
        }
    }

    private void clicarContinuar(JavaDriverless driver) {
        // Prioriza os IDs reais do fluxo do Upwork; cai para texto como fallback.
        // Timeout do CDP aqui nao pode derrubar o login inteiro: no container de
        // 1GB o executeScript ocasionalmente estoura e a excecao subia ate o
        // executarLogin, abortando o ciclo antes da pergunta de seguranca e do
        // OTP. As demais etapas ja sao tolerantes; esta era a unica que nao era.
        try {
            driver.executeScript(
                "var ids = ['login_password_continue','login_username_continue','login_control_continue','login_control_continue'];"
                + "var btn = null;"
                + "for (var i=0;i<ids.length && !btn;i++){ var e=document.getElementById(ids[i]); if(e && e.offsetParent!==null) btn=e; }"
                + "if (!btn) {"
                + "  btn = Array.from(document.querySelectorAll('button, input[type=submit]')).find(function(b){"
                + "    var t=(b.innerText||'').toLowerCase();"
                + "    return b.offsetParent!==null && (t.includes('continue')||t.includes('log in')||t.includes('login')||t.includes('next'))"
                + "      && !t.includes('google') && !t.includes('apple');"
                + "  });"
                + "}"
                + "if (btn) btn.click();"
            );
        } catch (Exception e) {
            log.warn("Falha ao clicar em continuar no Upwork ({}); seguindo o fluxo.", e.getMessage());
        }
    }

    /** Detecta a tela de pergunta de seguranca do Upwork. */
    private boolean perguntaSegurancaVisivel(JavaDriverless driver) {
        try {
            Object r = driver.executeScript(
                "return !!(document.getElementById('login_answer')"
                + " || document.querySelector('input[name=\"login[answer]\"]')"
                + " || (document.body.innerText||'').toLowerCase().indexOf('security question')>=0"
                + " || (document.body.innerText||'').toLowerCase().indexOf(\"make sure it's you\")>=0);"
            );
            return Boolean.TRUE.equals(r);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Responde a pergunta de seguranca. Se {@code upwork.security-answer} tiver
     * mapeamentos "trecho=resposta;...", escolhe pela pergunta exibida; caso
     * contrario usa a resposta unica configurada.
     */
    private void responderPerguntaSeguranca(JavaDriverless driver) {
        String cfg = config.getSecurityAnswer();
        if (cfg.isBlank()) {
            log.warn("Pergunta de seguranca do Upwork exibida, mas UPWORK_SECURITY_ANSWER nao esta definido.");
            try { driver.screenshot("upwork_security_question.png"); } catch (Exception ignore) {}
            return;
        }
        String pergunta = "";
        try {
            Object q = driver.executeScript("return (document.body.innerText||'').toLowerCase();");
            pergunta = q == null ? "" : q.toString();
        } catch (Exception ignore) {
        }
        String resposta = escolherResposta(cfg, pergunta);
        if (resposta == null || resposta.isBlank()) {
            log.warn("Nenhuma resposta de seguranca casou com a pergunta exibida.");
            return;
        }
        log.info("Respondendo pergunta de seguranca do Upwork.");
        injetarValor(driver, "#login_answer", resposta);
        // fallback de seletor caso o id mude
        driver.executeScript(
            "var el=document.querySelector('#login_answer, input[name=\"login[answer]\"], input[type=text]');"
            + "if(el && !el.value){ el.focus(); }"
        );
        clicarContinuar(driver);
    }

    /**
     * Escolhe a resposta: se {@code cfg} contem pares "trecho=resposta"
     * separados por ';', casa pelo trecho presente na pergunta; senao devolve
     * o valor unico.
     */
    static String escolherResposta(String cfg, String perguntaLower) {
        if (cfg == null || cfg.isBlank()) {
            return null;
        }
        if (!cfg.contains("=")) {
            return cfg.trim();
        }
        String fallback = null;
        for (String par : cfg.split(";")) {
            String p = par.trim();
            if (p.isEmpty()) {
                continue;
            }
            int eq = p.indexOf('=');
            if (eq <= 0) {
                fallback = p;
                continue;
            }
            String chave = p.substring(0, eq).trim().toLowerCase();
            String valor = p.substring(eq + 1).trim();
            if (!chave.isEmpty() && perguntaLower != null && perguntaLower.contains(chave)) {
                return valor;
            }
            if (fallback == null) {
                fallback = valor;
            }
        }
        return fallback;
    }

    /** Fecha o banner de cookies (OneTrust) que pode interceptar cliques. */
    private void fecharBannerCookies(JavaDriverless driver) {
        try {
            driver.executeScript(
                "var b=document.getElementById('onetrust-accept-btn-handler'); if(b && b.offsetParent!==null) b.click();"
            );
        } catch (Exception ignore) {
        }
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
            boolean temSessao = cookieMap.containsKey("oauth2_global_js_token")
                || cookieMap.containsKey("master_access_token")
                || cookieMap.containsKey("user_uid")
                || cookieMap.containsKey("XSRF-TOKEN");
            if (temSessao) {
                log.info("Cookies de sessao Upwork encontrados.");
            } else {
                log.warn("Cookies de sessao Upwork nao reconhecidos. Keys: {}", cookieMap.keySet());
            }
            salvarCookies(cookieMap);
            log.info("{} cookies Upwork exportados e salvos.", cookieMap.size());
            return !cookieMap.isEmpty() && temSessao;
        } catch (Exception e) {
            log.error("Erro ao exportar cookies Upwork: {}", e.getMessage());
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
            log.info("Cookies Upwork salvos em {}", config.getCookiesArquivo());
        } catch (Exception e) {
            log.error("Erro ao salvar cookies Upwork: {}", e.getMessage());
        }
    }
}
