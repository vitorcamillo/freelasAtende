package com.freelasatende.service.impl;

import com.freelasatende.model.dto.ControleAcaoResultadoDTO;
import com.freelasatende.model.dto.EnsinoFocoDTO;
import com.freelasatende.service.interfaceServico.ControleOperacionalService;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.freelasatende.service.impl.TelegramActionMessageFormatter.formatarDetalheAcao;
import static com.freelasatende.service.impl.TelegramActionMessageFormatter.formatarFocoEnsino;
import static com.freelasatende.service.impl.TelegramActionMessageFormatter.formatarResumoAcao;
import static com.freelasatende.service.impl.TelegramActionMessageFormatter.limitarMensagemTelegram;

final class TelegramCallbackProcessor {

    private static final String ORIENTACAO_REESCREVER = """
        Reescreva a mensagem atual criando uma alternativa diferente, mais util e especifica para a conversa.
        Use a ultima fala do cliente e o historico visivel como base.
        Nao use pergunta generica do tipo "qual detalhe voce quer que eu priorize", "qual ponto voce quer que eu responda" ou "responder do jeito certo".
        Se realmente faltar informacao, pergunte somente um dado concreto do projeto, baseado no historico visivel.
        Evite frases genericas como "vou revisar esse ponto", "te atualizo com o proximo passo", "sigo por aqui" ou "vou ajustar por aqui".
        """;

    private static final String ORIENTACAO_TOM_ROBOTICO = """
        O texto ficou robotico. Reescreva a mensagem atual com tom mais humano, direto e natural, sem parecer template ou sistema.
        Mantenha o mesmo objetivo, os mesmos fatos e o mesmo proximo passo do rascunho atual; altere apenas o tom.
        Nao transforme a resposta em sugestao de texto de anuncio, briefing ou resumo do projeto.
        Nao volte a explicar o que o cliente pediu se o rascunho atual ja tem uma resposta pronta.
        Nao use pergunta generica do tipo "qual detalhe voce quer que eu priorize", "qual ponto voce quer que eu responda" ou "responder do jeito certo".
        Se realmente faltar informacao, pergunte somente um dado concreto do projeto, baseado no historico visivel.
        Evite frases genericas como "vou revisar esse ponto", "te atualizo com o proximo passo", "sigo por aqui" ou "vou ajustar por aqui".
        """;

    private static final String ORIENTACAO_TEXTO_LONGO = """
        O texto ficou longo demais. Reescreva a mensagem atual em no maximo 350 caracteres.
        Mantenha o mesmo objetivo, os mesmos fatos e o mesmo proximo passo; corte apenas o que for redundante.
        Elimine frase de preenchimento, repeticao de ideia e explicacao que o cliente ja sabe.
        Deixe no maximo 1 pergunta, e somente se ela for essencial.
        Nao use lista nem bullet para compactar o texto.
        """;

    private static final String ORIENTACAO_REPETITIVO = """
        O texto repetiu a mesma ideia mais de uma vez. Reescreva dizendo cada coisa uma unica vez.
        Mantenha o mesmo objetivo e os mesmos fatos do rascunho atual.
        Cada frase precisa acrescentar informacao nova; remova parafrase do que ja foi dito antes no texto.
        Nao repita como checklist o que o cliente pediu.
        Deixe no maximo 1 pergunta, e somente se ela for essencial.
        """;

    /**
     * Motivos de recusa que viram reescrita orientada em vez de descarte da acao.
     * Cada motivo carrega uma orientacao propria, para o proximo prompt saber o que
     * corrigir de fato em vez de receber sempre a mesma instrucao generica.
     */
    private static final Map<String, String> ORIENTACOES_REESCRITA_POR_MOTIVO = Map.of(
        "ROBOTICO", ORIENTACAO_TOM_ROBOTICO,
        "TEXTO_LONGO", ORIENTACAO_TEXTO_LONGO,
        "REPETITIVO", ORIENTACAO_REPETITIVO
    );

    private static final Map<String, String> ROTULOS_MOTIVO_REESCRITA = Map.of(
        "ROBOTICO", "tom robotico",
        "TEXTO_LONGO", "texto longo demais",
        "REPETITIVO", "ideia repetida"
    );

    private static final Map<String, String> CALLBACKS_MOTIVO_REESCRITA = Map.of(
        "ROBOTICO", "Reescrevendo tom",
        "TEXTO_LONGO", "Reescrevendo mais curto",
        "REPETITIVO", "Reescrevendo sem repetir"
    );

    private TelegramCallbackProcessor() {
    }

    static void processar(
        JsonNode callback,
        TelegramServiceImpl telegramService,
        ControleOperacionalService controleOperacionalService,
        AtomicReference<TelegramCommandHandler.EntradaBotaoPendente> entradaBotaoPendente
    ) {
        try {
            String callbackId = callback.path("id").asText("");
            String data = callback.path("data").asText("");

            if (data.startsWith("acao:aprovar:")) {
                String[] partes = data.split(":", 4);
                Long acaoId = Long.parseLong(partes[2]);
                String motivo = partes.length >= 4 ? partes[3] : "BOM_TOM";
                ControleAcaoResultadoDTO resultado = controleOperacionalService.aprovarAcao(acaoId, motivo);
                telegramService.responderCallback(callbackId, textoCallbackResultado(resultado, "Ação aprovada"));
                if (!resultado.isSucesso()) {
                    telegramService.enviarNotificacao(formatarResultadoBotao(resultado));
                }
                if (deveApagarMensagemDeBotoes(resultado)) {
                    apagarMensagemDeBotoes(callback, telegramService);
                }
                return;
            }
            if (data.startsWith("acao:recusar:")) {
                String[] partes = data.split(":", 4);
                Long acaoId = Long.parseLong(partes[2]);
                String motivo = partes.length >= 4 ? partes[3] : "ROBOTICO";
                String motivoNormalizado = motivo.toUpperCase(Locale.ROOT);
                String orientacaoMotivo = ORIENTACOES_REESCRITA_POR_MOTIVO.get(motivoNormalizado);
                if (orientacaoMotivo != null) {
                    ControleAcaoResultadoDTO resultado = controleOperacionalService.registrarContextoEnsino(
                        acaoId,
                        orientacaoMotivo
                    );
                    String rotulo = ROTULOS_MOTIVO_REESCRITA.getOrDefault(motivoNormalizado, "ajuste pedido");
                    telegramService.responderCallback(
                        callbackId,
                        CALLBACKS_MOTIVO_REESCRITA.getOrDefault(motivoNormalizado, "Reescrevendo")
                    );
                    enviarResultadoAcaoComBotoes(resultado, "Resposta reprocessada por " + rotulo + ".", telegramService);
                    apagarMensagemDeBotoes(callback, telegramService);
                    return;
                }
                ControleAcaoResultadoDTO resultado = controleOperacionalService.recusarAcao(acaoId, motivo);
                telegramService.responderCallback(callbackId, textoCallbackResultado(resultado, "Ação recusada"));
                telegramService.enviarNotificacao(formatarResultadoBotao(resultado));
                apagarMensagemDeBotoes(callback, telegramService);
                return;
            }
            if ("acao:entrada:cancelar".equals(data)) {
                entradaBotaoPendente.set(null);
                telegramService.responderCallback(callbackId, "Entrada cancelada");
                telegramService.enviarNotificacao("Entrada cancelada. As decisões continuam pelos botões da pendência.");
                return;
            }
            if (data.startsWith("acao:detalhe:")) {
                Long acaoId = Long.parseLong(data.substring("acao:detalhe:".length()));
                telegramService.responderCallback(callbackId, "Ação aberta");
                controleOperacionalService.buscarAcao(acaoId).ifPresentOrElse(
                    acao -> telegramService.enviarAcaoPendenteComBotoes(formatarDetalheAcao(acao), acao.getId()),
                    () -> telegramService.enviarNotificacao("Ação não encontrada: " + acaoId)
                );
                return;
            }
            if (data.startsWith("acao:reescrever:")) {
                Long acaoId = Long.parseLong(data.substring("acao:reescrever:".length()));
                ControleAcaoResultadoDTO resultado = controleOperacionalService.registrarContextoEnsino(
                    acaoId,
                    ORIENTACAO_REESCREVER
                );
                telegramService.responderCallback(callbackId, "Reescrevendo");
                enviarResultadoAcaoComBotoes(resultado, "Resposta reprocessada.", telegramService);
                apagarMensagemDeBotoes(callback, telegramService);
                return;
            }
            if (data.startsWith("acao:ensinar:")) {
                Long acaoId = Long.parseLong(data.substring("acao:ensinar:".length()));
                entradaBotaoPendente.set(new TelegramCommandHandler.EntradaBotaoPendente(
                    acaoId,
                    TelegramCommandHandler.TipoEntradaBotao.ENSINAR
                ));
                telegramService.responderCallback(callbackId, "Ensino ativado");
                telegramService.enviarEntradaAcaoComBotoes(
                    "Ensinar ação #" + acaoId + "\n\n"
                        + "Mande agora o contexto, regra ou ajuste em mensagem normal.\n"
                        + "Eu atualizo a pendência e volto com botões.",
                    acaoId
                );
                return;
            }
            if (data.startsWith("acao:foco:")) {
                Long acaoId = Long.parseLong(data.substring("acao:foco:".length()));
                EnsinoFocoDTO foco = controleOperacionalService.definirFocoEnsino(acaoId);
                telegramService.responderCallback(callbackId, "Foco atualizado");
                telegramService.enviarNotificacao(formatarFocoEnsino(foco)
                    + "\n\nAção em foco. Texto normal conversa comigo. Para ensinar ou reescrever, use os botões da pendência.");
                return;
            }

            telegramService.responderCallback(callbackId, "Ação desconhecida");
        } catch (NumberFormatException e) {
            String callbackId = callback.path("id").asText("");
            telegramService.responderCallback(callbackId, "ID da ação inválido");
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(TelegramCallbackProcessor.class)
                .debug("Erro ao processar callback Telegram: {}", e.getMessage());
        }
    }

    private static void apagarMensagemDeBotoes(JsonNode callback, TelegramServiceImpl telegramService) {
        String chatId = callback.path("message").path("chat").path("id").asText("");
        long messageId = callback.path("message").path("message_id").asLong(0L);
        telegramService.apagarMensagem(chatId, messageId);
    }

    private static String textoCallbackResultado(ControleAcaoResultadoDTO resultado, String sucesso) {
        if (resultado != null && resultado.isSucesso()) {
            return sucesso;
        }
        if (resultado != null
            && resultado.getMensagem() != null
            && resultado.getMensagem().toLowerCase().contains("continua pendente")) {
            return "Falha; continua pendente";
        }
        if (resultado != null
            && resultado.getMensagem() != null
            && mensagemIndicaFalha(resultado.getMensagem())) {
            return "Falha ao enviar";
        }
        return "Ação já tratada";
    }

    private static boolean mensagemIndicaFalha(String mensagem) {
        String normalizada = mensagem.toLowerCase();
        return normalizada.contains("falha")
            || normalizada.contains("falhou")
            || normalizada.contains("nada foi enviado");
    }

    private static boolean deveApagarMensagemDeBotoes(ControleAcaoResultadoDTO resultado) {
        if (resultado == null || resultado.isSucesso()) {
            return true;
        }
        String mensagem = resultado.getMensagem();
        return mensagem == null || !mensagem.toLowerCase().contains("continua pendente");
    }

    private static String formatarResultadoBotao(ControleAcaoResultadoDTO resultado) {
        if (resultado == null || resultado.getMensagem() == null || resultado.getMensagem().isBlank()) {
            return "Ação não executada. Atualize /pendencias ou o dashboard.";
        }
        if (resultado.isSucesso()) {
            return resultado.getMensagem();
        }
        return resultado.getMensagem() + "\n\nNada foi enviado agora. Atualize /pendencias ou o dashboard.";
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
}
