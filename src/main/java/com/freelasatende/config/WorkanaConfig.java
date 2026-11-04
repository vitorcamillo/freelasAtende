package com.freelasatende.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "workana")
public class WorkanaConfig {

    public static final String DEFAULT_URL_BASE = "https://www.workana.com";
    public static final String DEFAULT_USER_AGENT = FreelasConfig.DEFAULT_USER_AGENT;

    private String urlBase = DEFAULT_URL_BASE;
    private String userAgent = DEFAULT_USER_AGENT;
    private String cookiesArquivo = "data/workana-cookies.json";
    private String workerSlug;
    private String discoveryQuery = "category=it-programming&language=pt";
    private int maxProjetos = 20;

    public String getUrlBase() {
        if (urlBase == null || urlBase.isBlank()) {
            return DEFAULT_URL_BASE;
        }
        return urlBase.endsWith("/")
            ? urlBase.substring(0, urlBase.length() - 1)
            : urlBase;
    }

    public String getUserAgent() {
        if (userAgent == null || userAgent.isBlank()) {
            return DEFAULT_USER_AGENT;
        }
        return userAgent;
    }

    public String getCookiesArquivo() {
        return cookiesArquivo == null ? "" : cookiesArquivo;
    }

    public String getWorkerSlug() {
        return workerSlug == null ? "" : workerSlug.trim();
    }

    public void setDiscoveryQuery(String discoveryQuery) {
        this.discoveryQuery = discoveryQuery;
    }

    public String getDiscoveryQuery() {
        if (discoveryQuery == null || discoveryQuery.isBlank()) {
            return "category=it-programming&language=pt";
        }
        String query = discoveryQuery.trim();
        return query.startsWith("?") ? query.substring(1) : query;
    }

    public int getMaxProjetos() {
        return Math.max(1, maxProjetos);
    }
}
