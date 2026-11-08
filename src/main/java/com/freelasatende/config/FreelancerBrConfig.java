package com.freelasatende.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao da integracao do Freelancer.com.br (marketplace BR independente).
 *
 * <p>O <b>discovery</b> usa a API JSON global do Freelancer
 * ({@code /api/projects/0.1/projects/active/}) filtrando projetos em portugues:
 * e o mesmo backend do freelancer.com, publico, sem cookie e — ao contrario da
 * listagem HTML {@code /projetos} — <b>fora do Cloudflare Managed Challenge</b>.
 * Ja a <b>inbox/mensagens</b> continua cookie-based com login manual, porque a
 * area autenticada fica atras do Cloudflare (ver docs/COOKIES_FREELANCER_BR.md).</p>
 */
@Data
@ConfigurationProperties(prefix = "freelancer-br")
public class FreelancerBrConfig {

    public static final String DEFAULT_URL_BASE = "https://www.freelancer.com.br";
    /** Base da API JSON global (mesmo backend do .com.br, sem Cloudflare). */
    public static final String DEFAULT_API_URL_BASE = "https://www.freelancer.com";
    /** Filtro padrao: projetos ativos em portugues, mais recentes primeiro. */
    public static final String DEFAULT_DISCOVERY_QUERY =
        "languages[]=pt&sort_field=time_updated&reverse_sort=true&full_description=true&job_details=true";
    public static final String DEFAULT_USER_AGENT = FreelasConfig.DEFAULT_USER_AGENT;
    /** Transporte com fingerprint de Chrome (curl_cffi); o Cloudflare recusa o HttpClient do Java. */
    public static final String DEFAULT_HTTP_SCRIPT = "scripts/freelas_http.py";

    private String urlBase = DEFAULT_URL_BASE;
    private String apiUrlBase = DEFAULT_API_URL_BASE;
    private String userAgent = DEFAULT_USER_AGENT;
    private String cookiesArquivo = "data/freelancerbr-cookies.json";
    private String discoveryQuery = "";
    private int maxProjetos = 20;
    private String httpScript = DEFAULT_HTTP_SCRIPT;
    /** Credenciais do proprio .com.br (site Laravel independente do freelancer.com). */
    private String email = "";
    private String senha = "";
    /** Credenciais OAuth da conta global (mesma usada no freelancer.com) para o envio via API. */
    private String oauthToken = "";
    private long bidderId;

    public String getUrlBase() {
        if (urlBase == null || urlBase.isBlank()) {
            return DEFAULT_URL_BASE;
        }
        return urlBase.endsWith("/") ? urlBase.substring(0, urlBase.length() - 1) : urlBase;
    }

    public String getApiUrlBase() {
        if (apiUrlBase == null || apiUrlBase.isBlank()) {
            return DEFAULT_API_URL_BASE;
        }
        return apiUrlBase.endsWith("/") ? apiUrlBase.substring(0, apiUrlBase.length() - 1) : apiUrlBase;
    }

    public String getUserAgent() {
        return userAgent == null || userAgent.isBlank() ? DEFAULT_USER_AGENT : userAgent;
    }

    public String getCookiesArquivo() {
        return cookiesArquivo == null ? "" : cookiesArquivo;
    }

    public String getHttpScript() {
        return httpScript == null || httpScript.isBlank() ? DEFAULT_HTTP_SCRIPT : httpScript;
    }

    public String getEmail() {
        return email == null ? "" : email.trim();
    }

    public String getSenha() {
        return senha == null ? "" : senha;
    }

    /** Login automatico so e possivel com as duas credenciais do .com.br. */
    public boolean temCredenciais() {
        return !getEmail().isBlank() && !getSenha().isBlank();
    }

    public String getDiscoveryQuery() {
        String q = discoveryQuery == null ? "" : discoveryQuery.trim();
        return q.isBlank() ? DEFAULT_DISCOVERY_QUERY : q;
    }

    public String getOauthToken() {
        return oauthToken == null ? "" : oauthToken.trim();
    }

    public long getBidderId() {
        return bidderId;
    }

    public int getMaxProjetos() {
        return Math.max(1, maxProjetos);
    }
}
