package com.freelasatende.util;

import com.freelasatende.model.dto.ConexoesDisponiveis;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers para interoperar com as APIs internas do 99Freelas.
 */
public final class FreelasApiUtils {

    private static final Pattern PERSON_ID_PATTERN =
        Pattern.compile("(?:ID_PESSOA|idPessoa)\\s*[:=]\\s*\"?(\\d{3,})\"?");

    private static final Pattern RENOVAVEIS_PATTERN =
        Pattern.compile("(\\d+)\\s+conex(?:oes|ões)?\\s+renovaveis");

    private static final Pattern NAO_EXPIRAVEIS_PATTERN =
        Pattern.compile("(\\d+)\\s+conex(?:oes|ões)?\\s+nao\\s+expir(?:am|aveis)");

    private static final Pattern TOTAL_PATTERN =
        Pattern.compile("(\\d+)\\s+conex(?:oes|ões)?\\s+(?:disponiveis|disponíveis|adicionadas)");

    private FreelasApiUtils() {
    }

    public static String encodeQueryData(ObjectMapper objectMapper, Object payload) throws Exception {
        String json = objectMapper.writeValueAsString(payload);
        return URLEncoder.encode(json, StandardCharsets.UTF_8);
    }

    public static String encodeDoubleFormData(ObjectMapper objectMapper, Object payload) throws Exception {
        String json = objectMapper.writeValueAsString(payload);
        String encodedJson = URLEncoder.encode(json, StandardCharsets.UTF_8);
        return "data=" + URLEncoder.encode(encodedJson, StandardCharsets.UTF_8);
    }

    public static JsonNode decodeUrlEncodedJson(ObjectMapper objectMapper, String body) throws Exception {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        String decoded = URLDecoder.decode(body, StandardCharsets.UTF_8);
        if (decoded.trim().startsWith("<html") || decoded.trim().startsWith("<!DOCTYPE")) {
            throw new IllegalStateException("Bloqueio detectado (Cloudflare 403 Forbidden). Necessário renovar cookies da sessão via JavaDriverless.");
        }
        return objectMapper.readTree(decoded);
    }

    public static Long extractLoggedPersonId(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        Matcher matcher = PERSON_ID_PATTERN.matcher(html);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        return null;
    }

    public static boolean isOwnConversationMessage(
        Long messagePersonId,
        Long loggedPersonId,
        Long conversationFreelancerId,
        Long conversationClientId
    ) {
        if (messagePersonId == null) {
            return false;
        }
        if (conversationFreelancerId != null && conversationFreelancerId.equals(messagePersonId)) {
            return true;
        }
        if (conversationClientId != null && conversationClientId.equals(messagePersonId)) {
            return false;
        }
        return loggedPersonId != null && loggedPersonId.equals(messagePersonId);
    }

    public static ConexoesDisponiveis extractConnectionStock(String html) {
        if (html == null || html.isBlank()) {
            return ConexoesDisponiveis.builder().build();
        }

        String text = Jsoup.parse(html).text();
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase();

        Integer renovaveis = extractInt(RENOVAVEIS_PATTERN, normalized);
        Integer naoExpiraveis = extractInt(NAO_EXPIRAVEIS_PATTERN, normalized);
        Integer total = extractInt(TOTAL_PATTERN, normalized);

        if (total == null && (renovaveis != null || naoExpiraveis != null)) {
            total = safe(renovaveis) + safe(naoExpiraveis);
        }

        return ConexoesDisponiveis.builder()
            .total(total)
            .renovaveis(renovaveis)
            .naoExpiraveis(naoExpiraveis)
            .build();
    }

    private static Integer extractInt(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private static int safe(Integer value) {
        return value != null ? value : 0;
    }
}
