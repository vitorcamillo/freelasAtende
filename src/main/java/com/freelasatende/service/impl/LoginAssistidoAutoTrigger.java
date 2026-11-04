package com.freelasatende.service.impl;

import com.freelasatende.controller.DashboardController;
import com.freelasatende.scheduler.PollingScheduler;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAssistidoAutoTrigger {

    private final ObjectProvider<LoginJavaDriverless> loginJavaDriverless;
    private final ObjectProvider<UpworkLoginDriverless> upworkLoginDriverless;
    private final ObjectProvider<FreelancerLoginDriverless> freelancerLoginDriverless;
    private final ObjectProvider<FreelancerBrBrowser> freelancerBrBrowser;
    private final AutenticacaoService autenticacaoService;
    // ObjectProvider evita dependencia circular: PollingScheduler ja depende deste bean.
    private final ObjectProvider<PollingScheduler> pollingSchedulerProvider;

    private final AtomicBoolean loginEmAndamento = new AtomicBoolean(false);
    private volatile long ultimaTentativaMillis = 0L;
    private volatile long ultimoAvisoIndisponivelMillis = 0L;

    @Value("${auth.auto-login.enabled:true}")
    private boolean autoLoginEnabled = true;

    @Value("${auth.auto-login.cooldown-seconds:180}")
    private long cooldownSeconds = 180;

    public void dispararSeSessaoInvalida(String origem) {
        if (!autoLoginEnabled) {
            avisarIndisponivel(origem, "auto-login desativado por configuracao");
            return;
        }

        if (!loginDisponivel(origem)) {
            avisarIndisponivel(origem, "login assistido indisponivel para " + extrairPlataformaKey(origem));
            return;
        }

        if (loginEmAndamento.get()) {
            log.debug("Login assistido automatico ja esta em andamento ({})", origem);
            return;
        }

        long agora = System.currentTimeMillis();
        long cooldownMillis = Math.max(30, cooldownSeconds) * 1000L;
        if (agora - ultimaTentativaMillis < cooldownMillis) {
            log.debug("Login assistido automatico em cooldown ({})", origem);
            return;
        }

        if (!loginEmAndamento.compareAndSet(false, true)) {
            return;
        }
        ultimaTentativaMillis = agora;

        DashboardController.enviarLog("WARN", "Sessao invalida: iniciando login assistido automatico");
        CompletableFuture.runAsync(() -> executarLogin(origem));
    }

    /** origem vem como "polling:99FREELAS" ou "inbox:99FREELAS"; pega a parte após o último ':'. */
    private String extrairPlataformaKey(String origem) {
        if (origem == null) {
            return null;
        }
        int idx = origem.lastIndexOf(':');
        return idx >= 0 ? origem.substring(idx + 1) : origem;
    }

    private void avisarIndisponivel(String origem, String motivo) {
        long agora = System.currentTimeMillis();
        if (agora - ultimoAvisoIndisponivelMillis < 300_000L) {
            log.debug("Login assistido automatico indisponivel para origem {}: {}", origem, motivo);
            return;
        }
        ultimoAvisoIndisponivelMillis = agora;
        log.warn("Login assistido automatico indisponivel para origem {}: {}", origem, motivo);
        DashboardController.enviarLog("WARN", "Login assistido automatico indisponivel: " + motivo);
    }

    void executarLogin(String origem) {
        try {
            log.info("Iniciando login assistido automatico por {}", origem);
            ResultadoLogin resultado = executarLoginDaPlataforma(origem);
            boolean executado = resultado.executado();
            boolean sessaoValida = resultado.sessaoValida();
            if (sessaoValida) {
                // Login renovou a sessao: libera o cooldown armado pelo scheduler antes do login,
                // senao o polling fica ~cooldown inteiro sem trabalhar mesmo ja logado.
                PollingScheduler scheduler = pollingSchedulerProvider.getIfAvailable();
                if (scheduler != null) {
                    scheduler.limparCooldownSessaoPorKey(extrairPlataformaKey(origem));
                }
            }
            DashboardController.enviarLog(
                sessaoValida ? "INFO" : "WARN",
                sessaoValida
                    ? "Login assistido automatico concluiu com sessao valida"
                    : "Login assistido automatico concluiu sem validar sessao"
            );
            log.info(
                "Login assistido automatico finalizado por {}: executado={}, sessaoValida={}",
                origem,
                executado,
                sessaoValida
            );
        } catch (Exception e) {
            log.warn("Login assistido automatico falhou por {}: {}", origem, e.getMessage());
            DashboardController.enviarLog("WARN", "Login assistido automatico falhou: " + e.getMessage());
        } finally {
            loginEmAndamento.set(false);
        }
    }

    private boolean loginDisponivel(String origem) {
        MarketplaceLoginTarget target = MarketplaceLoginTarget.fromOrigem(origem);
        if (target == MarketplaceLoginTarget.UPWORK) {
            return upworkLoginDriverless.getIfAvailable() != null;
        }
        if (target == MarketplaceLoginTarget.FREELANCER_BR) {
            return freelancerBrBrowser.getIfAvailable() != null;
        }
        if (target == MarketplaceLoginTarget.FREELANCER) {
            return freelancerLoginDriverless.getIfAvailable() != null;
        }
        return loginJavaDriverless.getIfAvailable() != null;
    }

    private ResultadoLogin executarLoginDaPlataforma(String origem) {
        MarketplaceLoginTarget target = MarketplaceLoginTarget.fromOrigem(origem);
        if (target == MarketplaceLoginTarget.UPWORK) {
            UpworkLoginDriverless login = upworkLoginDriverless.getIfAvailable();
            boolean executado = login != null && login.executarLogin();
            return new ResultadoLogin(executado, executado);
        }
        if (target == MarketplaceLoginTarget.FREELANCER_BR) {
            // Renova a sessao do .com.br no Chrome real e reexporta os cookies
            // (inclui cf_clearance, que e atrelado ao IP de saida do container).
            FreelancerBrBrowser browser = freelancerBrBrowser.getIfAvailable();
            boolean executado = browser != null && browser.renovarSessao();
            return new ResultadoLogin(executado, executado);
        }
        if (target == MarketplaceLoginTarget.FREELANCER) {
            FreelancerLoginDriverless login = freelancerLoginDriverless.getIfAvailable();
            boolean executado = login != null && login.executarLogin();
            return new ResultadoLogin(executado, executado);
        }
        LoginJavaDriverless login = loginJavaDriverless.getIfAvailable();
        boolean executado = login != null && login.executarLogin();
        return new ResultadoLogin(executado, autenticacaoService.verificarSessaoValida());
    }

    private record ResultadoLogin(boolean executado, boolean sessaoValida) {
    }
}
