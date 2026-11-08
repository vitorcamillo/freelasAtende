package com.freelasatende.service.impl;

import com.freelasatende.config.FreelasConfig;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.util.RetryUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implementação do serviço de autenticação no 99Freelas.
 * Gerencia cookies de sessão e re-login automático.
 * Suporta cookies vindos do JavaDriverless (CAPTCHA solver).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutenticacaoServiceImpl implements AutenticacaoService {

    private final FreelasConfig freelasConfig;
    private final ObjectMapper objectMapper;
    private final GerenciadorConexoesService gerenciadorConexoes;

    @Value("${auth.cookies-arquivo:data/cookies.json}")
    private String arquivoCookies;

    /** Cookies de sessão ativos (thread-safe) */
    private final AtomicReference<Map<String, String>> cookiesAtual =
        new AtomicReference<>(new HashMap<>());
    private final AtomicLong versaoCookies = new AtomicLong(0);

    private HttpClient httpClient;

    private synchronized HttpClient getHttpClient() {
        if (httpClient == null) {
            CookieManager cookieManager = new CookieManager();
            cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);

            httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        }
        return httpClient;
    }

    @PostConstruct
    public void init() {
        if (!carregarCookies()) {
            log.info("Cookies não encontrados, será necessário fazer login");
        } else {
            log.info("Cookies carregados: {} cookies", cookiesAtual.get().size());
        }
    }

    @Override
    public boolean verificarSessaoValida() {
        if (cookiesAtual.get().isEmpty()) {
            return false;
        }

        try {
            String html = fazerGet(urlBase() + "/dashboard");
            boolean valida = html != null
                && html.length() > 1000
                && !html.contains("frmEfetuarLogin")
                && !html.contains("Faça seu login")
                && !html.contains("Entrar na conta");

            log.debug("Sessão válida: {}", valida);
            return valida;

        } catch (Exception e) {
            log.warn("Erro ao verificar sessão: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void renovarSessaoSeNecessario() {
        if (!verificarSessaoValida()) {
            log.info("Sessão inválida, tentando re-login");
            fazerLogin(freelasConfig.getEmail(), freelasConfig.getSenha());
        }
    }

    @Override
    public void fazerLogin(String email, String senha) {
        try {
            log.info("Tentando login com email: {}", email);

            String dadosForm = "email=" + URLEncoder.encode(email, java.nio.charset.StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(senha, java.nio.charset.StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlBase() + "/login"))
                .header("User-Agent", freelasConfig.getUserAgent())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", urlBase() + "/")
                .header("Origin", urlBase())
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(dadosForm))
                .build();

            HttpResponse<String> response = getHttpClient().send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );

            Map<String, String> cookiesResposta = extrairTodosCookies(response);

            if (!cookiesResposta.isEmpty()) {
                // Merge com cookies existentes (para não perder cookies do Cloudflare)
                Map<String, String> merged = new HashMap<>(cookiesAtual.get());
                merged.putAll(cookiesResposta);
                atualizarCookiesAtuais(merged);
                salvarCookies();
                gerenciadorConexoes.marcarSessaoRenovada();
                log.info("Login realizado com sucesso — {} cookies ativos",
                    merged.size());
            } else {
                log.error("Login falhou - nenhum cookie recebido. " +
                    "Cloudflare pode estar bloqueando. Use JavaDriverless para login inicial.");
            }

        } catch (Exception e) {
            log.error("Erro ao fazer login: {}", e.getMessage());
        }
    }

    /**
     * Carrega cookies exportados pelo JavaDriverless.
     * Chamado quando o CAPTCHA solver exporta cookies para data/cookies.json
     */
    @Override
    public void carregarCookiesJson(String jsonCookies) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> cookies = objectMapper.readValue(jsonCookies,
                objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, String.class));

            if (!cookies.isEmpty()) {
                atualizarCookiesAtuais(cookies);
                salvarCookies();
                log.info("Cookies externos carregados: {} cookies", cookies.size());
            }
        } catch (Exception e) {
            log.error("Erro ao carregar cookies externos: {}", e.getMessage());
        }
    }

    @Override
    public void salvarCookies() {
        try {
            Path arquivo = Paths.get(arquivoCookies);
            Path dir = arquivo.getParent();
            if (dir != null && !Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            if (Files.exists(arquivo) && Files.size(arquivo) > 0) {
                Path backup = Paths.get(arquivo.toString() + ".bak-" + System.currentTimeMillis());
                Files.copy(arquivo, backup, StandardCopyOption.REPLACE_EXISTING);
                log.info("Backup dos cookies salvo em {}", backup);
            }

            objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(arquivo.toFile(), cookiesAtual.get());
            log.info("Cookies salvos em {} ({} cookies)", arquivoCookies, cookiesAtual.get().size());

        } catch (IOException e) {
            log.error("Erro ao salvar cookies: {}", e.getMessage());
        }
    }

    @Override
    public boolean carregarCookies() {
        try {
            Path arquivo = Paths.get(arquivoCookies);
            if (Files.exists(arquivo)) {
                @SuppressWarnings("unchecked")
                Map<String, String> cookies = objectMapper.readValue(
                    arquivo.toFile(),
                    objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, String.class)
                );

                if (!cookies.isEmpty()) {
                    atualizarCookiesAtuais(cookies);
                    log.info("Cookies carregados com sucesso: {} cookies", cookies.size());
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("Erro ao carregar cookies: {}", e.getMessage());
        }
        return false;
    }

    @Override
    public Map<String, String> obterCookies() {
        return new HashMap<>(cookiesAtual.get());
    }

    @Override
    public long versaoCookies() {
        return versaoCookies.get();
    }

    private String fazerGet(String url) throws Exception {
        Map<String, String> cookies = cookiesAtual.get();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", freelasConfig.getUserAgent())
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            .header("Cookie", construirCookieHeader(cookies))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();

        HttpResponse<String> response = getHttpClient().send(
            request,
            HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("GET " + URI.create(url).getPath()
                + " retornou HTTP " + response.statusCode());
        }

        // Atualizar cookies se receber novos
        Map<String, String> novosCookies = extrairTodosCookies(response);
        if (!novosCookies.isEmpty()) {
            Map<String, String> merged = new HashMap<>(cookiesAtual.get());
            merged.putAll(novosCookies);
            atualizarCookiesAtuais(merged);
        }

        return response.body();
    }

    private void atualizarCookiesAtuais(Map<String, String> cookies) {
        cookiesAtual.set(new HashMap<>(cookies));
        versaoCookies.incrementAndGet();
    }

    private String construirCookieHeader(Map<String, String> cookies) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    private String urlBase() {
        return freelasConfig.getUrlBase();
    }

    private Map<String, String> extrairTodosCookies(HttpResponse<?> response) {
        Map<String, String> cookies = new HashMap<>();

        List<String> setCookieHeaders = response.headers().allValues("set-cookie");

        for (String cookieStr : setCookieHeaders) {
            // Cada Set-Cookie header tem formato: nome=valor; path=...; expires=...
            String[] partes = cookieStr.split(";");
            if (partes.length > 0) {
                String[] nomeValor = partes[0].trim().split("=", 2);
                if (nomeValor.length == 2) {
                    String nome = nomeValor[0].trim();
                    String valor = nomeValor[1].trim();

                    // Aceitar todos os cookies relevantes (não apenas 4 específicos)
                    if (!nome.isEmpty() && !valor.isEmpty()) {
                        cookies.put(nome, valor);
                        log.debug("Cookie extraído: {}=[presente, {} chars]", nome, valor.length());
                    }
                }
            }
        }

        return cookies;
    }
}
