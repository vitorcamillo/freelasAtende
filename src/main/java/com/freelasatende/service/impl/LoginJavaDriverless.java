package com.freelasatende.service.impl;

import com.freelasatende.config.FreelasConfig;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import com.freelasatende.service.interfaceServico.TelegramService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import io.github.selenium.javaDriverless.types.WebElement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * Login no 99Freelas usando JavaDriverless em modo assistido.
 *
 * <p>O fluxo abre um Chrome real, preenche credenciais, tenta concluir o desafio interativo
 * pelo próprio navegador e depois exporta os cookies para reaproveitamento pelo scraper.</p>
 *
 * <p>DOM da página de login (confirmado via browser analysis):</p>
 * <ul>
 *   <li>Email: {@code #email} (input type="text")</li>
 *   <li>Senha: {@code #senha} (input type="password")</li>
 *   <li>Turnstile: {@code iframe[src*="challenges.cloudflare.com"]}</li>
 *   <li>Botão: {@code #btnEfetuarLogin} (button.btn-green)</li>
 *   <li>Sitekey: {@code 0x4AAAAAAA9Utah2iZE4n8u6}</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "freelas.driverless", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class LoginJavaDriverless {

    private final FreelasConfig freelasConfig;
    private final AutenticacaoService autenticacaoService;
    private final TelegramService telegramService;
    private final MfaCodeInbox mfaCodeInbox;
    private final ObjectMapper objectMapper;
    private final ChromeSessaoLock chromeSessaoLock;
    // Nao e dependencia injetada: inicializado fora do @RequiredArgsConstructor.
    private final LoginRecusaGuard recusaGuard = new LoginRecusaGuard();

    private static final String SELECTOR_TURNSTILE_IFRAME =
        "iframe[src*='challenges.cloudflare.com'], iframe[src*='turnstile']";
    private static final String SELECTOR_TURNSTILE_HOST =
        ".cf-turnstile, div[data-sitekey]";

    @Value("${auth.cookies-arquivo:data/cookies.json}")
    private String arquivoCookies;

    /**
     * Executa login completo via JavaDriverless.
     *
     * @return true se login bem-sucedido e cookies exportados
     */
    public boolean executarLogin() {
        if (recusaGuard.bloqueada(freelasConfig.getEmail(), freelasConfig.getSenha())) {
            log.warn("Login 99Freelas pausado: senha rejeitada anteriormente; aguardando atualizacao da senha");
            return false;
        }
        if (!chromeSessaoLock.adquirir("99Freelas")) {
            return false;
        }
        JavaDriverless driver = null;

        try {
            log.info("═══════════════════════════════════════════");
            log.info("   🔐 LOGIN 99FREELAS - JavaDriverless");
            log.info("═══════════════════════════════════════════");

            ChromeOptions options = new ChromeOptions();
            options.addArgument("--start-maximized");
            options.addArgument("--no-sandbox");
            options.addArgument("--disable-dev-shm-usage");
            // Container roda com 1GB total (Java+Chrome+Xvfb); reduz o consumo
            // de memoria do Chrome fora do que este login precisa.
            options.addArgument("--disable-extensions");
            options.addArgument("--disable-background-networking");

            // Perfil de fingerprint Linux-BR (locale pt-BR, timezone America/Sao_Paulo,
            // plataforma Linux x86_64 e spoof de WebGL coerente). Mantém o renderer
            // reportado consistente mesmo quando o backend real é SwiftShader/llvmpipe.
            options.setFingerprintProfile(BrowserFingerprintProfile.brLinux());

            // Profile persistente mantém cookies/sessão entre execuções.
            // Fallback: se travar para conectar (porta/profile em estado ruim), tenta profile temporário.
            try {
                driver = new JavaDriverless("99FreelasLogin", options, true);
            } catch (Exception e) {
                log.warn("⚠️ Falha ao iniciar JavaDriverless com profile persistente, tentando profile temporário: {}", e.getMessage());
                driver = new JavaDriverless("99FreelasLoginTmp", options, false);
            }

            // 1. Navegar para login
            String urlLogin = freelasConfig.getUrlBase() + "/login";
            log.info("📝 Navegando para: {}", urlLogin);
            driver.get(urlLogin);

            // 2. Aguardar página carregar com Cloudflare
            log.info("⏱️ Aguardando página carregar (10s)...");
            TimeUnit.SECONDS.sleep(10);

            String url = driver.getCurrentUrl();
            log.info("   URL atual: {}", url);

            // Se já não está mais no login (sessão anterior válida), exportar cookies
            if (!url.contains("login")) {
                log.info("✅ Sessão anterior ainda válida!");
                recusaGuard.limpar();
                return exportarESalvarCookies(driver);
            }

            // 3. Preencher credenciais ANTES do Turnstile (preenchimento natural)
            aguardarElementoCdp(driver, new String[]{
                "#email",
                "input[placeholder='Email']",
                "input[placeholder='E-mail']",
                "input[name='email']",
                "input[type='email']"
            }, 12, "debug_sem_email.png");

            aguardarElementoCdp(driver, new String[]{
                "#senha",
                "input[placeholder='Senha']",
                "input[name='senha']",
                "input[type='password']"
            }, 12, "debug_sem_senha.png");

            preencherCredenciais(driver);
            try { driver.screenshot("login_pos_preenchimento.png"); } catch (Exception ignore) {}

            // 4. Se houver desafio interativo, tenta resolver pelo próprio navegador.
            boolean resolvido = resolverTurnstile(driver);
            if (!resolvido) {
                log.warn("⚠️ Turnstile não resolvido, tentando submeter mesmo assim...");
            }

            // 5. Clicar no botão Entrar
            clicarBotaoLogin(driver);

            // 6. Aguardar carregamento da próxima página
            log.info("⏱️ Aguardando resultado do login...");
            TimeUnit.SECONDS.sleep(8);

            url = driver.getCurrentUrl();
            log.info("   URL após login: {}", url);

            // 6.5. Lidar com Autenticação Multifator (MFA)
            if (url != null && url.contains("/mfa/session/confirm")) {
                url = new LoginMfaHandler(telegramService, mfaCodeInbox).lidarComMfa(driver);
            }

            // 7. Verificar resultado
            log.info("📊 URL final: {}", url);

            if (url != null && !url.contains("login") && !url.contains("mfa/session/confirm")) {
                log.info("✅ LOGIN BEM-SUCEDIDO!");
                recusaGuard.limpar();
                return exportarESalvarCookies(driver);
            }

            log.warn("⚠️ Login falhou — URL continua bloqueada: {}", url);
            String mensagemPagina = LoginDriverlessDomHelper.extrairMensagemErroLogin(driver);
            if (!mensagemPagina.isBlank()) {
                log.warn("Mensagem visivel na pagina de login: {}", mensagemPagina);
                recusaGuard.registrarFalha(freelasConfig.getEmail(), freelasConfig.getSenha(), mensagemPagina, telegramService);
            } else {
                log.warn("Nenhuma mensagem de erro visivel na pagina de login (possivel bloqueio de IP ou desafio silencioso)");
            }
            try {
                driver.screenshot("login_falhou.png");
            } catch (Exception ignore) {}
            return false;

        } catch (Exception e) {
            log.error("❌ Erro no login JavaDriverless: {}", e.getMessage(), e);
            return false;
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception e) {
                    log.debug("Erro ao fechar driver: {}", e.getMessage());
                }
            }
            chromeSessaoLock.liberar();
        }
    }

    /**
     * Preenche email e senha nos campos de login.
     *
     * Tenta via findElement + sendKeys (CDP nativo, mais natural).
     * Fallback via executeScript JS direto.
     */
    private void preencherCredenciais(JavaDriverless driver) throws Exception {
        log.info("📝 Preenchendo credenciais...");

        String email = freelasConfig.getEmail();
        String senha = freelasConfig.getSenha();
        if (email == null || email.isBlank() || senha == null || senha.isBlank()) {
            throw new IllegalStateException("Credenciais 99Freelas ausentes; defina FREELAS_EMAIL e FREELAS_PASSWORD");
        }

        if (preencherCredenciaisViaJs(driver, email, senha)) {
            return;
        }

        // Preferir CDP sendKeys em inputs reais quando possível (mais "humano").
        try {
            WebElement emailField = LoginDriverlessDomHelper.findFirstUsableElement(driver, new String[]{
                "#email",
                "input[placeholder='Email']",
                "input[placeholder='E-mail']",
                "input[name='email']",
                "input[type='email']",
                "form input[placeholder='Email']"
            });
            if (emailField != null) {
                emailField.click().get();
                TimeUnit.MILLISECONDS.sleep(250);
                emailField.sendKeys(email).get();
                log.info("   ✅ Email preenchido via CDP sendKeys");
            }

            WebElement senhaField = LoginDriverlessDomHelper.findFirstUsableElement(driver, new String[]{
                "#senha",
                "input[placeholder='Senha']",
                "input[name='senha']",
                "input[type='password']",
                "form input[placeholder='Senha']"
            });
            if (senhaField != null) {
                senhaField.click().get();
                TimeUnit.MILLISECONDS.sleep(250);
                senhaField.sendKeys(senha).get();
                log.info("   ✅ Senha preenchida via CDP sendKeys");
            }
        } catch (Exception e) {
            log.debug("   CDP sendKeys falhou (seguindo com JS): {}", e.getMessage());
        }

        preencherCredenciaisViaJs(driver, email, senha);
    }

    private boolean preencherCredenciaisViaJs(JavaDriverless driver, String email, String senha) throws Exception {
        // Preenchimento mais robusto (compatível com frameworks que escutam setter nativo).
        log.info("   Preenchendo via JS setNativeValue (com input/change)...");
        Object fillResult = driver.executeScript(
            "(() => {" +
                "function isVisible(el) {" +
                    "if (!el) return false;" +
                    "const r = el.getBoundingClientRect();" +
                    "const cs = getComputedStyle(el);" +
                    "if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity || '1') < 0.05) return false;" +
                    "return r.width > 5 && r.height > 5;" +
                "}" +
                "function findVisible(sel) {" +
                    "const els = Array.from(document.querySelectorAll(sel));" +
                    "for (const el of els) { if (isVisible(el)) return el; }" +
                    "return els.length ? els[0] : null;" +
                "}" +
                "function setNativeValue(el, value) {" +
                    "const proto = Object.getPrototypeOf(el);" +
                    "const desc = Object.getOwnPropertyDescriptor(proto, 'value');" +
                    "if (desc && desc.set) desc.set.call(el, value);" +
                    "else el.value = value;" +
                "}" +
                "function fill(sel, val) {" +
                    "const el = findVisible(sel);" +
                    "if (!el) return false;" +
                    "el.scrollIntoView({block:'center', inline:'center'});" +
                    "el.focus();" +
                    "setNativeValue(el, val);" +
                    "el.dispatchEvent(new Event('input', {bubbles:true}));" +
                    "el.dispatchEvent(new Event('change', {bubbles:true}));" +
                    "return true;" +
                "}" +
                "const emailSel = " + LoginDriverlessDomHelper.jsString(seletorEmailDinamico()) + ";" +
                "const senhaSel = " + LoginDriverlessDomHelper.jsString(seletorSenhaDinamico()) + ";" +
                "const okEmail = fill(emailSel, " + LoginDriverlessDomHelper.jsString(email) + ");" +
                "const okSenha = fill(senhaSel, " + LoginDriverlessDomHelper.jsString(senha) + ");" +
                "const eEl = findVisible(emailSel);" +
                "const sEl = findVisible(senhaSel);" +
                "return {okEmail: okEmail, okSenha: okSenha, vEmailLen: (eEl && eEl.value ? eEl.value.length : 0), vSenhaLen: (sEl && sEl.value ? sEl.value.length : 0)};" +
            "})()"
        );

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> res = (fillResult instanceof Map) ? (Map<String, Object>) fillResult : null;
            if (res != null) {
                int emailLen = res.get("vEmailLen") instanceof Number n ? n.intValue() : -1;
                int senhaLen = res.get("vSenhaLen") instanceof Number n ? n.intValue() : -1;
                if (emailLen <= 0 || senhaLen <= 0) {
                    log.warn("   ⚠️ Campos parecem vazios após preenchimento JS (emailLen={}, senhaLen={})", emailLen, senhaLen);
                } else {
                    log.info("   ✅ Credenciais preenchidas (emailLen={}, senhaLen={})", emailLen, senhaLen);
                    return true;
                }
            }
        } catch (Exception ignore) {}

        return false;
    }

    /**
     * Tenta concluir qualquer desafio interativo exibido pelo 99Freelas no próprio navegador.
     */
    private boolean resolverTurnstile(JavaDriverless driver) {
        try {
            log.info("🔒 Verificando se ha desafio interativo para resolver automaticamente...");

            LoginDriverlessDomHelper.scrollTurnstileParaCentro(driver, SELECTOR_TURNSTILE_IFRAME, SELECTOR_TURNSTILE_HOST);
            recusaGuard.notificarLoginAssistido(telegramService);

            long deadline = System.currentTimeMillis() + 90_000L;
            long ultimoClique = 0L;
            boolean screenshotFalhaSalvo = false;
            while (System.currentTimeMillis() < deadline) {
                String url = driver.getCurrentUrl();
                String token = LoginDriverlessDomHelper.lerRespostaTurnstile(driver);
                if (url != null && !url.contains("login")) {
                    log.info("   ✅ Navegador saiu da tela de login.");
                    return true;
                }
                if (token != null && !token.isBlank()) {
                    log.info("   ✅ Desafio interativo retornou token.");
                    return true;
                }

                long agora = System.currentTimeMillis();
                if (agora - ultimoClique >= 5_000L) {
                    boolean clicou = LoginDriverlessDomHelper.clicarTurnstileAutomaticamente(driver, SELECTOR_TURNSTILE_IFRAME, SELECTOR_TURNSTILE_HOST);
                    ultimoClique = agora;
                    if (!clicou && !screenshotFalhaSalvo) {
                        try {
                            driver.screenshot("turnstile_auto_nao_encontrado.png");
                            log.warn("   Screenshot salvo: turnstile_auto_nao_encontrado.png");
                        } catch (Exception ignore) {}
                        screenshotFalhaSalvo = true;
                    }
                }

                TimeUnit.SECONDS.sleep(2);
            }

            log.warn("   ⚠️ Tempo esgotado tentando resolver desafio automaticamente.");
            return false;

        } catch (Exception e) {
            log.error("   ❌ Erro ao resolver desafio interativo: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Clica no botão "Entrar" (#btnEfetuarLogin).
     */
    private void clicarBotaoLogin(JavaDriverless driver) {
        try {
            WebElement btnLogin = LoginDriverlessDomHelper.findFirstUsableElement(driver, new String[]{
                "#btnEfetuarLogin",
                "button[type='submit']",
                "button.btn-green",
                "form button[type='submit']"
            });
            if (btnLogin != null) {
                // Evita click() em elemento "0 area" usando Pointer no centro do botão.
                double[] loc = btnLogin.getLocation().get();
                double[] size = btnLogin.getSize().get();
                int x = (int) Math.round(loc[0] + (size[0] / 2.0));
                int y = (int) Math.round(loc[1] + (size[1] / 2.0));
                driver.getPointer().click(x, y, true, 0.6, 2.0, 18.0).get();
                log.info("   ✅ Click no botão Entrar via Pointer CDP em ({}, {})", x, y);
                return;
            }
        } catch (Exception e) {
            log.debug("   Click CDP no botão falhou: {}", e.getMessage());
        }

        // Fallback JS (quando CDP element lookup falha)
        try {
            driver.executeScript(
                "var btn = document.querySelector('#btnEfetuarLogin, button[type=submit], button.btn-green');" +
                    "if(btn) btn.click();"
            );
            log.info("   ✅ Click no botão via JS");
        } catch (Exception ex) {
            log.error("   ❌ Erro ao clicar botão: {}", ex.getMessage());
        }
    }

    // Seletores dinâmicos escolhidos em tempo de execução.
    // Mantidos como métodos para poder evoluir sem espalhar strings no código.
    private String seletorEmailDinamico() {
        return "#email, input[placeholder='Email'], input[placeholder='E-mail'], input[name='email'], input[type='email'], input[autocomplete='username']";
    }

    private String seletorSenhaDinamico() {
        return "#senha, input[placeholder='Senha'], input[name='senha'], input[name='password'], input[type='password'], input[autocomplete='current-password']";
    }

    private void aguardarElementoCdp(JavaDriverless driver, String[] selectors, int timeoutSeconds, String screenshotOnFail) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            for (String sel : selectors) {
                if (sel != null && !sel.isBlank() && LoginDriverlessDomHelper.elementoVisivel(driver, sel)) {
                    return;
                }
            }
            try {
                TimeUnit.MILLISECONDS.sleep(350);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        log.warn("   ⚠️ Timeout aguardando elemento via CDP. Tentados: {}", String.join(", ", selectors));
        try {
            driver.screenshot(screenshotOnFail);
            log.warn("   Screenshot salvo: {}", screenshotOnFail);
        } catch (Exception ignore) {}
        return;
    }

    /**
     * Exporta cookies via CDP Network.getAllCookies e salva em arquivo.
     * Usa o método nativo getCookies() do JavaDriverless que retorna
     * TODOS os cookies (incluindo HttpOnly, que document.cookie não pega).
     */
    private boolean exportarESalvarCookies(JavaDriverless driver) {
        try {
            List<Map<String, Object>> cookies = driver.getCookies();
            log.info("   Cookies CDP encontrados: {}", cookies.size());

            Map<String, String> cookieMap = new HashMap<>();
            for (Map<String, Object> cookie : cookies) {
                String name = (String) cookie.get("name");
                String value = (String) cookie.get("value");
                if (name != null && value != null) {
                    cookieMap.put(name, value);
                }
            }

            // Verificar cookies essenciais
            boolean temSessao = cookieMap.containsKey("JSESSIONID") ||
                                cookieMap.containsKey("sgcn") ||
                                cookieMap.containsKey("_freelas_session");

            if (temSessao) {
                log.info("   ✅ Cookies de sessão encontrados!");
            } else {
                log.warn("   ⚠️ Cookies de sessão não encontrados. Keys: {}", cookieMap.keySet());
            }

            // Salvar em arquivo
            salvarCookies(cookieMap);
            autenticacaoService.carregarCookiesJson(objectMapper.writeValueAsString(cookieMap));
            log.info("✅ {} cookies exportados e salvos", cookieMap.size());
            return true;

        } catch (Exception e) {
            log.error("❌ Erro ao exportar cookies: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Salva cookies em arquivo JSON para persistência.
     */
    private void salvarCookies(Map<String, String> cookies) {
        try {
            Path arquivo = Paths.get(arquivoCookies);
            Path dir = arquivo.getParent();
            if (dir != null && !Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(arquivo.toFile(), cookies);
            log.info("   Cookies salvos em {}", arquivoCookies);
        } catch (Exception e) {
            log.error("   Erro ao salvar cookies: {}", e.getMessage());
        }
    }
}
