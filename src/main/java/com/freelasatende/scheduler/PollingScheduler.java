package com.freelasatende.scheduler;

import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.controller.DashboardController;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.service.interfaceServico.AutoAtendimentoService;
import com.freelasatende.service.interfaceServico.ControleOperacionalService;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import com.freelasatende.service.interfaceServico.MarketplaceCapabilities;
import com.freelasatende.service.interfaceServico.TelegramService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.impl.LoginAssistidoAutoTrigger;
import com.freelasatende.service.impl.MarketplaceSessionFailure;
import com.freelasatende.service.impl.MarketplaceAdapterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scheduler que verifica novos projetos periodicamente.
 * Suporta pausa/retomada via comandos Telegram.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PollingScheduler {

    private final MarketplaceAdapterRegistry marketplaceAdapterRegistry;
    private final AutoAtendimentoService autoAtendimentoService;
    private final TelegramService telegramService;
    private final GerenciadorConexoesService gerenciadorConexoes;
    private final ControleOperacionalService controleOperacionalService;
    private final LoginAssistidoAutoTrigger loginAssistidoAutoTrigger;
    private final InteracaoRepository interacaoRepository;

    @Value("${scheduling.enabled:true}")
    private boolean schedulingEnabled = true;

    @Value("${sistema.iniciar-pausado:false}")
    private boolean iniciarPausado = false;

    @Value("${sistema.modo-padrao:ENSINAMENTO}")
    private ModoSistema modoInicial = ModoSistema.ENSINAMENTO;

    @Value("${sistema.auto-ativo-inicial:false}")
    private boolean autoAtivoInicial = false;

    @Value("${telegram.diario-operacional.enabled:true}")
    private boolean diarioOperacionalEnabled = true;

    @Value("${telegram.diario-operacional.arquivo:data/diario-operacional-ultimo.txt}")
    private String arquivoDiarioOperacional = "data/diario-operacional-ultimo.txt";

    @Value("${telegram.checkin-ensino.enabled:true}")
    private boolean checkinEnsinoEnabled = true;

    @Value("${telegram.checkin-ensino.intervalo-minutos:30}")
    private long checkinEnsinoIntervaloMinutos = 30;

    @Value("${telegram.checkin-ensino.arquivo:data/checkin-ensino-ultimo.txt}")
    private String arquivoCheckinEnsino = "data/checkin-ensino-ultimo.txt";

    @Value("${real.write.enabled:false}")
    private boolean realWriteEnabled = false;

    @Value("${real.write.simulate:false}")
    private boolean realWriteSimulateEnabled = false;

    @Value("${sistema.sessao-invalida-cooldown-segundos:120}")
    private long sessaoInvalidaCooldownSegundos = 120;

    @Value("${sistema.inbox-limite-99freelas-por-ciclo:5}")
    private int inboxLimite99FreelasPorCiclo = 5;

    @Value("${sistema.inbox-limite-workana-por-ciclo:10}")
    private int inboxLimiteWorkanaPorCiclo = 10;

    @Value("${sistema.inbox-limite-padrao-por-ciclo:5}")
    private int inboxLimitePadraoPorCiclo = 5;

    @Value("${ia.auth-alerta-cooldown-segundos:3600}")
    private long iaAuthAlertaCooldownSegundos = 3600;

    private final AtomicReference<ModoSistema> modoAtual =
        new AtomicReference<>(ModoSistema.ENSINAMENTO);

    private final AtomicBoolean pausado = new AtomicBoolean(false);
    private final AtomicBoolean autoAtivo = new AtomicBoolean(false);
    private final AtomicBoolean iaBloqueadaPorConta = new AtomicBoolean(false);
    private final AtomicBoolean verificacaoEmAndamento = new AtomicBoolean(false);
    private final AtomicInteger errosConsecutivos = new AtomicInteger(0);
    private final Map<PlataformaFreela, Long> proximaTentativaSessaoPorPlataforma = new ConcurrentHashMap<>();
    private final AlertaSessaoManual alertaSessaoManual;
    private PollingSchedulerErrorHandler errorHandler;
    private PollingSchedulerOperatorNotifier operatorNotifier;
    private static final int MAX_ERROS_ANTES_NOTIFICAR = 3;

    @PostConstruct
    void init() {
        errorHandler();
        modoAtual.set(modoInicial);
        autoAtivo.set(autoAtivoInicial);
        if (iniciarPausado) {
            pausado.set(true);
            log.info("Sistema iniciado em modo PAUSADO por configuracao");
        }
    }

    @Scheduled(fixedDelayString = "${sistema.polling-intervalo:30}000")
    public void verificarProjetos() {
        if (!schedulingEnabled) {
            return;
        }

        if (!verificacaoEmAndamento.compareAndSet(false, true)) {
            log.debug("Verificacao anterior ainda em andamento, pulando ciclo.");
            return;
        }

        if (iaBloqueadaPorConta.get()) {
            log.debug("IA bloqueada por conta Codex; pulando ciclo ate troca de CODEX_AUTH_JSON_B64*.");
            verificacaoEmAndamento.set(false);
            return;
        }

        // Modo AUTO: se estava pausado e auto está ativo, retoma automaticamente
        if (pausado.get()) {
            if (autoAtivo.get()) {
                log.info("[AUTO] Sistema estava pausado, retomando automaticamente...");
                retomar();
            } else {
                log.debug("Sistema pausado, pulando verificação");
                verificacaoEmAndamento.set(false);
                return;
            }
        }

        log.debug("Verificando projetos...");
        DashboardController.enviarLog("DEBUG", "Verificando novos projetos...");

        try {
            // Verifica limite de conexões
            if (!gerenciadorConexoes.temConexoesDisponiveis()) {
                log.warn("Limite de conexões diárias atingido. Aguardando amanhã.");
                DashboardController.enviarLog("WARN", "Limite de conexoes diarias atingido");
                verificacaoEmAndamento.set(false);
                return;
            }

            long pendenciasAntes = contarPendenciasSeguro();
            ModoSistema modoCiclo = modoAtual.get();
            int projetosLidos = 0;

            List<MarketplaceAdapter> adapters = marketplaceAdapterRegistry.ativos();
            if (adapters.isEmpty()) {
                log.warn("Nenhum marketplace habilitado para polling");
                DashboardController.enviarLog("WARN", "Nenhum marketplace habilitado para polling");
                verificacaoEmAndamento.set(false);
                return;
            }

            // Discovery de todos primeiro; depois processa intercalando (ver FilasMarketplace).
            List<FilasMarketplace.Fila> filas = new ArrayList<>();
            for (MarketplaceAdapter adapter : adapters) {
                FilasMarketplace.Fila fila = descobrirMarketplace(adapter);
                if (fila != null) {
                    projetosLidos += fila.projetos().size();
                    filas.add(fila);
                }
            }
            FilasMarketplace.processarIntercalado(filas,
                projeto -> autoAtendimentoService.processarProjeto(projeto, modoCiclo),
                this::tratarFalhaMarketplace);
            errorHandler().reprocessarErrosTecnicosPendentes(modoCiclo);
            long pendenciasDepois = contarPendenciasSeguro();
            operatorNotifier().notificarDiarioOperacional(projetosLidos, pendenciasAntes, pendenciasDepois, modoCiclo);

            // Reset erros consecutivos em sucesso
            errosConsecutivos.set(0);

            if (projetosLidos == 0) {
                log.debug("Nenhum projeto novo encontrado");
                DashboardController.enviarLog("DEBUG", "Nenhum projeto novo encontrado");
            }

            } catch (Exception e) {
                log.error("Erro ao verificar projetos: {}", e.getMessage());
                DashboardController.enviarLog("ERROR", "Erro ao verificar projetos: " + e.getMessage());
                pausarPorProblemaContaCodex(e);
                errosConsecutivos.incrementAndGet();
                verificarErrosConsecutivos();
            } finally {
            verificacaoEmAndamento.set(false);
        }
    }

    private int limiteInboxPorCiclo(PlataformaFreela plataforma) {
        if (plataforma == PlataformaFreela.FREELAS_99) {
            return Math.max(1, inboxLimite99FreelasPorCiclo);
        }
        if (plataforma == PlataformaFreela.WORKANA) {
            return Math.max(1, inboxLimiteWorkanaPorCiclo);
        }
        return Math.max(1, inboxLimitePadraoPorCiclo);
    }

    /** Usado pelo {@link InboxScheduler}, que roda o ciclo de conversas em agenda propria. */
    boolean podeSincronizarInbox() {
        return !pausado.get() && !iaBloqueadaPorConta.get();
    }

    ModoSistema modoCicloAtual() {
        return modoAtual.get();
    }

    boolean sessaoEmCooldown(PlataformaFreela plataforma) {
        return System.currentTimeMillis()
            < proximaTentativaSessaoPorPlataforma.getOrDefault(plataforma, 0L);
    }

    /** Discovery de uma plataforma; devolve null quando nao ha o que processar neste ciclo. */
    private FilasMarketplace.Fila descobrirMarketplace(MarketplaceAdapter adapter) {
        PlataformaFreela plataforma = adapter.plataforma();
        if (plataforma == null) {
            log.warn("Adapter sem marketplace declarado ignorado pelo scheduler");
            return null;
        }
        try {
            long agora = System.currentTimeMillis();
            long proximaTentativa = proximaTentativaSessaoPorPlataforma.getOrDefault(
                plataforma,
                0L
            );
            if (agora < proximaTentativa) {
                long segundos = Math.max(1, (proximaTentativa - agora) / 1000);
                log.warn("Sessao {} invalida; aguardando cooldown de {}s antes de nova requisicao externa",
                    plataforma.label(), segundos);
                return null;
            }

            boolean precisaMarcarRenovacao = gerenciadorConexoes.precisaRenovarSessao();
            if (deveVerificarLoginAntesDoCiclo(plataforma) && !adapter.verificarLogin()) {
                registrarSessaoInvalida(plataforma, "polling");
                errosConsecutivos.incrementAndGet();
                verificarErrosConsecutivos();
                return null;
            }
            proximaTentativaSessaoPorPlataforma.remove(plataforma);
            if (precisaMarcarRenovacao) {
                gerenciadorConexoes.marcarSessaoRenovada();
            }

            List<Projeto> projetos = adapter.buscarProjetosNovos();
            log.info("Polling {}: discovery concluido, projetosLidos={}", plataforma.label(), projetos.size());
            return FilasMarketplace.Fila.de(plataforma, projetos);
        } catch (Exception e) {
            tratarFalhaMarketplace(plataforma, e);
            return null;
        }
    }

    private void tratarFalhaMarketplace(PlataformaFreela plataforma, Exception e) {
        if (MarketplaceSessionFailure.isSessaoMarketplaceInvalida(e)) {
            // Inclui plataformas sem precheck (ex.: FREELANCER_BR), em que a
            // falha de sessao chega pelo discovery/inbox e nao pelo verificarLogin.
            registrarSessaoInvalida(plataforma, "polling");
            return;
        }
        log.error("Erro ao verificar projetos em {}: {}", plataforma.label(), e.getMessage());
        DashboardController.enviarLog("ERROR",
            "Erro ao verificar projetos em " + plataforma.label() + ": " + e.getMessage());
        pausarPorProblemaContaCodex(e);
        errosConsecutivos.incrementAndGet();
        verificarErrosConsecutivos();
    }

    private boolean deveVerificarLoginAntesDoCiclo(PlataformaFreela plataforma) {
        // O discovery do Freelancer.com.br migrou para a API JSON publica (sem
        // cookie e fora do Cloudflare). Nao bloqueamos o ciclo pela sessao: a
        // inbox autenticada valida a sessao por conta propria e dispara o aviso
        // de renovacao manual quando os cookies expiram.
        return plataforma != PlataformaFreela.FREELANCER_BR;
    }

    void sincronizarInboxDaPlataforma(PlataformaFreela plataforma, ModoSistema modoCiclo) {
        try {
            autoAtendimentoService.sincronizarInbox(
                modoCiclo,
                limiteInboxPorCiclo(plataforma),
                List.of(plataforma)
            );
            alertaSessaoManual.registrarSucesso(plataforma);
        } catch (Exception e) {
            if (MarketplaceSessionFailure.isSessaoMarketplaceInvalida(e)) {
                log.warn("Inbox de {} indicou sessao invalida; acionando renovacao assistida", plataforma.label());
                registrarSessaoInvalida(plataforma, "inbox");
                return;
            }
            log.error("Erro ao sincronizar inbox em {}: {}", plataforma.label(), e.getMessage(), e);
            DashboardController.enviarLog(
                "ERROR",
                "Erro ao sincronizar inbox em " + plataforma.label() + ": " + e.getMessage()
            );
            errosConsecutivos.incrementAndGet();
            verificarErrosConsecutivos();
        }
    }

    /**
     * Limpa o cooldown de sessão inválida de uma plataforma após login assistido
     * bem-sucedido, para o polling retomar imediatamente em vez de esperar o
     * cooldown que foi armado antes do login.
     */
    public void limparCooldownSessaoPorKey(String plataformaKey) {
        if (plataformaKey == null || plataformaKey.isBlank()) {
            return;
        }
        boolean removido = proximaTentativaSessaoPorPlataforma.keySet()
            .removeIf(p -> p.key().equalsIgnoreCase(plataformaKey.trim()));
        if (removido) {
            log.info("Cooldown de sessao {} limpo apos login assistido bem-sucedido", plataformaKey.trim());
        }
    }

    private void registrarSessaoInvalida(PlataformaFreela plataforma, String origem) {
        log.warn("Não está logado em {}, tentando renovar sessão", plataforma.label());
        DashboardController.enviarLog("WARN", "Sessao " + plataforma.label() + " nao autenticada");
        if (exigeLoginManual(plataforma)) {
            avisarRenovacaoManual(plataforma);
        } else {
            loginAssistidoAutoTrigger.dispararSeSessaoInvalida(origem + ":" + plataforma.key());
        }
        long proxima = System.currentTimeMillis() + Math.max(30, sessaoInvalidaCooldownSegundos) * 1000;
        proximaTentativaSessaoPorPlataforma.put(plataforma, proxima);
    }

    /**
     * Plataformas cuja sessao nao pode ser renovada por automacao (ex.: Cloudflare
     * Managed Challenge no Freelancer.com.br). Nesses casos avisamos o usuario para
     * capturar novos cookies manualmente.
     */
    private boolean exigeLoginManual(PlataformaFreela plataforma) {
        // O Freelancer.com.br saiu daqui: o login dele (/login + POST /conecte-se)
        // nao tem captcha e o Chrome real resolve o desafio do Cloudflare, entao a
        // sessao e renovada por automacao como nas demais plataformas.
        return plataforma == PlataformaFreela.WORKANA;
    }

    private void avisarRenovacaoManual(PlataformaFreela plataforma) {
        if (alertaSessaoManual.registrarFalha(plataforma)) {
            alertaSessaoManual.enviar(plataforma);
        }
    }

    /**
     * Notifica via Telegram se houver muitos erros consecutivos.
     */
    private void verificarErrosConsecutivos() {
        errorHandler().notificarErrosConsecutivos(errosConsecutivos.get(), MAX_ERROS_ANTES_NOTIFICAR);
    }

    private long contarPendenciasSeguro() {
        try {
            return autoAtendimentoService.contarAcoesPendentes();
        } catch (Exception e) {
            log.debug("Nao foi possivel contar pendencias antes/depois do ciclo: {}", e.getMessage());
            return -1;
        }
    }

    private PollingSchedulerErrorHandler errorHandler() {
        if (errorHandler == null) {
            errorHandler = new PollingSchedulerErrorHandler(
                interacaoRepository,
                autoAtendimentoService,
                telegramService
            );
        }
        return errorHandler;
    }

    private PollingSchedulerOperatorNotifier operatorNotifier() {
        if (operatorNotifier == null) {
            operatorNotifier = new PollingSchedulerOperatorNotifier(
                telegramService,
                controleOperacionalService,
                diarioOperacionalEnabled,
                arquivoDiarioOperacional,
                checkinEnsinoEnabled,
                checkinEnsinoIntervaloMinutos,
                arquivoCheckinEnsino,
                realWriteEnabled,
                realWriteSimulateEnabled
            );
        }
        return operatorNotifier;
    }

    /**
     * Roda todos os dias às 03:00 da manhã.
     * Faz o cashback de conexões (cancelar propostas sem resposta > 7 dias)
     * e otimiza a memória da IA.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void otimizarSistemaDiariamente() {
        if (!schedulingEnabled || pausado.get()) return;

        log.info("Iniciando rotina de manutenção noturna (Cashback e IA)...");
        try {
            // 1. Otimizar memória da IA
            autoAtendimentoService.otimizarMemoriaContexto();

            // 2. Cashback de Conexões
            autoAtendimentoService.cancelarPropostasAntigas(7);

        } catch (Exception e) {
            log.error("Erro na rotina de manutenção noturna: {}", e.getMessage());
        }
    }

    public void alterarModo(ModoSistema novoModo) {
        this.modoAtual.set(novoModo);
        log.info("Modo alterado para: {}", novoModo);
    }

    public ModoSistema getModoAtual() {
        return modoAtual.get();
    }

    public void pausar() {
        pausado.set(true);
        log.info("Sistema PAUSADO");
    }

    public void retomar() {
        iaBloqueadaPorConta.set(false);
        pausado.set(false);
        errosConsecutivos.set(0);
        log.info("Sistema RETOMADO");
    }

    public boolean isPausado() {
        return pausado.get();
    }

    public boolean isAutoAtivo() {
        return autoAtivo.get();
    }

    public void ativarAuto() {
        autoAtivo.set(true);
        log.info("Modo AUTO ATIVADO - sistema ligará automaticamente se estiver pausado");
    }

    public void desativarAuto() {
        autoAtivo.set(false);
        log.info("Modo AUTO DESATIVADO");
    }

    private void pausarPorProblemaContaCodex(Exception exception) {
        if (!isProblemaContaCodex(exception)) {
            return;
        }
        iaBloqueadaPorConta.set(true);
        pausado.set(true);
        autoAtivo.set(false);
        errorHandler().notificarProblemaContaCodex(exception, iaAuthAlertaCooldownSegundos);
        log.warn("Fluxo pausado por problema nas contas Codex. Atualize CODEX_AUTH_JSON_B64* e retome manualmente.");
    }

    private boolean isProblemaContaCodex(Throwable throwable) {
        Throwable atual = throwable;
        while (atual != null) {
            String mensagem = atual.getMessage();
            if (mensagem != null && (mensagem.contains("CODEX_AUTH_INVALIDA") || mensagem.contains("CODEX_CONTA_LIMITADA"))) {
                return true;
            }
            atual = atual.getCause();
        }
        return false;
    }
}
