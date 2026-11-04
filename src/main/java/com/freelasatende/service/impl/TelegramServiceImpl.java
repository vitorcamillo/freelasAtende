package com.freelasatende.service.impl;

import com.freelasatende.config.TelegramConfig;
import com.freelasatende.config.OperacaoDefaults;
import com.freelasatende.model.dto.ProjetoInteresseResumoDTO;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.service.interfaceServico.TelegramService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Implementação do serviço de Telegram usando Bot API direta.
 * Envia e recebe mensagens via HTTP API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramServiceImpl implements TelegramService {

    private final TelegramConfig telegramConfig;
    private final ObjectMapper objectMapper;

    private static final String TELEGRAM_API = "https://api.telegram.org/bot";
    private static final int LIMITE_CHAT_TELEGRAM = OperacaoDefaults.LIMITE_MENSAGEM_TELEGRAM;

    private HttpClient httpClient;

    @PostConstruct
    void registrarConfiguracaoTelegram() {
        String botToken = telegramConfig.getBotToken() != null ? telegramConfig.getBotToken().trim() : "";
        String chatId = telegramConfig.getChatId() != null ? telegramConfig.getChatId().trim() : "";
        log.info(
            "Telegram configurado: simulate={}, botToken={}, chatId={}",
            telegramConfig.isSimulate(),
            identificarSegredo(botToken),
            identificarSegredo(chatId)
        );
    }

    private synchronized HttpClient getHttpClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        }
        return httpClient;
    }

    @Override
    public void enviarNotificacao(String mensagem) {
        enviarMensagem(mensagem);
    }

    public void enviarMensagemChat(String mensagem) {
        for (String parte : dividirMensagemChat(mensagem)) {
            enviarMensagem(parte);
        }
    }

    @Override
    public void enviarDuvidas(Projeto projeto, String duvidas) {
        enviarMensagem(montarMensagemDuvidas(projeto, duvidas));
    }

    String montarMensagemDuvidas(Projeto projeto, String duvidas) {
        return TelegramServiceMessageFormatter.montarMensagemDuvidas(projeto, duvidas);
    }

    @Override
    public void perguntarAprovacao(Projeto projeto, Proposta proposta) {
        enviarMensagem(montarMensagemProposta(projeto, proposta));
    }

    String montarMensagemProposta(Projeto projeto, Proposta proposta) {
        return TelegramServiceMessageFormatter.montarMensagemProposta(projeto, proposta);
    }

    @Override
    public void notificarProjetoInteressante(Projeto projeto) {
        notificarProjetoInteressante(projeto, null);
    }

    @Override
    public void notificarProjetoInteressante(Projeto projeto, ProjetoInteresseResumoDTO resumo) {
        enviarMensagem(montarMensagemProjetoInteressante(projeto, resumo));
    }

    String montarMensagemProjetoInteressante(Projeto projeto, ProjetoInteresseResumoDTO resumo) {
        return TelegramServiceMessageFormatter.montarMensagemProjetoInteressante(projeto, resumo);
    }

    @Override
    public Long enviarAcaoPendenteComBotoes(String mensagem, Long acaoId) {
        return enviarAcaoPendenteComBotoes(mensagem, acaoId, true);
    }

    @Override
    public Long enviarAcaoPendenteParaRevisaoComBotoes(String mensagem, Long acaoId) {
        return enviarAcaoPendenteComBotoes(mensagem, acaoId, false);
    }

    private Long enviarAcaoPendenteComBotoes(String mensagem, Long acaoId, boolean incluirAprovacao) {
        ObjectNode replyMarkup = objectMapper.createObjectNode();
        var keyboard = replyMarkup.putArray("inline_keyboard");
        if (incluirAprovacao) {
            var linhaAprovacao = keyboard.addArray();
            linhaAprovacao.addObject()
                .put("text", "✅ Bom tom")
                .put("callback_data", "acao:aprovar:" + acaoId + ":BOM_TOM");
            linhaAprovacao.addObject()
                .put("text", "✅ Escopo claro")
                .put("callback_data", "acao:aprovar:" + acaoId + ":ESCOPO_CLARO");
        }
        var linhaRecusa = keyboard.addArray();
        linhaRecusa.addObject()
            .put("text", "❌ Robótico")
            .put("callback_data", "acao:recusar:" + acaoId + ":ROBOTICO");
        linhaRecusa.addObject()
            .put("text", "❌ Risco")
            .put("callback_data", "acao:recusar:" + acaoId + ":RISCO_PLATAFORMA");
        // Motivos especificos: cada um manda uma orientacao propria para a reescrita,
        // em vez de reprocessar sempre com a mesma instrucao generica.
        var linhaMotivos = keyboard.addArray();
        linhaMotivos.addObject()
            .put("text", "📏 Muito longo")
            .put("callback_data", "acao:recusar:" + acaoId + ":TEXTO_LONGO");
        linhaMotivos.addObject()
            .put("text", "🔁 Repetitivo")
            .put("callback_data", "acao:recusar:" + acaoId + ":REPETITIVO");
        var linhaReescrita = keyboard.addArray();
        linhaReescrita.addObject()
            .put("text", "✍️ Reescrever")
            .put("callback_data", "acao:reescrever:" + acaoId);
        linhaReescrita.addObject()
            .put("text", "🧠 Ensinar")
            .put("callback_data", "acao:ensinar:" + acaoId);
        var linhaContexto = keyboard.addArray();
        linhaContexto.addObject()
            .put("text", "🎯 Focar")
            .put("callback_data", "acao:foco:" + acaoId);
        linhaContexto.addObject()
            .put("text", "🔎 Ver ação")
            .put("callback_data", "acao:detalhe:" + acaoId);

        return enviarMensagem(mensagem, replyMarkup);
    }

    public void enviarEntradaAcaoComBotoes(String mensagem, Long acaoId) {
        ObjectNode replyMarkup = objectMapper.createObjectNode();
        var keyboard = replyMarkup.putArray("inline_keyboard");
        var linha = keyboard.addArray();
        linha.addObject()
            .put("text", "Cancelar")
            .put("callback_data", "acao:entrada:cancelar");
        linha.addObject()
            .put("text", "Ver ação")
            .put("callback_data", "acao:detalhe:" + acaoId);

        enviarMensagem(mensagem, replyMarkup);
    }

    /**
     * Envia mensagem usando ObjectMapper para construção segura de JSON.
     */
    private Long enviarMensagem(String texto) {
        return enviarMensagem(texto, null);
    }

    private Long enviarMensagem(String texto, JsonNode replyMarkup) {
        if (deveSimularEnvioTelegram()) {
            log.info(
                "TELEGRAM_SIMULADO motivo={} replyMarkup={} preview=\"{}\"",
                motivoSimulacaoTelegram(),
                replyMarkup != null,
                previewLogTelegram(texto)
            );
            return null;
        }
        try {
            String url = TELEGRAM_API + telegramConfig.getBotToken() + "/sendMessage";

            ObjectNode body = objectMapper.createObjectNode();
            body.put("chat_id", telegramConfig.getChatId());
            body.put("text", texto);
            body.put("parse_mode", "Markdown");
            body.put("disable_web_page_preview", true);
            if (replyMarkup != null) {
                body.set("reply_markup", replyMarkup);
            }

            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            HttpResponse<String> response = getHttpClient().send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() == 200) {
                log.trace("Mensagem Telegram enviada com sucesso");
                return extrairMessageId(response.body());
            } else {
                log.error("Erro Telegram: {} - {}", response.statusCode(), response.body());

                // Se falhar com Markdown, tentar sem formatação
                if (response.statusCode() == 400) {
                    return enviarMensagemSemFormatacao(texto, replyMarkup);
                }
            }

        } catch (Exception e) {
            log.error("Erro ao enviar mensagem Telegram: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Fallback: envia sem formatação Markdown quando o parse falha.
     */
    private Long enviarMensagemSemFormatacao(String texto) {
        return enviarMensagemSemFormatacao(texto, null);
    }

    private Long enviarMensagemSemFormatacao(String texto, JsonNode replyMarkup) {
        if (deveSimularEnvioTelegram()) {
            log.info(
                "TELEGRAM_SIMULADO motivo={} replyMarkup={} preview=\"{}\"",
                motivoSimulacaoTelegram(),
                replyMarkup != null,
                previewLogTelegram(texto)
            );
            return null;
        }
        try {
            String url = TELEGRAM_API + telegramConfig.getBotToken() + "/sendMessage";

            ObjectNode body = objectMapper.createObjectNode();
            body.put("chat_id", telegramConfig.getChatId());
            body.put("text", TelegramServiceMessageFormatter.removerMarkdown(texto));
            body.put("disable_web_page_preview", true);
            if (replyMarkup != null) {
                body.set("reply_markup", replyMarkup);
            }

            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            HttpResponse<String> response = getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                log.trace("Mensagem Telegram enviada sem Markdown com sucesso");
                return extrairMessageId(response.body());
            } else {
                log.error("Falha ao enviar Telegram sem Markdown: {} - {}", response.statusCode(), response.body());
            }

        } catch (Exception e) {
            log.error("Falha total ao enviar Telegram: {}", e.getMessage());
        }
        return null;
    }

    private Long extrairMessageId(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode id = node.path("result").path("message_id");
            return id.isNumber() ? id.asLong() : null;
        } catch (Exception e) {
            log.debug("Nao foi possivel extrair message_id do Telegram: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Obtém updates não lidos do Telegram (para processamento de comandos).
     */
    public JsonNode obterUpdates(long offset) {
        if (credenciaisTelegramAusentes()) {
            log.debug("Telegram getUpdates ignorado: credenciais ausentes");
            return null;
        }
        try {
            String url = String.format("%s%s/getUpdates?offset=%d&timeout=5",
                TELEGRAM_API, telegramConfig.getBotToken(), offset);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

            HttpResponse<String> response = getHttpClient().send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() == 200) {
                return objectMapper.readTree(response.body());
            }
            log.debug("Telegram getUpdates retornou HTTP {}", response.statusCode());

        } catch (Exception e) {
            log.debug("Erro ao obter updates Telegram: {}", e.getMessage());
        }
        return null;
    }

    public void responderCallback(String callbackQueryId, String texto) {
        if (deveSimularEnvioTelegram()) {
            log.info(
                "TELEGRAM_SIMULADO motivo={} callback={} preview=\"{}\"",
                motivoSimulacaoTelegram(),
                callbackQueryId,
                previewLogTelegram(texto)
            );
            return;
        }
        try {
            String url = TELEGRAM_API + telegramConfig.getBotToken() + "/answerCallbackQuery";

            ObjectNode body = objectMapper.createObjectNode();
            body.put("callback_query_id", callbackQueryId);
            body.put("text", texto);
            body.put("show_alert", false);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.debug("Falha ao responder callback Telegram: {}", e.getMessage());
        }
    }

    public void apagarMensagem(String chatId, long messageId) {
        if (chatId == null || chatId.isBlank() || messageId <= 0) {
            return;
        }
        if (deveSimularEnvioTelegram()) {
            log.info(
                "TELEGRAM_SIMULADO motivo={} deleteMessage chat={} messageId={}",
                motivoSimulacaoTelegram(),
                chatId,
                messageId
            );
            return;
        }
        try {
            String url = TELEGRAM_API + telegramConfig.getBotToken() + "/deleteMessage";

            ObjectNode body = objectMapper.createObjectNode();
            body.put("chat_id", chatId);
            body.put("message_id", messageId);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> response = getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("Telegram deleteMessage retornou HTTP {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.debug("Falha ao apagar mensagem Telegram: {}", e.getMessage());
        }
    }

    @Override
    public void apagarMensagemDaConta(long messageId) {
        apagarMensagem(telegramConfig.getChatId(), messageId);
    }

    List<String> dividirMensagemChat(String texto) {
        return TelegramMessageSplitter.dividirMensagemChat(texto, LIMITE_CHAT_TELEGRAM);
    }

    private boolean deveSimularEnvioTelegram() {
        return telegramConfig.isSimulate() || credenciaisTelegramAusentes();
    }

    private boolean credenciaisTelegramAusentes() {
        return telegramConfig.getBotToken() == null
            || telegramConfig.getBotToken().isBlank()
            || telegramConfig.getChatId() == null
            || telegramConfig.getChatId().isBlank();
    }

    private String motivoSimulacaoTelegram() {
        if (telegramConfig.isSimulate()) {
            return "telegram.simulate=true";
        }
        return "credenciais_ausentes";
    }

    private String previewLogTelegram(String texto) {
        if (texto == null) {
            return "";
        }
        String normalizado = texto.replaceAll("\\s+", " ").trim();
        if (normalizado.length() <= 180) {
            return normalizado;
        }
        return normalizado.substring(0, 180).trim() + "...";
    }

    private String identificarSegredo(String valor) {
        if (valor.isBlank()) {
            return "[ausente]";
        }
        return "[set]";
    }
}
