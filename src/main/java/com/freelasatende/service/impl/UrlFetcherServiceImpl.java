package com.freelasatende.service.impl;

import com.freelasatende.config.FreelasConfig;
import com.freelasatende.service.interfaceServico.UrlFetcherService;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementação do serviço de busca de URLs.
 * Extrai URLs de descrições e busca conteúdo relevante para dar mais contexto à IA.
 */
@Slf4j
@Service
public class UrlFetcherServiceImpl implements UrlFetcherService {

    private static final Pattern URL_PATTERN = Pattern.compile(
        "https?://[^\\s<>\\\"\\']+",
        Pattern.CASE_INSENSITIVE
    );

    private static final Set<String> DOMINIOS_PERMITIDOS = Set.of(
        "99freelas.com.br",
        "www.99freelas.com.br",
        "freelas.com.br",
        "www.freelas.com.br"
    );

    private static final int MAX_URLS_POR_PROJETO = 3;
    private static final int TIMEOUT_SECONDS = 10;
    private static final int MAX_CHARS_POR_URL = 500;

    private final FreelasConfig freelasConfig;
    private final HttpClient httpClient;

    public UrlFetcherServiceImpl(FreelasConfig freelasConfig) {
        this.freelasConfig = freelasConfig;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build();
    }

    @Override
    public List<String> extrairUrls(String texto) {
        if (texto == null || texto.isBlank()) {
            return List.of();
        }

        Set<String> urlsUnicas = new HashSet<>();
        Matcher matcher = URL_PATTERN.matcher(texto);

        while (matcher.find()) {
            String url = matcher.group();
            // Limpa a URL de pontuação final
            url = url.replaceAll("[.,;:)\\]]+$", "");

            if (ehDominioPermitido(url)) {
                urlsUnicas.add(url);
            }
        }

        return new ArrayList<>(urlsUnicas);
    }

    @Override
    public String buscarContextoDeUrls(String descricao) {
        if (descricao == null || descricao.isBlank()) {
            return "";
        }

        List<String> urls = extrairUrls(descricao);

        if (urls.isEmpty()) {
            return "";
        }

        StringBuilder contexto = new StringBuilder();
        AtomicInteger count = new AtomicInteger(0);

        for (String url : urls) {
            if (count.get() >= MAX_URLS_POR_PROJETO) {
                break;
            }

            try {
                String conteudo = buscarConteudoUrl(url);
                if (conteudo != null && !conteudo.isBlank()) {
                    contexto.append(String.format("\n\n--- Referência: %s ---\n%s", url, conteudo));
                    count.incrementAndGet();
                }
            } catch (Exception e) {
                log.debug("Erro ao buscar URL {}: {}", url, e.getMessage());
            }
        }

        return contexto.toString();
    }

    private boolean ehDominioPermitido(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) return false;

            // Verifica se é um domínio permitido ou subdomínio
            for (String dominio : DOMINIOS_PERMITIDOS) {
                if (host.equals(dominio) || host.endsWith("." + dominio)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private String buscarConteudoUrl(String url) {
        try {
            // Tenta com Jsoup primeiro (melhor para HTML)
            try {
                Document doc = Jsoup.connect(url)
                    .timeout(TIMEOUT_SECONDS * 1000)
                    .userAgent(freelasConfig.getUserAgent())
                    .get();

                // Extrai texto relevante
                StringBuilder sb = new StringBuilder();

                // Título da página
                String title = doc.title();
                if (title != null && !title.isBlank()) {
                    sb.append("Título: ").append(title);
                }

                // Texto principal (limita tamanho)
                String bodyText = doc.body().text();
                if (bodyText != null && !bodyText.isBlank()) {
                    String texto = truncate(bodyText, MAX_CHARS_POR_URL);
                    sb.append("\nConteúdo: ").append(texto);
                }

                String resultado = sb.toString().trim();
                if (!resultado.isBlank()) {
                    log.debug("Conteúdo buscado de {}: {} chars", url, resultado.length());
                    return resultado;
                }
            } catch (Exception e) {
                log.debug("Jsoup falhou para {}, tentando HttpClient: {}", url, e.getMessage());
            }

            // Fallback: HttpClient para páginas simples ou JSON
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", freelasConfig.getUserAgent())
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String body = response.body();
                // Se parecer HTML, tenta extrair só o texto
                if (body.contains("<")) {
                    Document doc = Jsoup.parse(body);
                    body = doc.body().text();
                }
                return truncate(body, MAX_CHARS_POR_URL);
            }

        } catch (Exception e) {
            log.debug("Erro ao buscar {}: {}", url, e.getMessage());
        }

        return null;
    }

    private String truncate(String text, int maxChars) {
        if (text == null) return "";
        text = text.trim();
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }
}
