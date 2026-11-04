package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.UpworkConfig;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.enums.PlataformaFreela;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
class UpworkClient {

    private static final Pattern TOKEN_SUBORDINADO = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");

    private final UpworkConfig config;
    private final ObjectMapper objectMapper;

    private HttpClient httpClient;
    private UpworkOAuthTokenProvider oauthTokenProvider;
    private final Map<Long, String> roomIds = new ConcurrentHashMap<>();

    /** Provedor OAuth criado sob demanda para reusar o mesmo HttpClient do cliente. */
    private synchronized UpworkOAuthTokenProvider oauth() {
        if (oauthTokenProvider == null) {
            oauthTokenProvider = new UpworkOAuthTokenProvider(config, objectMapper, client());
        }
        return oauthTokenProvider;
    }

    String buscarProjetosHtml() {
        String url = config.getUrlBase() + "/nx/find-work/best-matches";
        return getText(url, "Upwork best matches");
    }

    List<ConversaResumo> listarConversasInbox() {
        Map<String, String> cookies = carregarCookies();
        String orgId = cookies.getOrDefault("current_organization_uid", "");
        if (orgId.isBlank()) {
            throw falhaAutenticacao("Upwork inbox sem current_organization_uid");
        }
        String token = obterTokenMensageiro(cookies);
        String url = config.getUrlBase() + "/api/v3/rooms/rooms?callerOrgId="
            + URLEncoder.encode(orgId, StandardCharsets.UTF_8) + "&limit=50";
        JsonNode root = getJsonAutorizado(url, "Upwork inbox", cookies, token, orgId);
        for (JsonNode room : root.path("rooms")) {
            String roomId = room.path("roomId").asText("");
            if (!roomId.isBlank()) roomIds.put(UpworkInboxMapper.idEstavel(roomId), roomId);
        }
        return UpworkInboxMapper.listarConversas(root, config);
    }

    List<MensagemConversa> listarMensagensConversa(Long conversaId) {
        String roomId = roomIds.get(conversaId);
        if (roomId == null) {
            listarConversasInbox();
            roomId = roomIds.get(conversaId);
        }
        if (roomId == null) return List.of();
        Map<String, String> cookies = carregarCookies();
        String orgId = cookies.getOrDefault("current_organization_uid", "");
        String token = obterTokenMensageiro(cookies);
        String url = config.getUrlBase() + "/api/v3/rooms/rooms/"
            + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
            + "/stories/simplified?callerOrgId=" + URLEncoder.encode(orgId, StandardCharsets.UTF_8)
            + "&limit=100";
        JsonNode root = getJsonAutorizado(url, "Upwork mensagens", cookies, token, orgId);
        return UpworkInboxMapper.listarMensagens(root, conversaId);
    }

    MensagemConversa enviarMensagemConversa(Long conversaId, String texto) {
        if (texto == null || texto.isBlank()) throw new MarketplaceAdapterException(
            PlataformaFreela.UPWORK, MarketplaceAdapterErrorType.FALHA_ENVIO, "Mensagem Upwork vazia");
        String roomId = roomIds.get(conversaId);
        if (roomId == null) {
            listarConversasInbox();
            roomId = roomIds.get(conversaId);
        }
        if (roomId == null) throw new MarketplaceAdapterException(
            PlataformaFreela.UPWORK, MarketplaceAdapterErrorType.CONVERSA_INACESSIVEL, "Conversa Upwork nao encontrada");
        Map<String, String> cookies = carregarCookies();
        String orgId = cookies.getOrDefault("current_organization_uid", "");
        String token = obterTokenMensageiro(cookies);
        JsonNode users = getJsonAutorizado(config.getUrlBase() + "/api/v3/rooms/rooms/"
            + URLEncoder.encode(roomId, StandardCharsets.UTF_8) + "/users?callerOrgId="
            + URLEncoder.encode(orgId, StandardCharsets.UTF_8) + "&limit=100",
            "Upwork participantes", cookies, token, orgId);
        String userId = "";
        for (JsonNode user : users.path("users")) {
            if (orgId.equals(user.path("orgId").asText())) {
                userId = user.path("userId").asText("");
                break;
            }
        }
        if (userId.isBlank()) throw new MarketplaceAdapterException(
            PlataformaFreela.UPWORK, MarketplaceAdapterErrorType.FALHA_ENVIO, "Usuario da conversa Upwork nao encontrado");
        String messageId = userId + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID();
        Map<String, Object> story = new HashMap<>();
        story.put("roomId", roomId);
        story.put("userId", userId);
        story.put("orgId", orgId);
        story.put("messageId", messageId);
        story.put("message", texto);
        story.put("actionType", "eo:post");
        story.put("sourceClient", "thunder");
        story.put("objectReferences", List.of(Map.of("objectType", "eo:message", "metadata", Map.of())));
        postJsonAutorizado(config.getUrlBase() + "/api/v3/rooms/rooms/"
            + URLEncoder.encode(roomId, StandardCharsets.UTF_8) + "/stories?callerOrgId="
            + URLEncoder.encode(orgId, StandardCharsets.UTF_8), story, cookies, token, orgId);
        return MensagemConversa.builder().plataforma(PlataformaFreela.UPWORK)
            .id(UpworkInboxMapper.idEstavel(messageId)).idConversa(conversaId).idPessoa(UpworkInboxMapper.idEstavel(userId))
            .texto(texto).dataCriacaoMillis(System.currentTimeMillis()).enviadaPorMim(true)
            .visualizada(true).excluida(false).sistema(false).build();
    }

    private void postJsonAutorizado(String url, Object body, Map<String, String> cookies, String token, String orgId) {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                .header("User-Agent", config.getUserAgent()).header("Accept", "application/json")
                .header("Content-Type", "application/json").header("Authorization", "bearer " + token)
                .header("vnd-eo-orguid", orgId).header("x-upwork-api-tenantid", orgId)
                .header("Cookie", construirCookieHeader(cookies)).timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build();
            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) throw new MarketplaceAdapterException(
                PlataformaFreela.UPWORK, MarketplaceAdapterErrorType.FALHA_ENVIO,
                "Upwork envio de mensagem retornou HTTP " + response.statusCode());
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(PlataformaFreela.UPWORK,
                MarketplaceAdapterErrorType.FALHA_REDE, "Falha ao enviar mensagem Upwork", e);
        }
    }

    /**
     * Token do mensageiro, com duas fontes.
     *
     * <p>A primeira e o endpoint de token subordinado. Medido em producao em
     * 15/09/2026: ele passou a responder HTTP 401 mesmo logo apos um login
     * bem-sucedido com 35 cookies salvos, o que derrubava a verificacao de
     * sessao e deixava a inbox do Upwork parada sem nenhuma outra pista.</p>
     *
     * <p>A segunda e o cookie {@code oauth2_global_js_token}, que o proprio site
     * usa no front para falar com a API do mensageiro -- e o mesmo cookie que o
     * login ja trata como prova de sessao. Quando o endpoint recusa, esse token
     * assume, em vez de derrubar a plataforma inteira.</p>
     */
    private String obterTokenMensageiro(Map<String, String> cookies) {
        if (oauth().configurado()) {
            return oauth().token();
        }
        String clientId = config.getMessagesClientId();
        if (clientId.isBlank()) throw falhaAutenticacao("Upwork messages client id ausente");
        String url = config.getAuthUrlBase() + "/api/v3/oauth2/token/subordinate/v3/" + clientId;
        String motivoEndpoint;
        try {
            String javascript = getTextComCookies(url, "Upwork token do mensageiro", cookies, config.getUrlBase() + "/");
            Matcher matcher = TOKEN_SUBORDINADO.matcher(javascript);
            if (matcher.find() && !matcher.group(1).isBlank()) {
                return matcher.group(1);
            }
            motivoEndpoint = "resposta sem token";
        } catch (MarketplaceAdapterException e) {
            motivoEndpoint = e.getMessage();
        }

        String tokenDoCookie = tokenDeCookie(cookies);
        if (!tokenDoCookie.isBlank()) {
            log.warn("Endpoint de token do Upwork indisponivel ({}); usando o token de sessao do cookie.",
                motivoEndpoint);
            return tokenDoCookie;
        }
        throw falhaAutenticacao("Upwork nao forneceu token do mensageiro (" + motivoEndpoint
            + ") e a sessao salva nao tem oauth2_global_js_token; cookies presentes: "
            + String.join(", ", new java.util.TreeSet<>(cookies.keySet())));
    }

    /** Token que o front do Upwork usa; vazio quando a sessao salva nao o tem. */
    static String tokenDeCookie(Map<String, String> cookies) {
        if (cookies == null) {
            return "";
        }
        String token = cookies.getOrDefault("oauth2_global_js_token", "");
        return token == null ? "" : token.trim();
    }

    private JsonNode getJsonAutorizado(
        String url, String descricao, Map<String, String> cookies, String token, String orgId
    ) {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                .header("User-Agent", config.getUserAgent())
                .header("Accept", "application/json")
                .header("Authorization", "bearer " + token)
                .header("vnd-eo-orguid", orgId)
                .header("x-upwork-api-tenantid", orgId)
                .header("Referer", config.getUrlBase() + "/ab/messages/rooms/")
                .header("Cookie", construirCookieHeader(cookies))
                .timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new MarketplaceAdapterException(PlataformaFreela.UPWORK,
                    MarketplaceAdapterErrorType.FALHA_REDE,
                    descricao + " retornou HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(PlataformaFreela.UPWORK,
                MarketplaceAdapterErrorType.FALHA_REDE, "Falha ao consultar " + descricao, e);
        }
    }

    private String getTextComCookies(String url, String descricao, Map<String, String> cookies, String referer) {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                .header("User-Agent", config.getUserAgent()).header("Accept", "*/*")
                .header("Referer", referer).header("Cookie", construirCookieHeader(cookies))
                .timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) throw falhaAutenticacao(descricao + " retornou HTTP " + response.statusCode());
            return response.body();
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(PlataformaFreela.UPWORK,
                MarketplaceAdapterErrorType.FALHA_REDE, "Falha ao consultar " + descricao, e);
        }
    }

    private MarketplaceAdapterException falhaAutenticacao(String mensagem) {
        return new MarketplaceAdapterException(PlataformaFreela.UPWORK,
            MarketplaceAdapterErrorType.FALHA_AUTENTICACAO, mensagem);
    }

    // enviarPergunta(ciphertext, mensagem) foi removido: o endpoint REST antigo
    // "/api/messages/v3/conversations" foi descontinuado pela Upwork (confirmado
    // em producao: HTTP 404 em 2026-09-01) e nao existe mutacao GraphQL publica
    // equivalente para abrir uma sala ANTES da candidatura. O fluxo suportado
    // e via proposta (ver UpworkProposalClient.enviar), que ja cria a sala
    // implicitamente. Duvidas pos-candidatura usam enviarMensagemConversa acima,
    // que opera sobre uma sala ja existente via API interna do mensageiro.

    private JsonNode getJson(String url, String descricao) {
        try {
            Map<String, String> cookies = carregarCookies();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", config.getUserAgent())
                .header("Accept", "application/json,text/plain,*/*")
                .header("Accept-Language", "en-US,en;q=0.9,pt-BR;q=0.8,pt;q=0.7")
                .timeout(Duration.ofSeconds(30))
                .GET();
            if (!cookies.isEmpty()) {
                builder.header("Cookie", construirCookieHeader(cookies));
            }
            HttpResponse<String> response = client().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new MarketplaceAdapterException(
                    PlataformaFreela.UPWORK,
                    MarketplaceAdapterErrorType.FALHA_REDE,
                    descricao + " retornou HTTP " + response.statusCode()
                );
            }
            return objectMapper.readTree(response.body());
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(
                PlataformaFreela.UPWORK,
                MarketplaceAdapterErrorType.FALHA_REDE,
                "Falha ao consultar " + descricao,
                e
            );
        }
    }

    private String getText(String url, String descricao) {
        try {
            Map<String, String> cookies = carregarCookies();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", config.getUserAgent())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9,pt-BR;q=0.8,pt;q=0.7")
                .timeout(Duration.ofSeconds(30))
                .GET();
            if (!cookies.isEmpty()) {
                builder.header("Cookie", construirCookieHeader(cookies));
            }
            HttpResponse<String> response = client().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new MarketplaceAdapterException(
                    PlataformaFreela.UPWORK,
                    MarketplaceAdapterErrorType.FALHA_REDE,
                    descricao + " retornou HTTP " + response.statusCode()
                );
            }
            return response.body();
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(
                PlataformaFreela.UPWORK,
                MarketplaceAdapterErrorType.FALHA_REDE,
                "Falha ao consultar " + descricao,
                e
            );
        }
    }

    Map<String, String> carregarCookies() {
        String arquivo = config.getCookiesArquivo();
        if (arquivo.isBlank() || !java.nio.file.Files.exists(java.nio.file.Path.of(arquivo))) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(java.nio.file.Path.of(arquivo).toFile());
            Map<String, String> cookies = new HashMap<>();
            if (root.isObject()) {
                root.fields().forEachRemaining(entry -> cookies.put(entry.getKey(), entry.getValue().asText("")));
            } else if (root.isArray()) {
                for (JsonNode cookie : root) {
                    String name = cookie.path("name").asText("");
                    String value = cookie.path("value").asText("");
                    if (!name.isBlank() && !value.isBlank()) {
                        cookies.put(name, value);
                    }
                }
            }
            cookies.entrySet().removeIf(e -> e.getKey().isBlank() || e.getValue().isBlank());
            return cookies;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(
                PlataformaFreela.UPWORK,
                MarketplaceAdapterErrorType.FALHA_PARSE,
                "Falha ao carregar cookies Upwork",
                e
            );
        }
    }

    private String construirCookieHeader(Map<String, String> cookies) {
        List<String> partes = new ArrayList<>();
        cookies.forEach((nome, valor) -> partes.add(nome + "=" + valor));
        return String.join("; ", partes);
    }

    private synchronized HttpClient client() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        }
        return httpClient;
    }
}
