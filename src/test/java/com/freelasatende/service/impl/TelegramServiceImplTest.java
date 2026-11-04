package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.TelegramConfig;
import com.freelasatende.model.dto.ProjetoInteresseResumoDTO;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.service.impl.TelegramServiceImplTestSupport.CapturingHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigDecimal;
import java.util.List;

import static com.freelasatende.service.impl.TelegramServiceImplTestSupport.response;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Telegram: mensagem de projeto interessante")
class TelegramServiceImplTest {

    private final TelegramServiceImpl service = criarService();

    @Test
    @DisplayName("Inclui leitura curta da IA sem duplicar detalhes da pendência")
    void incluiLeituraCurtaDaIaSemDuplicarDetalhesDaPendencia() {
        String mensagem = service.montarMensagemProjetoInteressante(
            Projeto.builder()
                .id(44L)
                .titulo("Site institucional")
                .categoria("Web")
                .orcamento(BigDecimal.valueOf(1200))
                .numeroPropostas(3)
                .nivelExperiencia("Intermediario")
                .descricaoCurta("Site com formulario e WhatsApp.")
                .build(),
            ProjetoInteresseResumoDTO.builder()
                .motivoInteresse("Interessante com ressalvas: IA marcou como atendivel.")
                .nivelConviccao("MEDIA COM DUVIDAS")
                .sinaisPositivos(List.of("Motivo da IA: site institucional dentro do escopo"))
                .pontosAtencao(List.of("Confirmar prazo antes da proposta"))
                .atitudeSugerida("Conversar com voce antes de perguntar ao cliente.")
                .build()
        );

        assertTrue(mensagem.contains("Projeto interessante"));
        assertTrue(mensagem.contains("Leitura da IA"));
        assertTrue(mensagem.contains("Motivo:"));
        assertTrue(mensagem.contains("Convicção:"));
        assertTrue(mensagem.contains("MEDIA COM DUVIDAS"));
        assertTrue(mensagem.contains("Próximo:"));
        assertFalse(mensagem.contains("Site com formulario e WhatsApp."));
        assertFalse(mensagem.contains("Confirmar prazo antes da proposta"));
    }

    @Test
    @DisplayName("Dúvidas explicam motivo, convicção, falta e atitude")
    void duvidasExplicamMotivoConviccaoFaltaEAtitude() {
        String mensagem = service.montarMensagemDuvidas(
            Projeto.builder()
                .id(45L)
                .titulo("Landing page")
                .categoria("Web")
                .orcamento(BigDecimal.valueOf(900))
                .build(),
            "Confirmar prazo antes da proposta."
        );

        assertTrue(mensagem.contains("Dúvida antes de falar com cliente"));
        assertTrue(mensagem.contains("Leitura da IA"));
        assertTrue(mensagem.contains("Motivo:"));
        assertTrue(mensagem.contains("Convicção: MEDIA COM DUVIDA"));
        assertTrue(mensagem.contains("O que falta:"));
        assertTrue(mensagem.contains("Atitude:"));
        assertTrue(mensagem.contains("Nada é enviado sem aprovação."));
    }

    @Test
    @DisplayName("Proposta explica motivo, convicção, sinais, atenção e atitude")
    void propostaExplicaMotivoConviccaoSinaisAtencaoEAtitude() {
        String mensagem = service.montarMensagemProposta(
            Projeto.builder()
                .id(46L)
                .titulo("Site institucional")
                .orcamento(BigDecimal.valueOf(1200))
                .build(),
            Proposta.builder()
                .valorProposto(BigDecimal.valueOf(1400))
                .valorMinimo(BigDecimal.valueOf(900))
                .mediaMercado(BigDecimal.valueOf(1300))
                .build()
        );

        assertTrue(mensagem.contains("Proposta sugerida"));
        assertTrue(mensagem.contains("Leitura da IA"));
        assertTrue(mensagem.contains("Motivo:"));
        assertTrue(mensagem.contains("Convicção: ALTA"));
        assertTrue(mensagem.contains("Sinais:"));
        assertTrue(mensagem.contains("Atenção:"));
        assertTrue(mensagem.contains("Atitude:"));
        assertTrue(mensagem.contains("Nada é enviado sem aprovação."));
    }

    @Test
    @DisplayName("Ação pendente envia reply markup com callbacks exatos")
    void acaoPendenteEnviaReplyMarkupComCallbacksExatos() throws Exception {
        CapturingHttpClient httpClient =
            new CapturingHttpClient(response(200, "{\"ok\":true,\"result\":{\"message_id\":987}}"));
        TelegramServiceImpl service = criarService(httpClient);

        Long messageId = service.enviarAcaoPendenteComBotoes("*ACAO PENDENTE*", 77L);

        assertEquals(1, httpClient.requestCount());
        assertEquals(987L, messageId);
        JsonNode body = httpClient.bodyJson(0);
        JsonNode keyboard = body.path("reply_markup").path("inline_keyboard");
        assertEquals("123", body.path("chat_id").asText());
        assertEquals("Markdown", body.path("parse_mode").asText());
        assertTrue(body.path("disable_web_page_preview").asBoolean());
        assertEquals(5, keyboard.size());
        assertEquals("✅ Bom tom", keyboard.get(0).get(0).path("text").asText());
        assertEquals("acao:aprovar:77:BOM_TOM", keyboard.get(0).get(0).path("callback_data").asText());
        assertEquals("✅ Escopo claro", keyboard.get(0).get(1).path("text").asText());
        assertEquals("acao:aprovar:77:ESCOPO_CLARO", keyboard.get(0).get(1).path("callback_data").asText());
        assertEquals("❌ Robótico", keyboard.get(1).get(0).path("text").asText());
        assertEquals("acao:recusar:77:ROBOTICO", keyboard.get(1).get(0).path("callback_data").asText());
        assertEquals("❌ Risco", keyboard.get(1).get(1).path("text").asText());
        assertEquals("acao:recusar:77:RISCO_PLATAFORMA", keyboard.get(1).get(1).path("callback_data").asText());
        assertEquals("📏 Muito longo", keyboard.get(2).get(0).path("text").asText());
        assertEquals("acao:recusar:77:TEXTO_LONGO", keyboard.get(2).get(0).path("callback_data").asText());
        assertEquals("🔁 Repetitivo", keyboard.get(2).get(1).path("text").asText());
        assertEquals("acao:recusar:77:REPETITIVO", keyboard.get(2).get(1).path("callback_data").asText());
        assertEquals("✍️ Reescrever", keyboard.get(3).get(0).path("text").asText());
        assertEquals("acao:reescrever:77", keyboard.get(3).get(0).path("callback_data").asText());
        assertEquals("🧠 Ensinar", keyboard.get(3).get(1).path("text").asText());
        assertEquals("acao:ensinar:77", keyboard.get(3).get(1).path("callback_data").asText());
        assertEquals("🎯 Focar", keyboard.get(4).get(0).path("text").asText());
        assertEquals("acao:foco:77", keyboard.get(4).get(0).path("callback_data").asText());
        assertEquals("🔎 Ver ação", keyboard.get(4).get(1).path("text").asText());
        assertEquals("acao:detalhe:77", keyboard.get(4).get(1).path("callback_data").asText());
    }

    @Test
    @DisplayName("Ação para revisão não mostra botões de aprovação")
    void acaoParaRevisaoNaoMostraBotoesDeAprovacao() throws Exception {
        CapturingHttpClient httpClient = new CapturingHttpClient(response(200, "{\"ok\":true,\"result\":{\"message_id\":988}}"));
        TelegramServiceImpl service = criarService(httpClient);

        service.enviarAcaoPendenteParaRevisaoComBotoes("Reescreva antes de aprovar", 78L);

        JsonNode keyboard = httpClient.bodyJson(0).path("reply_markup").path("inline_keyboard");
        assertEquals(4, keyboard.size());
        for (JsonNode linha : keyboard) {
            for (JsonNode botao : linha) {
                assertFalse(botao.path("callback_data").asText().startsWith("acao:aprovar:"));
            }
        }
        assertEquals("❌ Robótico", keyboard.get(0).get(0).path("text").asText());
        assertEquals("📏 Muito longo", keyboard.get(1).get(0).path("text").asText());
        assertEquals("✍️ Reescrever", keyboard.get(2).get(0).path("text").asText());
        assertEquals("🔎 Ver ação", keyboard.get(3).get(1).path("text").asText());
    }

    @Test
    @DisplayName("Fallback 400 remove Markdown e preserva botões")
    void fallback400RemoveMarkdownEPreservaBotoes() throws Exception {
        CapturingHttpClient httpClient = new CapturingHttpClient(
            response(400, "{\"ok\":false,\"description\":\"Bad Request: can't parse entities\"}"),
            response(200, "{\"ok\":true}")
        );
        TelegramServiceImpl service = criarService(httpClient);

        service.enviarAcaoPendenteComBotoes("*Texto* com _risco_ `markdown`", 88L);

        assertEquals(2, httpClient.requestCount());
        JsonNode tentativaMarkdown = httpClient.bodyJson(0);
        JsonNode tentativaFallback = httpClient.bodyJson(1);
        assertEquals("Markdown", tentativaMarkdown.path("parse_mode").asText());
        assertFalse(tentativaFallback.has("parse_mode"));
        assertEquals("Texto com risco markdown", tentativaFallback.path("text").asText());
        assertTrue(tentativaFallback.path("disable_web_page_preview").asBoolean());
        assertEquals(
            "acao:foco:88",
            tentativaFallback.path("reply_markup").path("inline_keyboard").get(4).get(0).path("callback_data").asText()
        );
    }

    @Test
    @DisplayName("Responder callback envia payload esperado")
    void responderCallbackEnviaPayloadEsperado() throws Exception {
        CapturingHttpClient httpClient = new CapturingHttpClient(response(200, "{\"ok\":true}"));
        TelegramServiceImpl service = criarService(httpClient);

        service.responderCallback("cb-123", "Ação aprovada");

        assertEquals(1, httpClient.requestCount());
        assertTrue(httpClient.requests().get(0).uri().toString().endsWith("/answerCallbackQuery"));
        JsonNode body = httpClient.bodyJson(0);
        assertEquals("cb-123", body.path("callback_query_id").asText());
        assertEquals("Ação aprovada", body.path("text").asText());
        assertFalse(body.path("show_alert").asBoolean());
    }

    @Test
    @DisplayName("Apagar mensagem envia deleteMessage para limpar botões antigos")
    void apagarMensagemEnviaDeleteMessage() throws Exception {
        CapturingHttpClient httpClient = new CapturingHttpClient(response(200, "{\"ok\":true}"));
        TelegramServiceImpl service = criarService(httpClient);

        service.apagarMensagem("123", 321L);

        assertEquals(1, httpClient.requestCount());
        assertTrue(httpClient.requests().get(0).uri().toString().endsWith("/deleteMessage"));
        JsonNode body = httpClient.bodyJson(0);
        assertEquals("123", body.path("chat_id").asText());
        assertEquals(321L, body.path("message_id").asLong());
    }

    @Test
    @DisplayName("Notificação simples não envia botões")
    void notificacaoSimplesNaoEnviaBotoes() throws Exception {
        CapturingHttpClient httpClient = new CapturingHttpClient(response(200, "{\"ok\":true}"));
        TelegramServiceImpl service = criarService(httpClient);

        service.enviarNotificacao("Mensagem operacional");

        assertEquals(1, httpClient.requestCount());
        assertFalse(httpClient.bodyJson(0).has("reply_markup"));
    }

    @Test
    @DisplayName("Obter updates retorna JSON e monta offset correto")
    void obterUpdatesRetornaJsonEMontaOffsetCorreto() throws Exception {
        CapturingHttpClient httpClient =
            new CapturingHttpClient(response(200, "{\"ok\":true,\"result\":[{\"update_id\":42}]}"));
        TelegramServiceImpl service = criarService(httpClient);

        JsonNode updates = service.obterUpdates(123L);

        assertNotNull(updates);
        assertTrue(updates.path("ok").asBoolean());
        assertEquals(42, updates.path("result").get(0).path("update_id").asInt());
        assertEquals(1, httpClient.requestCount());
        assertTrue(httpClient.requests().get(0).uri().toString().contains("/getUpdates?offset=123&timeout=5"));
        assertEquals("GET", httpClient.requests().get(0).method());
    }

    @Test
    @DisplayName("Obter updates retorna null em resposta não 200")
    void obterUpdatesRetornaNullEmRespostaNao200() {
        CapturingHttpClient httpClient = new CapturingHttpClient(response(500, "{\"ok\":false}"));
        TelegramServiceImpl service = criarService(httpClient);

        JsonNode updates = service.obterUpdates(10L);

        assertNull(updates);
        assertEquals(1, httpClient.requestCount());
    }

    @Test
    @DisplayName("SendMessage não tenta fallback em erro diferente de 400")
    void sendMessageNaoTentaFallbackEmErroDiferenteDe400() {
        CapturingHttpClient httpClient = new CapturingHttpClient(response(500, "{\"ok\":false}"));
        TelegramServiceImpl service = criarService(httpClient);

        service.enviarNotificacao("*Mensagem*");

        assertEquals(1, httpClient.requestCount());
    }

    @Test
    @DisplayName("Timeout HTTP do Telegram nao propaga excecao")
    void timeoutHttpTelegramNaoPropagaExcecao() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new IOException("request timed out"));
        TelegramServiceImpl service = criarService(httpClient);

        assertDoesNotThrow(() -> service.enviarNotificacao("Mensagem operacional"));
    }

    @Test
    @DisplayName("Chat formatado preserva Markdown, code fence e divide mensagens longas")
    void chatFormatadoPreservaMarkdownCodeFenceEDivideMensagensLongas() throws Exception {
        CapturingHttpClient httpClient = new CapturingHttpClient(
            response(200, "{\"ok\":true}"),
            response(200, "{\"ok\":true}")
        );
        TelegramServiceImpl service = criarService(httpClient);
        String mensagem = """
            *Resposta*

            Segue exemplo:
            ```java
            public class Exemplo {
                public static void main(String[] args) {
                    System.out.println("ok");
                }
            }
            ```

            %s
            """.formatted("linha longa\n".repeat(420));

        service.enviarMensagemChat(mensagem);

        assertTrue(httpClient.requestCount() > 1);
        JsonNode primeira = httpClient.bodyJson(0);
        assertEquals("Markdown", primeira.path("parse_mode").asText());
        assertTrue(primeira.path("text").asText().contains("```java"));
        assertTrue(primeira.path("disable_web_page_preview").asBoolean());
        for (int i = 0; i < httpClient.requestCount(); i++) {
            assertTrue(httpClient.bodyJson(i).path("text").asText().length() <= 3800);
            assertFalse(httpClient.bodyJson(i).has("reply_markup"));
        }
    }

    @Test
    @DisplayName("Sem credenciais Telegram simula envio e não chama HTTP")
    void semCredenciaisTelegramSimulaEnvioENaoChamaHttp() {
        CapturingHttpClient httpClient = new CapturingHttpClient(response(200, "{\"ok\":true}"));
        TelegramConfig config = new TelegramConfig();
        config.setBotToken("");
        config.setChatId("");
        TelegramServiceImpl service = new TelegramServiceImpl(config, new ObjectMapper());
        ReflectionTestUtils.setField(service, "httpClient", httpClient);

        service.enviarNotificacao("Mensagem segura");

        assertEquals(0, httpClient.requestCount());
    }

    private TelegramServiceImpl criarService() {
        return criarService(null);
    }

    private TelegramServiceImpl criarService(HttpClient httpClient) {
        TelegramConfig config = new TelegramConfig();
        config.setBotToken("test-token");
        config.setChatId("123");
        TelegramServiceImpl telegramService = new TelegramServiceImpl(config, new ObjectMapper());
        if (httpClient != null) {
            ReflectionTestUtils.setField(telegramService, "httpClient", httpClient);
        }
        return telegramService;
    }

}
