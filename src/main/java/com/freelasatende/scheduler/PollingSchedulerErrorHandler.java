package com.freelasatende.scheduler;

import com.freelasatende.controller.DashboardController;
import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.service.interfaceServico.AutoAtendimentoService;
import com.freelasatende.service.interfaceServico.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
final class PollingSchedulerErrorHandler {

    private static final int LIMITE_RETRY_ERRO_TECNICO_POR_CICLO = 1;
    private static final int MAX_TENTATIVAS_ERRO_TECNICO = 3;
    private static final EnumSet<TipoInteracao> TIPOS_NAO_CONCLUSIVOS = EnumSet.of(
        TipoInteracao.ERRO_SISTEMA,
        TipoInteracao.MENSAGEM_ERRO
    );

    private final InteracaoRepository interacaoRepository;
    private final AutoAtendimentoService autoAtendimentoService;
    private final TelegramService telegramService;
    private volatile long proximoAlertaIaAuthMillis = 0;

    void reprocessarErrosTecnicosPendentes(ModoSistema modoCiclo) {
        LocalDateTime desde = LocalDateTime.now().minusDays(1);
        List<Interacao> pendentes = interacaoRepository.findErrosTecnicosSemTratamento(
            TipoInteracao.ERRO_SISTEMA,
            desde,
            MAX_TENTATIVAS_ERRO_TECNICO,
            TIPOS_NAO_CONCLUSIVOS,
            PageRequest.of(0, LIMITE_RETRY_ERRO_TECNICO_POR_CICLO)
        );
        if (pendentes == null || pendentes.isEmpty()) {
            return;
        }
        for (Interacao pendente : pendentes) {
            if (pendente.getProjetoId() == null) {
                continue;
            }
            log.info("Reprocessando projeto {} apos erro tecnico sem tratamento", pendente.getProjetoId());
            DashboardController.enviarLog("INFO",
                "Reprocessando projeto " + pendente.getProjetoId() + " apos erro tecnico");
            autoAtendimentoService.reprocessarProjetoIgnorado(pendente.getProjetoId(), modoCiclo);
        }
    }

    void notificarErrosConsecutivos(int erros, int maxErrosAntesNotificar) {
        if (erros >= maxErrosAntesNotificar && erros % maxErrosAntesNotificar == 0) {
            enviarNotificacaoSegura(
                "⚠️ *ALERTA: " + erros + " erros consecutivos*\n" +
                    "O sistema pode estar com problemas.\n" +
                    "Use /status para verificar ou /parar para pausar.",
                "alerta de erros consecutivos"
            );
        }
    }

    void notificarProblemaContaCodex(Exception exception, long cooldownSegundos) {
        boolean authInvalida = contemMensagem(exception, "CODEX_AUTH_INVALIDA");
        boolean contaLimitada = contemMensagem(exception, "CODEX_CONTA_LIMITADA");
        if (!authInvalida && !contaLimitada) {
            return;
        }
        long agora = System.currentTimeMillis();
        if (agora < proximoAlertaIaAuthMillis) {
            return;
        }
        String motivo = authInvalida
            ? "autenticacao do Codex CLI ausente ou expirada"
            : "limite ou creditos da conta Codex esgotados";
        if (enviarNotificacaoSegura(
            "🚨 IA PAUSADA: " + motivo + ".\n"
                + "O fluxo automático foi pausado para não processar projetos sem IA.\n"
                + "Envie ou atualize CODEX_AUTH_JSON_B64 e CODEX_AUTH_JSON_B64_1..5 no servidor/Coolify.\n"
                + "Depois faça redeploy ou use /retomar. Nunca coloque auth.json, cookies ou tokens no Git.",
            "alerta de conta Codex"
        )) {
            proximoAlertaIaAuthMillis = agora + Math.max(300, cooldownSegundos) * 1000;
        }
    }

    private boolean enviarNotificacaoSegura(String mensagem, String contexto) {
        try {
            telegramService.enviarNotificacao(mensagem);
            return true;
        } catch (Exception e) {
            log.warn("Falha ao enviar {} pelo Telegram: {}", contexto, e.getMessage());
            return false;
        }
    }

    private boolean contemMensagem(Throwable throwable, String marcador) {
        Throwable atual = throwable;
        while (atual != null) {
            if (atual.getMessage() != null && atual.getMessage().contains(marcador)) {
                return true;
            }
            atual = atual.getCause();
        }
        return false;
    }
}
