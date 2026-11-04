package com.freelasatende.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configurações do 99Freelas.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "freelas")
public class FreelasConfig {

    public static final String DEFAULT_URL_BASE = "https://www.99freelas.com.br";
    public static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36 Edg/147.0.0.0";

    private String email;
    private String senha;
    /**
     * Alias para compatibilidade com env var `FREELAS_PASSWORD`.
     * Mantemos `senha` como nome principal para não quebrar chamadas existentes.
     */
    private String password;
    private String urlBase = DEFAULT_URL_BASE;
    private String userAgent = DEFAULT_USER_AGENT;

    public String getSenha() {
        if (senha != null && !senha.isBlank()) {
            return senha;
        }
        return password;
    }

    public String getUrlBase() {
        if (urlBase == null || urlBase.isBlank()) {
            return DEFAULT_URL_BASE;
        }
        return urlBase.endsWith("/")
            ? urlBase.substring(0, urlBase.length() - 1)
            : urlBase;
    }

    public String getUserAgent() {
        if (userAgent != null && !userAgent.isBlank()) {
            return userAgent;
        }
        return DEFAULT_USER_AGENT;
    }
}
