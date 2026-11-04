package com.freelasatende.service.impl;

import com.freelasatende.model.dto.ControleAcaoResultadoDTO;
import com.freelasatende.scheduler.PollingScheduler;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.ControleOperacionalService;
import com.freelasatende.service.interfaceServico.IaProvider;

import java.util.concurrent.atomic.AtomicReference;

import static com.freelasatende.service.impl.TelegramActionMessageFormatter.formatarResumoAcao;
import static com.freelasatende.service.impl.TelegramActionMessageFormatter.limitarMensagemTelegram;

final class TelegramFreeTextProcessor {

    private TelegramFreeTextProcessor() {
    }

    static void processarMensagemLivre(
        String texto,
        AtomicReference<TelegramCommandHandler.EntradaBotaoPendente> entradaBotaoPendente,
        ControleOperacionalService controleOperacionalService,
        TelegramServiceImpl telegramService,
        boolean operatorChatIaEnabled,
        IaProvider iaProvider,
        AvaliadorIaService avaliadorIaService,
        PollingScheduler pollingScheduler
    ) {
        String orientacao = texto != null ? texto.trim() : "";
        if (orientacao.isBlank()) {
            return;
        }

        TelegramCommandHandler.EntradaBotaoPendente entrada = entradaBotaoPendente.get();
        if (entrada != null) {
            processarEntradaBotao(entrada, orientacao, controleOperacionalService, telegramService);
            entradaBotaoPendente.compareAndSet(entrada, null);
            return;
        }

        responderConversaOperador(
            orientacao,
            telegramService,
            operatorChatIaEnabled,
            iaProvider,
            avaliadorIaService,
            controleOperacionalService,
            pollingScheduler
        );
    }

    private static void processarEntradaBotao(
        TelegramCommandHandler.EntradaBotaoPendente entrada,
        String texto,
        ControleOperacionalService controleOperacionalService,
        TelegramServiceImpl telegramService
    ) {
        if (entrada.tipo() == TelegramCommandHandler.TipoEntradaBotao.REESCREVER) {
            ControleAcaoResultadoDTO resultado = controleOperacionalService.reescreverAcao(
                entrada.acaoId(),
                texto,
                "Reescrito via botão Telegram"
            );
            enviarResultadoAcaoComBotoes(resultado, "Reescrita processada.", telegramService);
            return;
        }
        if (entrada.tipo() == TelegramCommandHandler.TipoEntradaBotao.ENSINAR) {
            ControleAcaoResultadoDTO resultado = controleOperacionalService.registrarContextoEnsino(entrada.acaoId(), texto);
            enviarResultadoAcaoComBotoes(resultado, "Contexto registrado.", telegramService);
        }
    }

    private static void enviarResultadoAcaoComBotoes(
        ControleAcaoResultadoDTO resultado,
        String fallback,
        TelegramServiceImpl telegramService
    ) {
        String resposta = resultado != null && resultado.getMensagem() != null ? resultado.getMensagem() : fallback;
        if (resultado != null && resultado.getAcao() != null) {
            resposta += "\n\n" + formatarResumoAcao(resultado.getAcao());
            telegramService.enviarAcaoPendenteComBotoes(limitarMensagemTelegram(resposta), resultado.getAcao().getId());
            return;
        }
        telegramService.enviarNotificacao(limitarMensagemTelegram(resposta));
    }

    static void responderConversaOperador(
        String texto,
        TelegramServiceImpl telegramService,
        boolean operatorChatIaEnabled,
        IaProvider iaProvider,
        AvaliadorIaService avaliadorIaService,
        ControleOperacionalService controleOperacionalService,
        PollingScheduler pollingScheduler
    ) {
        String respostaIa = TelegramOperationalContextHelper.gerarRespostaOperadorComIa(
            operatorChatIaEnabled,
            iaProvider,
            avaliadorIaService,
            controleOperacionalService,
            pollingScheduler,
            texto
        );
        if (respostaIa != null && !respostaIa.isBlank()) {
            telegramService.enviarMensagemChat("Conversa operacional\n\n" + respostaIa.trim());
            return;
        }

        telegramService.enviarMensagemChat(
            TelegramOperationalContextHelper.respostaLocalOperador(controleOperacionalService, texto)
        );
    }
}
