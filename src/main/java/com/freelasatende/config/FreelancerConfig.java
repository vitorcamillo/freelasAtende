package com.freelasatende.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao da integracao HTTP do Freelancer.com (cookie-based, como Workana).
 */
@Data
@ConfigurationProperties(prefix = "freelancer")
public class FreelancerConfig {

    public static final String DEFAULT_URL_BASE = "https://www.freelancer.com";
    public static final String DEFAULT_USER_AGENT = FreelasConfig.DEFAULT_USER_AGENT;

    private String urlBase = DEFAULT_URL_BASE;
    private String userAgent = DEFAULT_USER_AGENT;
    private String cookiesArquivo = "data/freelancer-cookies.json";
    private String oauthToken;
    private long bidderId;
    private String workerSlug;
    private String discoveryQuery = "user_role=freelancer&job_category=web-mobile-software-dev&sort=date&per_page=20";
    private int maxProjetos = 20;
    private boolean preferred;
    private boolean verified;
    /** Conta com Escrow.com vinculado: libera projetos marcados is_escrow_project. */
    private boolean escrowVinculado;

    /** Credenciais para login assistido via Chrome real (JavaDriverless). */
    private String email;
    private String senha;
    /** Executa o Chrome de login em modo headless (sem janela). Padrao: true. */
    private boolean loginHeadless = true;

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

    public String getOauthToken() {
        return oauthToken == null ? "" : oauthToken.trim();
    }

    public long getBidderId() {
        return bidderId;
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

    public String getDiscoveryQuery() {
        return discoveryQuery == null ? "" : discoveryQuery.trim();
    }

    public int getMaxProjetos() {
        return Math.max(1, maxProjetos);
    }
}
