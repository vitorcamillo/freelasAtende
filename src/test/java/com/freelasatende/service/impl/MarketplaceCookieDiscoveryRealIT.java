package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.FreelancerConfig;
import com.freelasatende.config.UpworkConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MarketplaceCookieDiscoveryRealIT {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void descobreProjetosUpworkComSessaoReal() throws Exception {
        String encoded = System.getenv("UPWORK_COOKIES_JSON_B64");
        assumeTrue(encoded != null && !encoded.isBlank(), "cookie Upwork nao fornecido");
        Path cookies = materializar("upwork.json", encoded);

        UpworkConfig config = new UpworkConfig();
        config.setCookiesArquivo(cookies.toString());
        UpworkMarketplaceAdapter adapter = new UpworkMarketplaceAdapter(
            config,
            new UpworkClient(config, objectMapper),
            null
        );

        assertTrue(adapter.buscarProjetosNovos().size() >= 20,
            "feed autenticado do Upwork deveria expor pelo menos 20 vagas");
    }

    @Test
    void descobreProjetosFreelancerComSessaoReal() throws Exception {
        String encoded = System.getenv("FREELANCER_COOKIES_JSON_B64");
        assumeTrue(encoded != null && !encoded.isBlank(), "cookie Freelancer nao fornecido");
        Path cookies = materializar("freelancer.json", encoded);

        FreelancerConfig config = new FreelancerConfig();
        config.setCookiesArquivo(cookies.toString());
        FreelancerMarketplaceAdapter adapter = new FreelancerMarketplaceAdapter(
            config,
            new FreelancerClient(config, objectMapper)
        );

        assertFalse(adapter.buscarProjetosNovos().isEmpty());
    }

    @Test
    void consultaInboxUpworkComOAuthTemporarioReal() throws Exception {
        String encoded = System.getenv("UPWORK_COOKIES_JSON_B64");
        assumeTrue(encoded != null && !encoded.isBlank(), "cookie Upwork nao fornecido");
        Path cookies = materializar("upwork-inbox.json", encoded);
        UpworkConfig config = new UpworkConfig();
        config.setCookiesArquivo(cookies.toString());
        UpworkClient client = new UpworkClient(config, objectMapper);

        assertNotNull(client.listarConversasInbox());
    }

    @Test
    void consultaInboxFreelancerNaApiMessagesReal() throws Exception {
        String encoded = System.getenv("FREELANCER_COOKIES_JSON_B64");
        assumeTrue(encoded != null && !encoded.isBlank(), "cookie Freelancer nao fornecido");
        Path cookies = materializar("freelancer-inbox.json", encoded);
        FreelancerConfig config = new FreelancerConfig();
        config.setCookiesArquivo(cookies.toString());
        FreelancerClient client = new FreelancerClient(config, objectMapper);

        assertNotNull(client.listarConversasInbox());
    }

    private Path materializar(String nome, String encoded) throws Exception {
        String normalizado = encoded.trim();
        if ((normalizado.startsWith("'") && normalizado.endsWith("'"))
            || (normalizado.startsWith("\"") && normalizado.endsWith("\""))) {
            normalizado = normalizado.substring(1, normalizado.length() - 1);
        }
        byte[] json = Base64.getDecoder().decode(normalizado);
        JsonNode root = objectMapper.readTree(json);
        Path arquivo = tempDir.resolve(nome);
        Files.writeString(arquivo, objectMapper.writeValueAsString(root), StandardCharsets.UTF_8);
        return arquivo;
    }
}
