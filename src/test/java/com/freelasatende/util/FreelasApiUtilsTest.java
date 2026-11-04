package com.freelasatende.util;

import com.freelasatende.model.dto.ConexoesDisponiveis;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreelasApiUtilsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deveDecodificarJsonUrlEncodedDasApisInternas() throws Exception {
        JsonNode root = FreelasApiUtils.decodeUrlEncodedJson(
            objectMapper,
            "%7B%22status%22%3A%7B%22id%22%3A1%7D%2C%22result%22%3A%7B%22texto%22%3A%22ok%22%7D%7D"
        );

        assertEquals(1, root.path("status").path("id").asInt());
        assertEquals("ok", root.path("result").path("texto").asText());
    }

    @Test
    void deveGerarBodyFormComDuploEncodingParaMensagemConversa() throws Exception {
        String body = FreelasApiUtils.encodeDoubleFormData(
            objectMapper,
            new LinkedHashMap<>(Map.of("idConversa", 12345L, "texto", "ola mundo"))
        );

        assertEquals("data=", body.substring(0, 5));
        JsonNode payload = objectMapper.readTree(java.net.URLDecoder.decode(
            java.net.URLDecoder.decode(body.substring(5), java.nio.charset.StandardCharsets.UTF_8),
            java.nio.charset.StandardCharsets.UTF_8
        ));
        assertEquals(12345L, payload.path("idConversa").asLong());
        assertEquals("ola mundo", payload.path("texto").asText());
    }

    @Test
    void deveExtrairPessoaLogadaDoHtml() {
        Long id = FreelasApiUtils.extractLoggedPersonId(
            "<script>var cfg={ID_PESSOA:269409};</script>"
        );

        assertEquals(269409L, id);
    }

    @Test
    void deveIdentificarMensagemPropriaPeloFreelancerDaConversa() {
        assertTrue(FreelasApiUtils.isOwnConversationMessage(
            269409L,
            999999L,
            269409L,
            1613360L
        ));
    }

    @Test
    void deveIdentificarMensagemDoClientePeloClienteDaConversa() {
        assertFalse(FreelasApiUtils.isOwnConversationMessage(
            1613360L,
            1613360L,
            269409L,
            1613360L
        ));
    }

    @Test
    void deveExtrairEstoqueDeConexoesDoHtml() {
        String html = """
            <div>
              <strong>300 conexoes renovaveis</strong>
              <strong>558 conexoes nao expiram</strong>
              <p>858 conexoes disponiveis</p>
            </div>
            """;

        ConexoesDisponiveis estoque = FreelasApiUtils.extractConnectionStock(html);

        assertNotNull(estoque);
        assertEquals(858, estoque.getTotal());
        assertEquals(300, estoque.getRenovaveis());
        assertEquals(558, estoque.getNaoExpiraveis());
    }
}
