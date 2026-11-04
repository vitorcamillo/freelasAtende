package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.UpworkConfig;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.model.enums.PlataformaFreela;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import com.freelasatende.model.enums.StatusProjeto;

@Component
@RequiredArgsConstructor
class UpworkProposalClient {

    private static final Pattern CIPHERTEXT = Pattern.compile("(~\\d{10,})");
    private static final Pattern TOKEN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");
    private static final String MUTATION = "mutation createProposal($input: CreateProposalInput!) {"
        + " createProposal(input: $input) { success newProposalId } }";
    private static final String PRECHECK = "query preApplyCheck($ciphertext: String!) {"
        + " preApplyCheck(ciphertext: $ciphertext) { passed error } }";
    private static final String CONTEXTO = "query proposalContext($ciphertext: String!) {"
        + " proposalsOpenings(ciphertext: $ciphertext) { opening { id job { info { type } budget { amount } } }"
        + " occupation { id } questions { questions { id question } } } }";
    private static final String FREELANCER = "query proposalFreelancer($jobId: ID!) {"
        + " jobProposalsTeamsCombined(jobId: $jobId, owner: \"include\") { freelancer { person { id legacyId } } } }";
    private static final String DETALHES = "query details($ciphertext: String!) {"
        + " fetchJobDetailsAndContext(ciphertext: $ciphertext) {"
        + " engagementDurations { id type label weeks } opening { description openingId"
        + " budget { amount currencyCode } extendedBudgetInfo { hourlyBudgetMin hourlyBudgetMax hourlyBudgetType }"
        + " engagementDuration { id type label weeks } info { title type ciphertext } category { name } } } }";

    private final UpworkConfig config;
    private final ObjectMapper objectMapper;
    private final UpworkClient upworkClient;
    private HttpClient httpClient;

    void enviar(Projeto projeto, Proposta proposta) {
        validar(projeto, proposta);
        String ciphertext = ciphertext(projeto.getUrlProjeto());
        Map<String, String> cookies = upworkClient.carregarCookies();
        String orgId = cookies.getOrDefault("current_organization_uid", "");
        if (orgId.isBlank()) throw erro("Organizacao Upwork ausente na sessao");
        String token = token(cookies);

        JsonNode precheck = gql("preApplyCheckResults", PRECHECK, Map.of("ciphertext", ciphertext), cookies, token, orgId);
        JsonNode check = precheck.path("data").path("preApplyCheck");
        if (!check.path("passed").asBoolean()) {
            throw new MarketplaceAdapterException(PlataformaFreela.UPWORK,
                MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL,
                "Upwork bloqueou candidatura: " + check.path("error").asText("requisitos nao atendidos"));
        }

        JsonNode contexto = gql("loadOpeningByCiphertext", CONTEXTO, Map.of("ciphertext", ciphertext),
            cookies, token, orgId).path("data").path("proposalsOpenings");
        JsonNode perguntas = contexto.path("questions").path("questions");
        if (perguntas.isArray() && !perguntas.isEmpty()) {
            throw new MarketplaceAdapterException(PlataformaFreela.UPWORK,
                MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL,
                "Projeto Upwork exige respostas adicionais; candidatura mantida para aprovacao manual");
        }
        String openingId = contexto.path("opening").path("id").asText("");
        JsonNode detalhes = gql("loadJobDetailsByCiphertext", DETALHES, Map.of("ciphertext", ciphertext),
            cookies, token, orgId).path("data").path("fetchJobDetailsAndContext");
        JsonNode pessoa = gql("teamsCombined.retrieve", FREELANCER, Map.of("jobId", openingId),
            cookies, token, orgId).path("data").path("jobProposalsTeamsCombined").path("freelancer").path("person");
        if (openingId.isBlank() || pessoa.path("id").asText("").isBlank()) throw erro("Contexto de candidatura incompleto");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("jobReference", openingId);
        input.put("chargedAmount", proposta.getValorProposto());
        input.put("coverLetter", proposta.getMensagem().trim());
        input.put("attachments", List.of());
        input.put("questions", List.of());
        input.put("milestones", null);
        input.put("occupationId", contexto.path("occupation").path("id").asText(null));
        input.put("selectedContractor", Map.of(
            "id", pessoa.path("id").asText(),
            "oDeskUserID", pessoa.path("legacyId").asText()
        ));
        input.put("sri", Map.of("percent", 0, "frequency", 0));
        input.put("umaTouched", false);
        if ("fixed".equalsIgnoreCase(contexto.path("opening").path("job").path("info").path("type").asText())) {
            String duracao = detalhes.path("opening").path("engagementDuration").path("type").asText("");
            if (duracao.isBlank() && detalhes.path("engagementDurations").isArray()
                && !detalhes.path("engagementDurations").isEmpty()) {
                duracao = detalhes.path("engagementDurations").path(0).path("type").asText("");
            }
            if (duracao.isBlank()) {
                throw new MarketplaceAdapterException(PlataformaFreela.UPWORK,
                    MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL,
                    "Duracao obrigatoria da proposta Upwork exige revisao manual");
            }
            input.put("estimatedDuration", duracao);
        }
        JsonNode response = gql("submitProposal", MUTATION, Map.of("input", input), cookies, token, orgId);
        if (!response.path("errors").isMissingNode() && response.path("errors").isArray()
            && !response.path("errors").isEmpty()) {
            throw erro("Upwork recusou proposta: " + response.path("errors").path(0).path("message").asText("erro GraphQL"));
        }
        if (response.path("data").path("createProposal").path("newProposalId").asText("").isBlank()) {
            throw erro("Upwork nao confirmou a proposta");
        }
    }

    Projeto buscarDetalhes(Long idExterno, String slug) {
        String ciphertext = ciphertext(slug);
        Map<String, String> cookies = upworkClient.carregarCookies();
        String orgId = cookies.getOrDefault("current_organization_uid", "");
        JsonNode opening = gql("loadJobDetailsByCiphertext", DETALHES, Map.of("ciphertext", ciphertext),
            cookies, token(cookies), orgId).path("data").path("fetchJobDetailsAndContext").path("opening");
        if (opening.isMissingNode() || opening.isNull()) throw erro("Detalhes Upwork ausentes");
        BigDecimal orcamento = decimal(opening.path("budget").path("amount"));
        if (orcamento == null || orcamento.compareTo(BigDecimal.ZERO) <= 0) {
            orcamento = decimal(opening.path("extendedBudgetInfo").path("hourlyBudgetMax"));
        }
        return Projeto.builder().id(PlataformaFreela.UPWORK.idInterno(idExterno)).idExterno(idExterno)
            .plataforma(PlataformaFreela.UPWORK).titulo(opening.path("info").path("title").asText())
            .descricao(opening.path("description").asText()).categoria(opening.path("category").path("name").asText())
            .orcamento(orcamento)
            .urlProjeto(config.getUrlBase() + "/jobs/" + ciphertext + "/")
            .status(StatusProjeto.NOVO).dataCriacao(LocalDateTime.now()).foiProcessado(false).build();
    }

    private JsonNode gql(String alias, String query, Map<String, Object> variables,
                         Map<String, String> cookies, String token, String orgId) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("query", query, "variables", variables));
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getUrlBase() + "/api/graphql/v1?alias=" + alias))
                .header("User-Agent", config.getUserAgent()).header("Accept", "application/json")
                .header("Content-Type", "application/json").header("Authorization", "bearer " + token)
                .header("vnd-eo-orguid", orgId).header("x-upwork-api-tenantid", orgId)
                .header("Cookie", cookieHeader(cookies)).timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) throw erro("Upwork " + alias + " retornou HTTP " + response.statusCode());
            return objectMapper.readTree(response.body());
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(PlataformaFreela.UPWORK,
                MarketplaceAdapterErrorType.FALHA_REDE, "Falha na candidatura Upwork", e);
        }
    }

    private String token(Map<String, String> cookies) {
        try {
            String url = config.getAuthUrlBase() + "/api/v3/oauth2/token/subordinate/v3/" + config.getProposalsClientId();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", config.getUserAgent())
                .header("Accept", "*/*").header("Referer", config.getUrlBase() + "/")
                .header("Cookie", cookieHeader(cookies)).timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
            Matcher matcher = TOKEN.matcher(response.body());
            if (response.statusCode() >= 400 || !matcher.find()) throw erro("Token temporario de propostas indisponivel");
            return matcher.group(1);
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(PlataformaFreela.UPWORK,
                MarketplaceAdapterErrorType.FALHA_REDE, "Falha ao autenticar candidatura Upwork", e);
        }
    }

    private String ciphertext(String url) {
        Matcher matcher = CIPHERTEXT.matcher(url == null ? "" : url);
        if (!matcher.find()) throw erro("Identificador cifrado do projeto Upwork ausente");
        return matcher.group(1);
    }

    private BigDecimal decimal(JsonNode valor) {
        try {
            if (valor == null || valor.isMissingNode() || valor.isNull() || valor.asText("").isBlank()) return null;
            return valor.isNumber() ? valor.decimalValue() : new BigDecimal(valor.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void validar(Projeto projeto, Proposta proposta) {
        if (projeto == null || proposta == null || proposta.getValorProposto() == null
            || proposta.getMensagem() == null || proposta.getMensagem().isBlank()) {
            throw erro("Projeto, valor e mensagem sao obrigatorios na proposta Upwork");
        }
    }

    private String cookieHeader(Map<String, String> cookies) {
        return cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
            .reduce((a, b) -> a + "; " + b).orElse("");
    }

    private MarketplaceAdapterException erro(String mensagem) {
        return new MarketplaceAdapterException(PlataformaFreela.UPWORK,
            MarketplaceAdapterErrorType.FALHA_ENVIO, mensagem);
    }

    private synchronized HttpClient client() {
        if (httpClient == null) httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        return httpClient;
    }
}
