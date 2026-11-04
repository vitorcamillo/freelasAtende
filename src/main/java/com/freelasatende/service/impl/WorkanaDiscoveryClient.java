package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.WorkanaConfig;
import com.freelasatende.model.enums.PlataformaFreela;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static com.freelasatende.service.impl.WorkanaClientSupport.carregarCookies;
import static com.freelasatende.service.impl.WorkanaClientSupport.construirCookieHeader;

/** Transporte e snapshot de uso único da listagem de projetos Workana. */
final class WorkanaDiscoveryClient {

    private static final long SNAPSHOT_TTL_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final WorkanaConfig config;
    private final ObjectMapper objectMapper;
    private final Supplier<HttpClient> httpClientSupplier;
    private final Object snapshotLock = new Object();
    private final byte[] fingerprintKey = criarChaveFingerprint();
    private volatile LongSupplier monotonicClock = System::nanoTime;
    private DiscoverySnapshot snapshot;

    WorkanaDiscoveryClient(
        WorkanaConfig config,
        ObjectMapper objectMapper,
        Supplier<HttpClient> httpClientSupplier
    ) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClientSupplier = httpClientSupplier;
    }

    JsonNode buscarProjetosJson() {
        return buscarProjetosJsonFresco(false);
    }

    JsonNode verificarLoginEPrepararDiscovery() {
        return buscarProjetosJsonFresco(true);
    }

    JsonNode buscarProjetosJsonParaDiscovery() {
        try {
            Map<String, String> cookies = carregarCookies(config, objectMapper);
            DiscoveryContext contexto = contexto(cookies);
            synchronized (snapshotLock) {
                DiscoverySnapshot anterior = snapshot;
                snapshot = null;
                if (anterior != null && anterior.thread() == Thread.currentThread()
                    && anterior.valido(contexto.fingerprint(), monotonicClock.getAsLong())) {
                    return anterior.projetos();
                }
            }
            return buscarProjetosJsonFresco(false, cookies, contexto);
        } catch (MarketplaceAdapterException e) {
            limparSnapshot();
            throw e;
        } catch (Exception e) {
            limparSnapshot();
            throw falhaDiscovery(e);
        }
    }

    void configurarRelogio(LongSupplier relogio) {
        monotonicClock = relogio;
    }

    private JsonNode buscarProjetosJsonFresco(boolean guardarSnapshot) {
        limparSnapshot();
        try {
            Map<String, String> cookies = carregarCookies(config, objectMapper);
            return buscarProjetosJsonFresco(guardarSnapshot, cookies, contexto(cookies));
        } catch (MarketplaceAdapterException e) {
            limparSnapshot();
            throw e;
        } catch (Exception e) {
            limparSnapshot();
            throw falhaDiscovery(e);
        }
    }

    private JsonNode buscarProjetosJsonFresco(
        boolean guardarSnapshot,
        Map<String, String> cookies,
        DiscoveryContext contexto
    ) {
        String url = contexto.url() + "/jobs?" + contexto.query();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", contexto.userAgent())
                .header("Accept", "application/json,text/plain,*/*")
                .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                .header("X-Requested-With", "XMLHttpRequest")
                .timeout(Duration.ofSeconds(30))
                .GET();
            if (!cookies.isEmpty()) {
                builder.header("Cookie", construirCookieHeader(cookies));
            }

            HttpResponse<String> response = httpClientSupplier.get().send(
                builder.build(), HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() >= 400) {
                throw new MarketplaceAdapterException(
                    PlataformaFreela.WORKANA,
                    MarketplaceAdapterErrorType.FALHA_REDE,
                    "Workana discovery retornou HTTP " + response.statusCode()
                );
            }
            JsonNode projetos = objectMapper.readTree(response.body());
            if (guardarSnapshot) {
                synchronized (snapshotLock) {
                    snapshot = new DiscoverySnapshot(
                        contexto.fingerprint(), monotonicClock.getAsLong(), Thread.currentThread(), projetos
                    );
                }
            }
            return projetos;
        } catch (MarketplaceAdapterException e) {
            limparSnapshot();
            throw e;
        } catch (Exception e) {
            limparSnapshot();
            throw falhaDiscovery(e);
        }
    }

    private DiscoveryContext contexto(Map<String, String> cookies) {
        String url = config.getUrlBase();
        String query = config.getDiscoveryQuery();
        String userAgent = config.getUserAgent();
        List<String> nomesCookies = new ArrayList<>(cookies.keySet());
        Collections.sort(nomesCookies);
        String fingerprint = fingerprint(url, query, userAgent, nomesCookies, cookies);
        return new DiscoveryContext(url, query, userAgent, fingerprint);
    }

    private String fingerprint(
        String url,
        String query,
        String userAgent,
        List<String> nomesCookies,
        Map<String, String> cookies
    ) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(fingerprintKey, "HmacSHA256"));
            atualizarFingerprint(mac, url);
            atualizarFingerprint(mac, query);
            atualizarFingerprint(mac, userAgent);
            for (String nome : nomesCookies) {
                atualizarFingerprint(mac, nome);
                atualizarFingerprint(mac, cookies.get(nome));
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte valor : mac.doFinal()) {
                hex.append(Character.forDigit((valor >>> 4) & 0xf, 16));
                hex.append(Character.forDigit(valor & 0xf, 16));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao criar fingerprint efemero de discovery", e);
        }
    }

    private static void atualizarFingerprint(Mac mac, String valor) {
        byte[] bytes = valor.getBytes(StandardCharsets.UTF_8);
        mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        mac.update(bytes);
    }

    private static byte[] criarChaveFingerprint() {
        byte[] chave = new byte[32];
        new SecureRandom().nextBytes(chave);
        return chave;
    }

    private MarketplaceAdapterException falhaDiscovery(Exception causa) {
        return new MarketplaceAdapterException(
            PlataformaFreela.WORKANA,
            MarketplaceAdapterErrorType.FALHA_REDE,
            "Falha ao consultar discovery Workana",
            causa
        );
    }

    private void limparSnapshot() {
        synchronized (snapshotLock) {
            snapshot = null;
        }
    }

    private record DiscoveryContext(String url, String query, String userAgent, String fingerprint) {
    }

    private record DiscoverySnapshot(String fingerprint, long criadoEmNanos, Thread thread, JsonNode projetos) {
        private boolean valido(String fingerprintAtual, long agoraNanos) {
            long idadeNanos = agoraNanos - criadoEmNanos;
            return fingerprint.equals(fingerprintAtual)
                && idadeNanos >= 0
                && idadeNanos < SNAPSHOT_TTL_NANOS;
        }
    }
}
