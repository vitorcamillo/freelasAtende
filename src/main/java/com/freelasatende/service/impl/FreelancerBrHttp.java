package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.FreelancerBrConfig;
import com.freelasatende.model.enums.PlataformaFreela;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Transporte HTTP do Freelancer.com.br com impressao digital de Chrome real.
 *
 * <p>O site fica atras do Cloudflare, que inspeciona o handshake TLS. O
 * {@code HttpClient} do Java tem fingerprint de Java e e recusado; por isso as
 * requisicoes saem por {@code scripts/freelas_http.py} (curl_cffi com
 * impersonate de Chrome), o mesmo mecanismo ja usado no 99Freelas.</p>
 *
 * <p>Detalhe critico: o backend Laravel do .com.br <b>rotaciona o cookie de
 * sessao a cada resposta</b>. O script regrava o arquivo de cookies com os
 * valores novos, o que mantem a sessao viva indefinidamente. Sem isso a sessao
 * morria no {@code Max-Age} original (30 min) e o sistema pedia login manual
 * varias vezes por dia.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class FreelancerBrHttp {

    private static final String COOKIE_DOMAIN = ".freelancer.com.br";
    private static final String IMPERSONATE = "chrome146";
    private static final int TIMEOUT_SEGUNDOS = 60;

    private final FreelancerBrConfig config;
    private final ObjectMapper objectMapper;

    /** Resposta bruta do transporte: status, URL final e corpo ja decodificado. */
    record Resposta(int status, String url, String body) {
        boolean sucesso() {
            return status >= 200 && status < 400;
        }
    }

    Resposta get(String url, String accept, String referer) {
        return executar("GET", url, accept, referer, null, null, true);
    }

    Resposta getJson(String url, String referer) {
        return executar("GET", url, "application/json, text/plain, */*", referer, null, null, true);
    }

    /**
     * POST de formulario navegado (ex.: "Estou interessado"). O servidor responde
     * com redirect 302 de volta para a pagina do projeto, que fica sob a unica
     * rota bloqueada pelo Cloudflare ({@code /projetos}); por isso NAO seguimos o
     * redirect: segui-lo transformaria um envio bem-sucedido em falso 403.
     */
    Resposta postForm(String url, String corpo, String referer) {
        return executar("POSTFORM", url, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            referer, corpo, "application/x-www-form-urlencoded", false);
    }

    private Resposta executar(String metodo, String url, String accept, String referer,
                              String corpo, String contentType, boolean seguirRedirect) {
        String arquivoCookies = config.getCookiesArquivo();
        List<String> comando = new ArrayList<>(List.of(
            "python3", config.getHttpScript(),
            "--method", metodo,
            "--url", url,
            "--cookie-file", arquivoCookies,
            "--cookie-domain", COOKIE_DOMAIN,
            "--cookie-jar-out", arquivoCookies,
            "--impersonate", IMPERSONATE,
            "--user-agent", userAgentEfetivo(),
            "--accept", accept));
        if (referer != null && !referer.isBlank()) {
            comando.add("--referer");
            comando.add(referer);
        }
        if (contentType != null && !contentType.isBlank()) {
            comando.add("--content-type");
            comando.add(contentType);
        }
        if (!seguirRedirect) {
            comando.add("--no-redirect");
        }

        try {
            String stdout = PythonHttpProcess.run(comando, corpo, Duration.ofSeconds(TIMEOUT_SEGUNDOS));
            JsonNode root = objectMapper.readTree(stdout);
            if (root.hasNonNull("error")) {
                throw falhaRede("Transporte HTTP Freelancer.com.br: " + root.path("error").asText(), null);
            }
            String body = new String(
                Base64.getDecoder().decode(root.path("body_base64").asText("")), StandardCharsets.UTF_8);
            return new Resposta(root.path("status").asInt(), root.path("url").asText(url), body);
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (TimeoutException e) {
            throw falhaRede("Timeout no transporte HTTP Freelancer.com.br", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw falhaRede("Transporte HTTP Freelancer.com.br interrompido", e);
        } catch (Exception e) {
            throw falhaRede("Falha no transporte HTTP Freelancer.com.br", e);
        }
    }

    /**
     * UA que acompanha o cf_clearance armazenado: o clearance fica atrelado a
     * IP+User-Agent, entao usar aqui o mesmo UA do Chrome que o gerou e o que
     * faz o cookie valer na rota /projetos. Sem arquivo exportado, cai para o
     * default configurado.
     */
    private String userAgentEfetivo() {
        try {
            Path arquivo = Path.of(config.getCookiesArquivo() + ".ua");
            if (Files.exists(arquivo)) {
                String ua = Files.readString(arquivo).trim();
                if (!ua.isBlank()) {
                    return ua;
                }
            }
        } catch (Exception e) {
            // Sem UA exportado: segue com o default.
        }
        return config.getUserAgent();
    }

    private MarketplaceAdapterException falhaRede(String mensagem, Throwable causa) {
        return new MarketplaceAdapterException(
            PlataformaFreela.FREELANCER_BR, MarketplaceAdapterErrorType.FALHA_REDE, mensagem, causa);
    }
}
