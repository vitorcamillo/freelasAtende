package com.freelasatende.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao da integracao HTTP da Upwork (cookie-based, como Workana).
 */
@Data
@ConfigurationProperties(prefix = "upwork")
public class UpworkConfig {

    public static final String DEFAULT_URL_BASE = "https://www.upwork.com";
    public static final String DEFAULT_USER_AGENT = FreelasConfig.DEFAULT_USER_AGENT;

    private String urlBase = DEFAULT_URL_BASE;
    private String userAgent = DEFAULT_USER_AGENT;
    private String cookiesArquivo = "data/upwork-cookies.json";
    private String workerSlug;
    private String discoveryQuery = "q=java+spring+boot+python&sort=recency&per_page=20";
    private int maxProjetos = 20;
    /** Client id publico usado pelo frontend do Upwork para o token temporario do mensageiro. */
    private String messagesClientId = "20f1626cfa6ce37af2e83cd472294c59";
    private String proposalsClientId = "1598ce365dbeb5bdea40df51eeb97780";
    private String authUrlBase = "https://auth.upwork.com";

    /**
     * Credenciais do app OAuth 2.0 da Upwork (Settings > API Access).
     *
     * <p>Medido em 15/09/2026: o endpoint de token interno do frontend passou a
     * responder HTTP 401 e o cookie de sessao do navegador tambem foi recusado
     * pela API do mensageiro. O caminho suportado para integracao e o OAuth
     * documentado: troca-se o refresh token por um access token em
     * POST /api/v3/oauth2/token. Quando estas tres propriedades estao
     * preenchidas, elas tem prioridade sobre a raspagem de sessao.</p>
     */
    private String oauthClientId;
    private String oauthClientSecret;
    private String oauthRefreshToken;


    /** Credenciais para login assistido via Chrome real (JavaDriverless). */
    private String email;
    private String senha;
    /** Executa o Chrome de login em modo headless (sem janela). Padrao: true. */
    private boolean loginHeadless = true;
    /**
     * Resposta(s) das perguntas de seguranca do Upwork. Aceita uma resposta
     * unica (ex.: "Teta") ou pares "trecho-da-pergunta=resposta" separados por
     * ponto e virgula quando ha mais de uma pergunta possivel. Ex.:
     * "best friend=Teta;first pet=Rex".
     */
    private String securityAnswer;

    public String getOauthClientId() {
        return oauthClientId == null ? "" : oauthClientId.trim();
    }

    public String getOauthClientSecret() {
        return oauthClientSecret == null ? "" : oauthClientSecret.trim();
    }

    public String getOauthRefreshToken() {
        return oauthRefreshToken == null ? "" : oauthRefreshToken.trim();
    }

    /** So vale como configurado quando as tres partes existem. */
    public boolean temOauthConfigurado() {
        return !getOauthClientId().isBlank()
            && !getOauthClientSecret().isBlank()
            && !getOauthRefreshToken().isBlank();
    }

    public String getUrlBase() {
        if (urlBase == null || urlBase.isBlank()) {
            return DEFAULT_URL_BASE;
        }
        return urlBase.endsWith("/") ? urlBase.substring(0, urlBase.length() - 1) : urlBase;
    }

    public String getUserAgent() {
        return userAgent == null || userAgent.isBlank() ? DEFAULT_USER_AGENT : userAgent;
    }

    public String getCookiesArquivo() {
        return cookiesArquivo == null ? "" : cookiesArquivo;
    }

    public String getWorkerSlug() {
        return workerSlug == null ? "" : workerSlug.trim();
    }

    public String getEmail() {
        return email == null ? "" : email.trim();
    }

    public String getSenha() {
        return senha == null ? "" : senha;
    }

    public boolean isLoginHeadless() {
        return loginHeadless;
    }

    public String getSecurityAnswer() {
        return securityAnswer == null ? "" : securityAnswer;
    }

    public String getDiscoveryQuery() {
        return discoveryQuery == null ? "" : discoveryQuery.trim();
    }

    public int getMaxProjetos() {
        return Math.max(1, maxProjetos);
    }

    public String getMessagesClientId() {
        return messagesClientId == null ? "" : messagesClientId.trim();
    }
}
