package com.freelasatende.service.impl;

import com.freelasatende.model.dto.AcaoControleDTO;
import com.freelasatende.model.dto.EnsinoFocoDTO;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.scheduler.PollingScheduler;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.ControleOperacionalService;
import com.freelasatende.service.interfaceServico.IaProvider;

import java.util.List;
import java.util.Map;

import static com.freelasatende.service.impl.TelegramActionMessageFormatter.formatarResumoAcao;
import static com.freelasatende.service.impl.TelegramActionMessageFormatter.limitarMensagemTelegram;

final class TelegramPendingActionCommandExecutor {

    private TelegramPendingActionCommandExecutor() {
    }

    static void enviarDuvidasOperacionais(
        String[] partes,
        TelegramServiceImpl telegramService,
        ControleOperacionalService controleOperacionalService
    ) {
        PlataformaFreela plataforma = plataformaFiltro(partes);
        List<AcaoControleDTO> pendencias = filtrarPorPlataforma(
            TelegramOperationalContextHelper.listarPendenciasSeguro(controleOperacionalService),
            plataforma
        );
        List<AcaoControleDTO> comDuvidas = pendencias.stream()
            .filter(TelegramOperationalContextHelper::acaoTemDuvidaAberta)
            .limit(8)
            .toList();

        telegramService.enviarNotificacao(limitarMensagemTelegram(
            TelegramCommandMessageFormatter.duvidasOperacionais(comDuvidas, pendencias.size())
        ));
    }

    static void enviarContextoOperacional(
        String[] partes,
        TelegramServiceImpl telegramService,
        ControleOperacionalService controleOperacionalService,
        AvaliadorIaService avaliadorIaService,
        PollingScheduler pollingScheduler,
        IaProvider iaProvider
    ) {
        PlataformaFreela plataforma = plataformaFiltro(partes);
        Map<String, Object> memoria = TelegramOperationalContextHelper.diagnosticoMemoriaSeguro(avaliadorIaService);
        List<AcaoControleDTO> pendencias = filtrarPorPlataforma(
            TelegramOperationalContextHelper.listarPendenciasSeguro(controleOperacionalService),
            plataforma
        );
        EnsinoFocoDTO foco = TelegramOperationalContextHelper.obterFocoSeguro(controleOperacionalService);

        telegramService.enviarNotificacao(limitarMensagemTelegram(
            TelegramCommandMessageFormatter.contextoOperacional(
                pollingScheduler,
                iaProvider,
                memoria,
                pendencias,
                foco
            )
        ));
    }

    static void enviarPendencias(
        String[] partes,
        TelegramServiceImpl telegramService,
        ControleOperacionalService controleOperacionalService
    ) {
        PlataformaFreela plataforma = plataformaFiltro(partes);
        List<AcaoControleDTO> pendencias = filtrarPorPlataforma(
            controleOperacionalService.listarAcoesPendentes(),
            plataforma
        );
        if (pendencias.isEmpty()) {
            telegramService.enviarNotificacao("Nenhuma pendência no momento" + sufixoPlataforma(plataforma) + ".");
            return;
        }

        EnsinoFocoDTO foco = controleOperacionalService.obterFocoEnsino();
        telegramService.enviarNotificacao(limitarMensagemTelegram(TelegramCommandMessageFormatter.listaAcoes(
            "Pendências aguardando decisão" + sufixoPlataforma(plataforma),
            pendencias,
            foco,
            "pendências no dashboard."
        )));
        pendencias.stream()
            .filter(acao -> acao.getId() != null)
            .limit(3)
            .forEach(acao -> telegramService.enviarAcaoPendenteComBotoes(formatarResumoAcao(acao), acao.getId()));
    }

    static void enviarPerguntas(
        String[] partes,
        TelegramServiceImpl telegramService,
        ControleOperacionalService controleOperacionalService
    ) {
        PlataformaFreela plataforma = plataformaFiltro(partes);
        List<AcaoControleDTO> perguntas = filtrarPorPlataforma(
            controleOperacionalService.listarAcoesPendentes(),
            plataforma
        ).stream()
            .filter(acao -> acao.getTipo() == TipoAcaoPendente.ENVIAR_PERGUNTA
                || acao.getTipo() == TipoAcaoPendente.RESPONDER_CONVERSA)
            .toList();
        if (perguntas.isEmpty()) {
            telegramService.enviarNotificacao(
                "Nenhuma pergunta ou resposta pendente no momento" + sufixoPlataforma(plataforma) + "."
            );
            return;
        }

        EnsinoFocoDTO foco = controleOperacionalService.obterFocoEnsino();
        telegramService.enviarNotificacao(limitarMensagemTelegram(TelegramCommandMessageFormatter.listaAcoes(
            "Perguntas/respostas aguardando decisão" + sufixoPlataforma(plataforma),
            perguntas,
            foco,
            "itens no dashboard."
        )));
        perguntas.stream()
            .filter(acao -> acao.getId() != null)
            .limit(3)
            .forEach(acao -> telegramService.enviarAcaoPendenteComBotoes(formatarResumoAcao(acao), acao.getId()));
    }

    private static PlataformaFreela plataformaFiltro(String[] partes) {
        if (partes.length < 2 || partes[1].isBlank() || "todas".equalsIgnoreCase(partes[1])) {
            return null;
        }
        return PlataformaFreela.fromKey(partes[1]);
    }

    private static List<AcaoControleDTO> filtrarPorPlataforma(List<AcaoControleDTO> acoes, PlataformaFreela plataforma) {
        if (plataforma == null) {
            return acoes;
        }
        return acoes.stream()
            .filter(acao -> PlataformaFreela.normalizar(acao.getPlataforma()) == plataforma)
            .toList();
    }

    private static String sufixoPlataforma(PlataformaFreela plataforma) {
        return plataforma == null ? "" : " em " + plataforma.label();
    }
}
