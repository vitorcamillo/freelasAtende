package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.FreelancerBrConfig;
import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * Chrome real para as duas operacoes do Freelancer.com.br que exigem navegador.
 *
 * <p>Motivo medido: a listagem publica {@code /projetos} e a unica rota do site
 * protegida por Cloudflare Managed Challenge — a area logada
 * ({@code /account/*}, {@code /project/*}, {@code /csrfresh}) responde 200 por
 * HTTP puro. Ou seja, so a DESCOBERTA precisa de
 * navegador; envio e inbox nao precisam.</p>
 *
 * <p>O login ({@code /login} + POST {@code /conecte-se}) nao pede verificacao
 * humana, entao o Chrome pode renovar a sessao e exportar os cookies; se o
 * Cloudflare barrar, a sessao depende dos cookies importados manualmente.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class FreelancerBrBrowser {

    private final FreelancerBrConfig config;
    private final ObjectMapper objectMapper;
    private final ChromeSessaoLock chromeSessaoLock;
    private final FreelancerBrClient client;

    /**
     * Executor DEDICADO, fora do pool de 3 threads do {@code @Scheduled}
     * ({@link com.freelasatende.config.SchedulingConfig}).
     *
     * <p>Motivo (incidente real): o Chrome sob Xvfb pode travar internamente
     * (CDP sem resposta) e {@code driver.executeScript}/{@code driver.quit} nao
     * tem timeout proprio. Rodando direto na thread do scheduler, isso prendia
     * a UNICA thread do polling por tempo indefinido — nenhuma outra plataforma
     * (99Freelas, Workana, Upwork, Freelancer.com) era verificada enquanto o
     * Chrome do .com.br ficava preso. Medido: 5+ minutos sem nenhum log apos
     * "desafio do Cloudflare nao liberou dentro do tempo". Isolar numa thread
     * daemon separada com timeout rigido devolve a thread do scheduler mesmo
     * quando o Chrome trava — a thread presa e apenas abandonada.</p>
     */
    private static final ExecutorService NAVEGADOR_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable, "freelancerbr-browser");
        t.setDaemon(true);
        return t;
    });

    /** Teto absoluto para qualquer operacao de navegador, do lancamento ao quit. */
    private static final long TIMEOUT_OPERACAO_SEGUNDOS = 150;

    /**
     * Chrome RESIDENTE: nasce uma vez e fica vivo entre os ciclos (modelo
     * bet365Higor). O discovery anterior lancava um Chrome novo a cada ciclo;
     * cada cold-start reprovava no Managed Challenge de /projetos e o ciclo
     * fechava em 0 projetos. Com o navegador residente, a sessao (cookies,
     * clearance, historico) persiste entre os ciclos.
     */
    private JavaDriverless driverResidente;

    private void encerrarDriverResidente() {
        if (driverResidente != null) {
            try {
                driverResidente.quit();
            } catch (Exception ignore) {
            }
            driverResidente = null;
        }
    }

    @jakarta.annotation.PreDestroy
    void desligarChromeResidente() {
        encerrarDriverResidente();
    }

    /** Baixa o HTML da listagem de projetos passando pelo desafio do Cloudflare. */
    String baixarListagemProjetos() {
        // A area logada responde 200 por HTTP com os cookies renovados; a rota
        // /projetos, porem, costuma aplicar o desafio APENAS quando a requisicao
        // chega sem um cf_clearance valido. Tenta o caminho barato primeiro e
        // so sobe o Chrome real quando o Cloudflare devolve o desafio.
        String porHttp = client.baixarListagemProjetosPorHttp();
        if (porHttp != null) {
            log.info("FreelancerBR discovery resolvido por HTTP ({} bytes).", porHttp.length());
            return porHttp;
        }
        log.info("FreelancerBR discovery HTTP não resolveu; subindo Chrome");
        String htmlRetornado = comNavegador(driver -> {
            // Carrega a sessao armazenada antes de abrir a rota desafiada:
            // com o cf_clearance valido o desafio nem aparece, e quando
            // aparece, o Chrome ja chega logado.
            driver.get(config.getUrlBase() + "/");
            for (Map<String, Object> cookie : client.cookiesParaChrome()) {
                try {
                    driver.addCookie(cookie);
                } catch (Exception e) {
                    log.debug("Cookie {} nao injetado: {}", cookie.get("name"), e.getMessage());
                }
            }
            driver.get(config.getUrlBase() + "/projetos");
            aguardarSaidaDoDesafio(driver);
            Object html1 = driver.executeScript("document.documentElement.outerHTML");
            String primeiraTentativa = html1 == null ? "" : html1.toString();
            if (pareceDesafio(primeiraTentativa)) {
                // Segunda rodada: com os cookies de sessao ja aquecidos, o
                // desafio as vezes cai na segunda passada.
                log.info("Freelancer.com.br: desafio persistiu; tentando segunda rodada.");
                driver.get(config.getUrlBase() + "/projetos");
                aguardarSaidaDoDesafio(driver);
            }
            // Persiste o cf_clearance renovado para o transporte HTTP reusar.
            try {
                exportarCookies(driver);
            } catch (Exception e) {
                log.warn("Freelancer.com.br: nao consegui exportar os cookies do discovery: {}", e.getMessage());
            }
            Object html = driver.executeScript("document.documentElement.outerHTML");
            return html == null ? "" : html.toString();
        });
        if (htmlRetornado != null) {
            log.info("FreelancerBR discovery por Chrome retornou {} bytes.", htmlRetornado.length());
        } else {
            log.warn("FreelancerBR discovery por Chrome não retornou HTML.");
        }
        return htmlRetornado;
    }

    /**
     * Faz login com as credenciais do .com.br e persiste os cookies da sessao.
     * Retorna {@code true} quando a sessao autenticada foi exportada.
     */
    boolean renovarSessao() {
        if (!config.temCredenciais()) {
            log.warn("Freelancer.com.br: sem FREELANCERBR_EMAIL/FREELANCERBR_PASSWORD; login automatico indisponivel.");
            return false;
        }
        Boolean ok = comNavegador(driver -> {
            driver.get(config.getUrlBase() + "/login");
            aguardarSaidaDoDesafio(driver);
            preencherLogin(driver);
            TimeUnit.SECONDS.sleep(6);
            aguardarSaidaDoDesafio(driver);
            driver.get(config.getUrlBase() + "/account/inbox");
            aguardarSaidaDoDesafio(driver);
            Object logado = driver.executeScript(
                "document.documentElement.outerHTML.toLowerCase().includes('/account/logout')");
            if (!(logado instanceof Boolean b) || !b) {
                log.warn("Freelancer.com.br: login automatico nao autenticou.");
                return false;
            }
            return exportarCookies(driver);
        });
        return Boolean.TRUE.equals(ok);
    }

    private void preencherLogin(JavaDriverless driver) {
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
    private boolean exportarCookies(JavaDriverless driver) throws Exception {
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

    /**
     * Aguarda a saída passiva do desafio do Cloudflare.
     *
     * <p>O código público não interage com o desafio: apenas observa título e
     * corpo da página. O desafio às vezes libera sozinho no Chrome real; se não
     * liberar, a sessão depende dos cookies manuais.</p>
     */
    private void aguardarSaidaDoDesafio(JavaDriverless driver) throws Exception {
        long limite = System.currentTimeMillis() + 45_000L;
        String t = "";
        String c = "";
        while (System.currentTimeMillis() < limite) {
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

    private <T> T comNavegador(AcaoNavegador<T> acao) {
        // Roda em thread daemon separada, fora do pool do scheduler: se o Chrome
        // travar, a thread do @Scheduled e liberada pelo timeout do Future em vez
        // de ficar presa indefinidamente (a thread travada e apenas abandonada).
        Future<T> futuro = NAVEGADOR_EXECUTOR.submit(() -> executarComDriver(acao));
        try {
            return futuro.get(TIMEOUT_OPERACAO_SEGUNDOS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            futuro.cancel(true);
            log.warn("Freelancer.com.br: operacao com navegador excedeu {}s; abandonando "
                + "(scheduler segue livre para as demais plataformas).", TIMEOUT_OPERACAO_SEGUNDOS);
            return null;
        } catch (Exception e) {
            log.warn("Freelancer.com.br: falha na operacao com navegador: {}", e.getMessage());
            return null;
        }
    }

    private <T> T executarComDriver(AcaoNavegador<T> acao) {
        if (!chromeSessaoLock.adquirir("Freelancer.com.br")) {
            return null;
        }
        try {
            ChromeOptions options = new ChromeOptions();
            options.addArgument("--no-sandbox");
            options.addArgument("--disable-dev-shm-usage");
            options.addArgument("--window-size=1440,900");
            if (driverResidente == null) {
                try {
                    driverResidente = new JavaDriverless("FreelancerBr", options, true);
                } catch (Exception e) {
                    driverResidente = new JavaDriverless("FreelancerBrTmp-" + System.currentTimeMillis(), options, false);
                }
                log.info("Freelancer.com.br: Chrome residente nascido (fica vivo entre os ciclos).");
            }
            return acao.executar(driverResidente);
        } catch (Exception e) {
            log.warn("Freelancer.com.br: falha na operacao com navegador: {}", e.getMessage());
            // Chrome pode ter morrido no meio da operacao: descarta para o
            // proximo ciclo renascer em vez de reaproveitar sessao quebrada.
            encerrarDriverResidente();
            return null;
        } finally {
            chromeSessaoLock.liberar();
        }
    }

    private static String jsString(String valor) {
        return "'" + valor.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    @FunctionalInterface
    private interface AcaoNavegador<T> {
        T executar(JavaDriverless driver) throws Exception;
    }
}
