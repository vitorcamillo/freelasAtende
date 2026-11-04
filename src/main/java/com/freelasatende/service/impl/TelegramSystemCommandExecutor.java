package com.freelasatende.service.impl;

import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.scheduler.PollingScheduler;
import com.freelasatende.service.interfaceServico.AutoAtendimentoService;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.HistoricoService;
import com.freelasatende.service.interfaceServico.IaProvider;

import java.util.Arrays;

final class TelegramSystemCommandExecutor {

    private TelegramSystemCommandExecutor() {
    }

    static void enviarStatus(
        TelegramServiceImpl telegramService,
        GerenciadorConexoesService gerenciadorConexoes,
        AutenticacaoService autenticacaoService,
        HistoricoService historicoService,
        PollingScheduler pollingScheduler,
        AutoAtendimentoService autoAtendimentoService,
        IaProvider iaProvider
    ) {
        telegramService.enviarNotificacao(TelegramSystemMessageFormatter.formatarStatus(
            gerenciadorConexoes.getStatusConexoes(),
            autenticacaoService.verificarSessaoValida(),
            historicoService.contarInteracoesDoDia(),
            pollingScheduler.getModoAtual(),
            pollingScheduler.isPausado(),
            autoAtendimentoService.contarAcoesPendentes(),
            iaProvider.getNome(),
            iaProvider.getModelo()
        ));
    }

    static void alterarModo(TelegramServiceImpl telegramService, PollingScheduler pollingScheduler, String[] partes) {
        if (partes.length < 2) {
            telegramService.enviarNotificacao(
                "Uso: /modo LIVRE|REGULAR|ENSINAMENTO\n" +
                    "Modo atual: " + pollingScheduler.getModoAtual());
            return;
        }

        try {
            ModoSistema novoModo = ModoSistema.valueOf(partes[1].toUpperCase());
            pollingScheduler.alterarModo(novoModo);
            telegramService.enviarNotificacao("✅ Modo alterado para: " + novoModo);
        } catch (IllegalArgumentException e) {
            telegramService.enviarNotificacao(
                "❌ Modo inválido: " + partes[1] +
                    "\nUse: LIVRE, REGULAR ou ENSINAMENTO");
        }
    }

    static void enviarEstatisticas(
        TelegramServiceImpl telegramService,
        HistoricoService historicoService,
        GerenciadorConexoesService gerenciadorConexoes,
        AutoAtendimentoService autoAtendimentoService,
        IaProvider iaProvider,
        PollingScheduler pollingScheduler
    ) {
        telegramService.enviarNotificacao(TelegramSystemMessageFormatter.formatarEstatisticas(
            historicoService.contarInteracoesDoDia(),
            gerenciadorConexoes.getStatusConexoes(),
            autoAtendimentoService.contarAcoesPendentes(),
            iaProvider.getNome(),
            iaProvider.getModelo(),
            pollingScheduler.getModoAtual()
        ));
    }

    static void atualizarCookies(
        TelegramServiceImpl telegramService,
        AutenticacaoService autenticacaoService,
        String[] partes
    ) {
        if (partes.length < 2) {
            telegramService.enviarNotificacao(
                "Uso: /cookies {\"JSESSIONID\":\"xxx\", \"sgcn\":\"yyy\"}\n" +
                    "Cole o JSON dos cookies exportados");
            return;
        }

        String jsonCookies = String.join(" ", Arrays.copyOfRange(partes, 1, partes.length));
        try {
            autenticacaoService.carregarCookiesJson(jsonCookies);
            boolean valida = autenticacaoService.verificarSessaoValida();
            telegramService.enviarNotificacao(
                valida
                    ? "✅ Cookies atualizados e sessão válida!"
                    : "⚠️ Cookies atualizados mas sessão pode estar inválida");
        } catch (Exception e) {
            telegramService.enviarNotificacao("❌ Erro ao atualizar cookies: " + e.getMessage());
        }
    }

    static void enviarInfoIa(TelegramServiceImpl telegramService, IaProvider iaProvider) {
        telegramService.enviarNotificacao(TelegramSystemMessageFormatter.formatarInfoIa(
            iaProvider.getNome(),
            iaProvider.getModelo(),
            iaProvider.estaDisponivel()
        ));
    }
}
