package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.UpworkConfig;
import com.freelasatende.model.enums.PlataformaFreela;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Access token da Upwork pelo fluxo OAuth 2.0 documentado.
 *
 * <p>Ate 15/09/2026 a integracao usava o token interno do frontend e, como
 * fallback, o cookie de sessao do navegador. Os dois passaram a ser recusados
 * com HTTP 401 e a plataforma inteira parou. O caminho suportado troca o refresh
 * token por um access token em {@code POST /api/v3/oauth2/token}, com
 * {@code Authorization: Basic base64(client_id:client_secret)} e corpo
 * {@code application/x-www-form-urlencoded}.</p>
 *
 * <p>O token e guardado em memoria ate pouco antes de expirar: sem isso, cada
 * ciclo de polling gastaria uma renovacao.</p>
 */
@Slf4j
class UpworkOAuthTokenProvider {

    /** Margem antes da expiracao, para nao usar token que vence no meio da chamada. */
    private static final long MARGEM_SEGUNDOS = 60;

    private final UpworkConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private volatile String tokenAtual = "";
    private volatile long validoAteMillis;

    UpworkOAuthTokenProvider(UpworkConfig config, ObjectMapper objectMapper, HttpClient httpClient) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    boolean configurado() {
        return config.temOauthConfigurado();
    }

    /** Token valido, renovando quando necessario. Vazio quando OAuth nao esta configurado. */
    synchronized String token() {
        if (!configurado()) {
            return "";
        }
        if (!tokenAtual.isBlank() && System.currentTimeMillis() < validoAteMillis) {
            return tokenAtual;
        }
        renovar();
        return tokenAtual;
    }

    private void renovar() {
        // Medido contra a API real em 15/09/2026: com as credenciais apenas no
        // header Basic, a Upwork responde 400 "Missing parameters: client_id".
        // Ela quer client_id e client_secret no CORPO. O header Basic vai junto
        // porque nao atrapalha e cobre provedores que so aceitam essa forma.
        String corpo = "grant_type=refresh_token"
            + "&refresh_token=" + URLEncoder.encode(config.getOauthRefreshToken(), StandardCharsets.UTF_8)
            + "&client_id=" + URLEncoder.encode(config.getOauthClientId(), StandardCharsets.UTF_8)
            + "&client_secret=" + URLEncoder.encode(config.getOauthClientSecret(), StandardCharsets.UTF_8);
        String credenciais = Base64.getEncoder().encodeToString(
            (config.getOauthClientId() + ":" + config.getOauthClientSecret()).getBytes(StandardCharsets.UTF_8));
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getUrlBase() + "/api/v3/oauth2/token"))
                .header("Authorization", "Basic " + credenciais)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("User-Agent", config.getUserAgent())
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(corpo))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new MarketplaceAdapterException(PlataformaFreela.UPWORK,
                    MarketplaceAdapterErrorType.FALHA_AUTENTICACAO,
                    "Upwork OAuth recusou a renovacao com HTTP " + response.statusCode()
                        + "; confira UPWORK_OAUTH_CLIENT_ID, UPWORK_OAUTH_CLIENT_SECRET e "
                        + "UPWORK_OAUTH_REFRESH_TOKEN");
            }
            JsonNode raiz = objectMapper.readTree(response.body());
            String token = raiz.path("access_token").asText("");
            if (token.isBlank()) {
                throw new MarketplaceAdapterException(PlataformaFreela.UPWORK,
                    MarketplaceAdapterErrorType.FALHA_AUTENTICACAO,
                    "Upwork OAuth respondeu sem access_token");
            }
            long expiraEm = raiz.path("expires_in").asLong(86400);
            tokenAtual = token;
            validoAteMillis = System.currentTimeMillis()
                + Math.max(0, (expiraEm - MARGEM_SEGUNDOS)) * 1000L;
            log.info("Upwork: access token OAuth renovado, valido por {}s", expiraEm);
        } catch (MarketplaceAdapterException e) {
            tokenAtual = "";
            validoAteMillis = 0;
            throw e;
        } catch (Exception e) {
            tokenAtual = "";
            validoAteMillis = 0;
            throw new MarketplaceAdapterException(PlataformaFreela.UPWORK,
                MarketplaceAdapterErrorType.FALHA_REDE, "Falha ao renovar token OAuth da Upwork", e);
        }
    }
}

