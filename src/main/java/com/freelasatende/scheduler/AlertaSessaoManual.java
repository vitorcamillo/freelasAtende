package com.freelasatende.scheduler;

import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.service.interfaceServico.TelegramService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Alerta de renovacao manual de sessao (plataformas sem login automatico, ex.:
 * Freelancer.com.br e Workana). Nao alerta na PRIMEIRA falha: o Cloudflare
 * derruba ocasionalmente uma unica requisicao mesmo com cf_clearance valido
 * (rajada pos-deploy, por exemplo) e a falha isolada se recupera sozinha no
 * ciclo seguinte. So avisa apos falhas CONSECUTIVAS; sucesso em qualquer ciclo
 * reseta o contador.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class AlertaSessaoManual {

    private static final int FALHAS_CONSECUTIVAS_PARA_AVISO = 2;
    /** Lembrete unico se o episodio de sessao invalida persistir (12h). */
    private static final long INTERVALO_LEMBRETE_MILLIS = 12L * 3_600_000L;

    private final TelegramService telegramService;
    private final Map<PlataformaFreela, Long> proximoAvisoPorPlataforma = new ConcurrentHashMap<>();
    private final Map<PlataformaFreela, Integer> falhasConsecutivasPorPlataforma = new ConcurrentHashMap<>();

    /** Registra falha de sessao. Retorna true se o aviso deve ser disparado. */
    boolean registrarFalha(PlataformaFreela plataforma) {
        int falhas = falhasConsecutivasPorPlataforma.merge(plataforma, 1, Integer::sum);
        if (falhas < FALHAS_CONSECUTIVAS_PARA_AVISO) {
            log.info("Sessao {} invalida pela {}a vez consecutiva; aguardando proximo ciclo antes de avisar",
                plataforma.label(), falhas);
            return false;
        }
        // NAO zera o contador: o episodio so termina com sucesso real
        // (registrarSucesso). Zerar aqui fazia o aviso se repetir a cada ~1h.
        long agora = System.currentTimeMillis();
        Long proximo = proximoAvisoPorPlataforma.get(plataforma);
        if (proximo != null && agora < proximo) {
            return false; // ja avisou neste episodio; lembrete so apos 12h persistindo
        }
        proximoAvisoPorPlataforma.put(plataforma, agora + INTERVALO_LEMBRETE_MILLIS);
        return true;
    }

    /** Registra sucesso de sessao (inbox sincronizada ou login verificado). */
    void registrarSucesso(PlataformaFreela plataforma) {
        falhasConsecutivasPorPlataforma.put(plataforma, 0);
        // Novo episodio futuro pode (e deve) alertar de novo: limpa o periodo de silencio.
        proximoAvisoPorPlataforma.remove(plataforma);
    }

    /** Envia a orientacao de renovacao manual via Telegram. */
    void enviar(PlataformaFreela plataforma) {
        try {
            String orientacao = plataforma == PlataformaFreela.FREELANCER_BR
                ? "Este site fica atras do Cloudflare e nao renova sozinho. Faca login no navegador "
                    + "e me peca para atualizar os cookies (veja docs/COOKIES_FREELANCER_BR.md)."
                : "A sessao desta plataforma usa cookies manuais. Atualize o arquivo de cookies antes de retomar.";
            telegramService.enviarNotificacao(
                "Sessao " + plataforma.label() + " expirada.\n\n" + orientacao);
        } catch (Exception e) {
            log.warn("Falha ao avisar renovacao manual de {}: {}", plataforma.label(), e.getMessage());
        }
    }
}
