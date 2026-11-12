package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.FreelancerBrConfig;
import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.BrowserFingerprintProfile;
import io.github.selenium.javaDriverless.types.ChromeOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.io.InputStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chrome real para as duas operacoes do Freelancer.com.br que exigem navegador.
 *
 * <p>Motivo medido: a listagem publica {@code /projetos} e a unica rota do site
 * protegida por Cloudflare Managed Challenge — a area logada
 * ({@code /account/*}, {@code /project/*}, {@code /csrfresh}) responde 200 por
 * HTTP puro com fingerprint de Chrome. Ou seja, so a DESCOBERTA precisa de
 * navegador; envio e inbox nao precisam.</p>
 *
 * <p>O login ({@code /login} + POST {@code /conecte-se}) tambem nao tem captcha,
 * entao o Chrome renova a sessao sozinho e exporta os cookies — incluindo o
 * {@code cf_clearance}, que fica atrelado ao IP de saida. Como no container o
 * Chrome e o cliente HTTP compartilham o mesmo IP, os cookies servem aos dois.</p>
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
     * quando o Chrome trava.</p>
     *
     * <p>Pool LIMITADO a 2 threads; a concorrencia de Chrome segue serializada pelo {@link ChromeSessaoLock}.</p>
     */
    /** Rejeicoes seguidas ate recriar o executor (threads presas em CDP sem timeout). */
    static final int LIMITE_REJEICOES_RECRIAR = 3;

    private final AtomicInteger rejeicoesSeguidas = new AtomicInteger();

    volatile ThreadPoolExecutor navegadorExecutor = criarNavegadorExecutor();

    private static ThreadPoolExecutor criarNavegadorExecutor() {
        return new ThreadPoolExecutor(
            0, 2, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            runnable -> {
                Thread t = new Thread(runnable, "freelancerbr-browser");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    }

    static boolean deveRecriarAposRejeicoes(int rejeicoes) {
        return rejeicoes >= LIMITE_REJEICOES_RECRIAR;
    }

    /** Recria o executor e interrompe o antigo; threads presas em CDP nativo sao daemon. */
    private synchronized ThreadPoolExecutor recriarNavegadorExecutor() {
        ThreadPoolExecutor antigo = navegadorExecutor;
        navegadorExecutor = criarNavegadorExecutor();
        rejeicoesSeguidas.set(0);
        if (antigo != null) {
            antigo.shutdownNow();
        }
        log.error("Freelancer.com.br: executor do navegador recriado apos {} rejeicoes seguidas "
            + "(threads presas em chamada CDP sem timeout); discovery/envio retomam sem reiniciar o container.",
            LIMITE_REJEICOES_RECRIAR);
        return navegadorExecutor;
    }

    /** Teto absoluto para qualquer operacao de navegador, do lancamento ao quit. */
    private static final long TIMEOUT_OPERACAO_SEGUNDOS = 150;

    /** Timeout efetivo da operacao; pacote para teste simular travamento sem esperar 150s. */
    long timeoutOperacaoSegundos = TIMEOUT_OPERACAO_SEGUNDOS;

    /**
     * Chrome RESIDENTE: nasce uma vez e fica vivo entre os ciclos (modelo
     * bet365Higor). O discovery anterior lancava um Chrome novo a cada ciclo;
     * cada cold-start reprovava no Managed Challenge de /projetos e o ciclo
     * fechava em 0 projetos. Com o navegador residente, a sessao (cookies,
     * clearance, historico) persiste entre os ciclos.
     */
    volatile JavaDriverless driverResidente;

    /** Driver cuja construcao terminou mas ainda nao virou residente. */
    private volatile JavaDriverless driverEmConstrucao;

    /** Timeout durante a construcao: quem criar o driver deve mata-lo em vez de promove-lo. */
    private volatile boolean construcaoAbortada;

    /** Ultimo instante em que uma operacao terminou com o lock do Chrome em maos. */
    volatile long ultimoUsoMillis;

    /** Fabrica do driver; pacote para teste injetar driver falso. */
    FabricaDriver fabricaDriver = FreelancerBrBrowser::criarDriverPadrao;

    interface FabricaDriver {
        JavaDriverless criar(ChromeOptions options) throws Exception;
    }

    private static JavaDriverless criarDriverPadrao(ChromeOptions options) throws Exception {
        try {
            return new JavaDriverless("FreelancerBr", options, true);
        } catch (Exception e) {
            return new JavaDriverless("FreelancerBrTmp-" + System.currentTimeMillis(), options, false);
        }
    }

    /** Minutos de ociosidade ate fechar o Chrome residente; {@code <= 0} desativa. */
    @org.springframework.beans.factory.annotation.Value("${freelancerbr.chrome-ocioso-minutos:10}")
    private long chromeOciosoMinutos;

    /** Perfil extraido do classpath para um arquivo real (fromJson exige Path). */
    private static volatile Path perfilExtraido;

    /** Desanexa o driver residente; com {@code esperado != null}, so se ainda for o mesmo. */
    synchronized JavaDriverless desanexarDriver(JavaDriverless esperado) {
        if (driverResidente == null || (esperado != null && driverResidente != esperado)) {
            return null;
        }
        JavaDriverless driver = driverResidente;
        driverResidente = null;
        return driver;
    }

    /** PID do Chrome; -1 quando o driver nao responde. */
    private static long pidDo(JavaDriverless driver) {
        try {
            return driver.getPid();
        } catch (Exception ignore) {
            return -1;
        }
    }

    /** Fecha o Chrome com quit em thread daemon (15s) e kill da arvore como fallback. */
    private void fecharDriver(JavaDriverless driver, String motivo) {
        if (driver == null) {
            return;
        }
        long pid = pidDo(driver);
        AtomicBoolean quitFalhou = new AtomicBoolean(false);
        Thread quit = new Thread(() -> {
            try {
                driver.quit();
            } catch (Exception e) {
                quitFalhou.set(true);
            }
        }, "freelancerbr-quit");
        quit.setDaemon(true);
        quit.start();
        try {
            quit.join(15_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (quit.isAlive() || quitFalhou.get()) {
            matarArvore(pid);
        }
        log.info("Freelancer.com.br: Chrome encerrado ({}); pid={}.", motivo, pid);
    }

    /** Mata o processo do Chrome e seus descendentes (renderers, GPU, zygote). */
    private static void matarArvore(long pid) {
        if (pid <= 0) {
            return;
        }
        try {
            ProcessHandle.of(pid).ifPresent(p -> { p.descendants().forEach(ProcessHandle::destroyForcibly); p.destroyForcibly(); });
        } catch (Exception ignore) {
        }
    }

    /** Mata o Chrome residente SEM quit (CDP travado); a thread presa libera o lock. */
    private void matarDriverResidente(String motivo) {
        construcaoAbortada = true;
        JavaDriverless driver = desanexarDriver(null);
        JavaDriverless emConstrucao = driverEmConstrucao;
        if (driver == null && emConstrucao == null) {
            return;
        }
        if (driver != null) {
            long pid = pidDo(driver);
            matarArvore(pid);
            log.warn("Freelancer.com.br: Chrome encerrado a forca ({}); pid={} (CDP travado, sem quit).", motivo, pid);
        }
        if (emConstrucao != null && emConstrucao != driver) {
            matarArvore(pidDo(emConstrucao));
        }
    }

    @jakarta.annotation.PreDestroy
    void desligarChromeResidente() {
        fecharDriver(desanexarDriver(null), "desligamento");
    }

    /** {@code minutos > 0} e o ultimo uso ja passou do limite de ociosidade. */
    static boolean deveFecharPorOciosidade(long ultimoUsoMillis, long agoraMillis, long minutos) {
        return minutos > 0 && ultimoUsoMillis > 0 && agoraMillis - ultimoUsoMillis >= minutos * 60_000L;
    }

    /** Fecha o Chrome residente apos N minutos sem uso (perfil em disco preservado). */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    void fecharChromeOcioso() {
        if (driverResidente == null
            || !deveFecharPorOciosidade(ultimoUsoMillis, System.currentTimeMillis(), chromeOciosoMinutos)) {
            return;
        }
        if (!chromeSessaoLock.tentarAdquirir()) {
            return;
        }
        try {
            if (driverResidente == null
                || !deveFecharPorOciosidade(ultimoUsoMillis, System.currentTimeMillis(), chromeOciosoMinutos)) {
                return;
            }
            fecharDriver(desanexarDriver(null), "ocioso ha " + chromeOciosoMinutos + " min");
            log.info("Freelancer.com.br: Chrome fechado apos {} min ocioso (perfil e cookies preservados em disco).",
                chromeOciosoMinutos);
        } finally {
            chromeSessaoLock.liberar();
        }
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
            FreelancerBrSessaoBrowser.aguardarSaidaDoDesafio(driver);
            Object html1 = driver.executeScript("document.documentElement.outerHTML");
            String primeiraTentativa = html1 == null ? "" : html1.toString();
            if (FreelancerBrSessaoBrowser.pareceDesafio(primeiraTentativa)) {
                // Segunda rodada: com os cookies de sessao ja aquecidos, o
                // desafio as vezes cai na segunda passada.
                log.info("Freelancer.com.br: desafio persistiu; tentando segunda rodada.");
                driver.get(config.getUrlBase() + "/projetos");
                FreelancerBrSessaoBrowser.aguardarSaidaDoDesafio(driver);
            }
            // Persiste o cf_clearance renovado para o transporte HTTP reusar.
            try {
                FreelancerBrSessaoBrowser.exportarCookies(driver, config, objectMapper);
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
            FreelancerBrSessaoBrowser.aguardarSaidaDoDesafio(driver);
            FreelancerBrSessaoBrowser.preencherLogin(driver, config);
            TimeUnit.SECONDS.sleep(6);
            FreelancerBrSessaoBrowser.aguardarSaidaDoDesafio(driver);
            driver.get(config.getUrlBase() + "/account/inbox");
            FreelancerBrSessaoBrowser.aguardarSaidaDoDesafio(driver);
            Object logado = driver.executeScript(
                "document.documentElement.outerHTML.toLowerCase().includes('/account/logout')");
            if (!(logado instanceof Boolean b) || !b) {
                log.warn("Freelancer.com.br: login automatico nao autenticou.");
                return false;
            }
            return FreelancerBrSessaoBrowser.exportarCookies(driver, config, objectMapper);
        });
        return Boolean.TRUE.equals(ok);
    }

    <T> T comNavegador(AcaoNavegador<T> acao) {
        // Roda em thread daemon separada: se o Chrome travar, a thread do
        // @Scheduled e liberada pelo timeout do Future.
        Future<T> futuro;
        try {
            futuro = navegadorExecutor.submit(() -> executarComDriver(acao));
            rejeicoesSeguidas.set(0);
        } catch (RejectedExecutionException e) {
            int rejeicoes = rejeicoesSeguidas.incrementAndGet();
            log.warn("Freelancer.com.br: navegador ocupado (pool no limite); adiando para a proxima rodada.");
            if (deveRecriarAposRejeicoes(rejeicoes)) {
                recriarNavegadorExecutor();
            }
            return null;
        }
        try {
            return futuro.get(timeoutOperacaoSegundos, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            futuro.cancel(true);
            // CDP travado: quit() travaria, entao mata o processo; a thread presa
            // sai por excecao e libera o ChromeSessaoLock.
            matarDriverResidente("timeout");
            log.warn("Freelancer.com.br: operacao excedeu {}s; Chrome encerrado a forca "
                + "(scheduler segue livre para as demais plataformas).", timeoutOperacaoSegundos);
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
        JavaDriverless driver = driverResidente;
        try {
            ChromeOptions options = new ChromeOptions();
            options.addArgument("--no-sandbox");
            options.addArgument("--disable-dev-shm-usage");
            options.addArgument("--window-size=1440,900");
            options.setFingerprintProfile(perfilDoNavegador());
            if (driver == null) {
                construcaoAbortada = false;
                JavaDriverless novo = fabricaDriver.criar(options);
                driverEmConstrucao = novo;
                if (construcaoAbortada || Thread.currentThread().isInterrupted()) {
                    driverEmConstrucao = null;
                    construcaoAbortada = false;
                    matarArvore(pidDo(novo));
                    log.warn("Freelancer.com.br: Chrome criado apos timeout descartado (orfao evitado).");
                    return null;
                }
                driver = novo;
                driverResidente = driver;
                driverEmConstrucao = null;
                log.info("Freelancer.com.br: Chrome residente nascido (fecha apos ociosidade).");
            }
            return acao.executar(driver);
        } catch (Exception e) {
            log.warn("Freelancer.com.br: falha na operacao com navegador: {}", e.getMessage());
            // Descarta APENAS o driver desta rodada (nunca um criado por outra rodada).
            fecharDriver(desanexarDriver(driver), "falha");
            return null;
        } finally {
            ultimoUsoMillis = System.currentTimeMillis();
            chromeSessaoLock.liberar();
        }
    }

    /**
     * Perfil de identidade HONESTA medido no container (22/09/2026): com o
     * brLinux() o WebGL spoofado ("NVIDIA GTX 1650") confrontava o renderer
     * real do container — o Cloudflare classifica como bot e serve um PoW que
     * a CPU nao completa (medido: 150s+ com a pagina do desafio saturada,
     * titulo "Um momento..." ate o timeout). Com superficie real (WebGL/
     * canvas nativos, pt-BR, America/Sao_Paulo) o desafio passa em ~10s e o
     * cf_clearance nasce (medido: 10 cards em /projetos, cookie presente).
     *
     * <p>O fallback para brLinux() existe so para ambientes sem o recurso no
     * classpath (dev local fora da imagem).</p>
     */
    private BrowserFingerprintProfile perfilDoNavegador() {
        try {
            return BrowserFingerprintProfile.fromJson(extrairPerfilParaTemp());
        } catch (Exception e) {
            log.warn("Freelancer.com.br: perfil honesto indisponivel ({}); usando brLinux.", e.getMessage());
            return BrowserFingerprintProfile.brLinux();
        }
    }

    private static Path extrairPerfilParaTemp() throws Exception {
        Path destino = perfilExtraido;
        if (destino != null && Files.exists(destino)) {
            return destino;
        }
        synchronized (FreelancerBrBrowser.class) {
            if (perfilExtraido != null && Files.exists(perfilExtraido)) {
                return perfilExtraido;
            }
            Path temp = Files.createTempFile("freelancerbr-perfil", ".json");
            try (InputStream in = FreelancerBrBrowser.class
                    .getResourceAsStream("/freelancerbr/perfil-navegador.json")) {
                if (in == null) {
                    throw new IllegalStateException("recurso freelancerbr/perfil-navegador.json ausente");
                }
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            perfilExtraido = temp;
            return temp;
        }
    }

    @FunctionalInterface
    interface AcaoNavegador<T> {
        T executar(JavaDriverless driver) throws Exception;
    }
}
