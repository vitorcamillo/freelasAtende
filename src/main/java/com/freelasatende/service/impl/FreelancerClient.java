package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.FreelancerConfig;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.entity.Proposta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
class FreelancerClient {

    private final FreelancerConfig config;
    private final ObjectMapper objectMapper;

    private HttpClient httpClient;
    private FreelancerElegibilidadeConta elegibilidade;

    JsonNode buscarProjetosJson() {
        // job_details traz as skills (jobs) de cada projeto, usadas no filtro
        // de skills em comum antes de gastar IA com o projeto.
        String query = config.getDiscoveryQuery() == null ? "" : config.getDiscoveryQuery();
        if (!query.contains("job_details")) {
            query = query.isBlank() ? "job_details=true" : query + "&job_details=true";
        }
        String url = config.getUrlBase() + "/api/projects/0.1/projects/active/?"
            + query;
        return getJson(url, "Freelancer discovery");
    }

    JsonNode buscarDetalhesProjetoJson(long idExterno) {
        String url = config.getUrlBase() + "/api/projects/0.1/projects/" + idExterno
            + "/?full_description=true&job_details=true&qualification_details=true&upgrade_details=true";
        return getJson(url, "Freelancer detalhes projeto");
    }

    boolean aceitaOfertaDaConta(JsonNode projeto) {
        return motivoRecusa(projeto) == null;
    }

    String motivoRecusa(JsonNode projeto) {
        return elegibilidade().motivoRecusa(projeto);
    }

    boolean lancesBloqueados() {
        return elegibilidade().lancesBloqueados();
    }

    long lancesBloqueadosAte() {
        return elegibilidade().lancesBloqueadosAte();
    }

    private synchronized FreelancerElegibilidadeConta elegibilidade() {
        if (elegibilidade == null) {
            elegibilidade = new FreelancerElegibilidadeConta(config, () -> getJson(
                config.getUrlBase() + "/api/users/0.1/self/?jobs=true", "Freelancer perfil"));
        }
        return elegibilidade;
    }

    List<ConversaResumo> listarConversasInbox() {
        String url = MarketplaceHttpContract.freelancerThreadsUrl(config.getUrlBase());
        JsonNode root = getJson(url, "Freelancer inbox");
        return FreelancerInboxMapper.listarConversas(root, config);
    }

    List<MensagemConversa> listarMensagensConversa(long threadId) {
        String url = MarketplaceHttpContract.freelancerMessagesUrl(config.getUrlBase(), threadId);
        JsonNode root = getJson(url, "Freelancer mensagens");
        return FreelancerInboxMapper.listarMensagens(root, threadId);
    }

    boolean enviarPergunta(long projetoIdExterno, String mensagem) {
        return enviarPergunta(projetoIdExterno, mensagem, null);
    }

    /**
     * O "Estou interessado" do Freelancer.com e um bid real: several projetos rejeitam
     * amount 0 (BID_AMOUNT_INVALID exige o orcamento minimo). Quando o orcamento do
     * projeto esta disponivel, ele e usado como valor do bid.
     */
    boolean enviarPergunta(long projetoIdExterno, String mensagem, java.math.BigDecimal orcamentoMinimo) {
        String url = config.getUrlBase() + "/api/projects/0.1/bids/";
        try {
            long bidderId = config.getBidderId();
            Map<String, String> cookies = carregarCookies();
            // O discovery costuma trazer o teto do orcamento (ou nada); a API rejeita
            // (BID_AMOUNT_INVALID: "Bid cannot be less than X") lances abaixo do minimo
            // real do projeto. Consulta o minimo autoritativo antes de dar o lance.
            java.math.BigDecimal amount = resolverValorBid(projetoIdExterno, orcamentoMinimo);
            // A API exige tipos numericos reais: string em bidder_id/amount vira
            // BAD_JSON ("needs integer, got '94462910'").
            Map<String, Object> bodyMap = new java.util.HashMap<>();
            bodyMap.put("project_id", projetoIdExterno);
            bodyMap.put("bidder_id", bidderId);
            bodyMap.put("description", mensagem);
            bodyMap.put("amount", amount);
            bodyMap.put("period", 1);
            // Alguns projetos exigem milestone entre 20 e 100 ("Please fill in the
            // milestone percentage"). 50 e um valor seguro aceito em ambos os casos.
            bodyMap.put("milestone_percentage", 50);
            String body = objectMapper.writeValueAsString(bodyMap);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", config.getUserAgent())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("freelancer-oauth-v1", oauthToken())
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body));
            if (!cookies.isEmpty()) {
                builder.header("Cookie", construirCookieHeader(cookies));
            }
            HttpResponse<String> response = client().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                elegibilidade().registrarRecusaDeLance(response.body());
                throw new MarketplaceAdapterException(
                    PlataformaFreela.FREELANCER,
                    FreelancerErrorClassifier.tipoErroMensagem(
                        response.statusCode(), response.body(), objectMapper
                    ),
                    "Freelancer envio retornou HTTP " + response.statusCode()
                        + ": " + MarketplaceHttpContract.resumirResposta(response.body())
                );
            }
            return true;
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(
                PlataformaFreela.FREELANCER,
                MarketplaceAdapterErrorType.FALHA_REDE,
                "Falha ao enviar pergunta Freelancer",
                e
            );
        }
    }

    void enviarProposta(long projetoIdExterno, Proposta proposta) {
        if (proposta == null || proposta.getValorProposto() == null || proposta.getMensagem() == null
            || proposta.getMensagem().isBlank()) {
            throw new MarketplaceAdapterException(PlataformaFreela.FREELANCER,
                MarketplaceAdapterErrorType.FALHA_ENVIO, "Valor e mensagem da proposta Freelancer sao obrigatorios");
        }
        validarElegibilidadeOferta(projetoIdExterno);
        String url = config.getUrlBase() + "/api/projects/0.1/bids/";
        try {
            Map<String, String> cookies = carregarCookies();
            String body = objectMapper.writeValueAsString(Map.of(
            "project_id", projetoIdExterno,
            "bidder_id", config.getBidderId(),
            "description", proposta.getMensagem(),
            "amount", proposta.getValorProposto(),
            "period", 7,
            "milestone_percentage", MarketplaceHttpContract.freelancerMilestonePercentage()
            ));
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                .header("User-Agent", config.getUserAgent()).header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("freelancer-oauth-v1", oauthToken())
                .header("Cookie", construirCookieHeader(cookies)).timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) elegibilidade().registrarRecusaDeLance(response.body());
            if (response.statusCode() >= 400) throw new MarketplaceAdapterException(
                PlataformaFreela.FREELANCER, MarketplaceAdapterErrorType.FALHA_ENVIO,
                "Freelancer proposta retornou HTTP " + response.statusCode()
                    + ": " + MarketplaceHttpContract.resumirResposta(response.body()));
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(PlataformaFreela.FREELANCER,
                MarketplaceAdapterErrorType.FALHA_REDE, "Falha ao enviar proposta Freelancer", e);
        }
    }

    private String oauthToken() {
        String token = config.getOauthToken();
        if (token.isBlank()) {
            throw new MarketplaceAdapterException(
                PlataformaFreela.FREELANCER,
                MarketplaceAdapterErrorType.FALHA_AUTENTICACAO,
                "FREELANCER_OAUTH_TOKEN ausente ou expirado"
            );
        }
        return token;
    }

    private void validarElegibilidadeOferta(long projetoIdExterno) {
        JsonNode projeto = buscarDetalhesProjetoJson(projetoIdExterno).path("result");
        if (projeto.has("project")) projeto = projeto.path("project");
        if (!"active".equalsIgnoreCase(projeto.path("status").asText(""))) {
            throw indisponivel("Projeto Freelancer nao esta mais aceitando ofertas");
        }
        JsonNode upgrades = projeto.path("upgrades");
        if (upgrades.path("pf_only").asBoolean(false) && !config.isPreferred()) {
            throw indisponivel("Projeto exclusivo para Freelancer Preferencial");
        }
        if (upgrades.path("qualified").asBoolean(false)) {
            throw indisponivel("Projeto exige qualificacoes adicionais; revisar manualmente");
        }
        if (upgrades.path("NDA").asBoolean(false) || upgrades.path("non_compete").asBoolean(false)) {
            throw indisponivel("Projeto exige aceite contratual adicional; revisar manualmente");
        }
        BigDecimal maximo = decimal(projeto.path("budget").path("maximum"));
        if ("hourly".equalsIgnoreCase(projeto.path("type").asText())
            && maximo.compareTo(new BigDecimal("50")) >= 0
            && !config.isVerified()) {
            throw indisponivel("Projeto por hora exige conta Freelancer Verificada");
        }
    }

    private BigDecimal decimal(JsonNode valor) {
        try {
            return valor.isNumber() ? valor.decimalValue() : new BigDecimal(valor.asText("0"));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private MarketplaceAdapterException indisponivel(String mensagem) {
        return new MarketplaceAdapterException(
            PlataformaFreela.FREELANCER,
            MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL,
            mensagem
        );
    }

    MensagemConversa enviarMensagemConversa(long threadId, String texto) {
        String url = MarketplaceHttpContract.freelancerThreadMessageUrl(config.getUrlBase(), threadId);
        try {
            Map<String, String> cookies = carregarCookies();
            String body = "message=" + URLEncoder.encode(texto, StandardCharsets.UTF_8);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", config.getUserAgent())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("freelancer-oauth-v1", oauthToken())
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body));
            if (!cookies.isEmpty()) {
                builder.header("Cookie", construirCookieHeader(cookies));
            }
            HttpResponse<String> response = client().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new MarketplaceAdapterException(
                    PlataformaFreela.FREELANCER,
                    FreelancerErrorClassifier.tipoErroMensagem(
                        response.statusCode(),
                        response.body(),
                        objectMapper
                    ),
                    "Freelancer envio mensagem retornou HTTP " + response.statusCode()
                        + ": " + MarketplaceHttpContract.resumirResposta(response.body())
                );
            }
            return MensagemConversa.builder()
                .plataforma(PlataformaFreela.FREELANCER)
                .id(System.currentTimeMillis())
                .idConversa(threadId)
                .nomePessoa(config.getWorkerSlug())
                .texto(texto)
                .dataCriacaoMillis(System.currentTimeMillis())
                .enviadaPorMim(true)
                .visualizada(true)
                .excluida(false)
                .sistema(false)
                .build();
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(
                PlataformaFreela.FREELANCER,
                MarketplaceAdapterErrorType.FALHA_REDE,
                "Falha ao enviar mensagem Freelancer",
                e
            );
        }
    }

    /**
     * Determina um valor de bid aceito pela API. Prefere o minimo real do projeto
     * (consultado na API); usa o orcamento do discovery como piso adicional. Falha
     * ABERTA: se a consulta nao responder, mantem o orcamento informado.
     */
    private java.math.BigDecimal resolverValorBid(long projetoIdExterno, java.math.BigDecimal orcamentoInformado) {
        java.math.BigDecimal minimoApi = null;
        try {
            JsonNode projeto = buscarDetalhesProjetoJson(projetoIdExterno);
            JsonNode budget = projeto.path("result").path("budget");
            if (budget.isMissingNode() || budget.isNull()) {
                budget = projeto.path("budget");
            }
            JsonNode minimo = budget.path("minimum");
            if (minimo.isNumber()) {
                minimoApi = java.math.BigDecimal.valueOf(minimo.asDouble());
            }
        } catch (Exception e) {
            log.debug("Freelancer: nao foi possivel obter minimo do projeto {}: {}",
                projetoIdExterno, e.getMessage());
        }
        java.math.BigDecimal candidato = orcamentoInformado != null
            ? orcamentoInformado
            : java.math.BigDecimal.ZERO;
        if (minimoApi != null && minimoApi.compareTo(candidato) > 0) {
            return minimoApi;
        }
        return candidato;
    }

    private JsonNode getJson(String url, String descricao) {
        try {
            Map<String, String> cookies = carregarCookies();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", config.getUserAgent())
                .header("Accept", "application/json,text/plain,*/*")
                .header("Accept-Language", "en-US,en;q=0.9,pt-BR;q=0.8,pt;q=0.7")
                .timeout(Duration.ofSeconds(30))
                .GET();
            if (!cookies.isEmpty()) {
                builder.header("Cookie", construirCookieHeader(cookies));
            }
            if (!config.getOauthToken().isBlank()) {
                builder.header("freelancer-oauth-v1", config.getOauthToken());
            }
            HttpResponse<String> response = client().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new MarketplaceAdapterException(
                    PlataformaFreela.FREELANCER,
                    MarketplaceAdapterErrorType.FALHA_REDE,
                    descricao + " retornou HTTP " + response.statusCode()
                        + ": " + MarketplaceHttpContract.resumirResposta(response.body())
                );
            }
            return objectMapper.readTree(response.body());
        } catch (MarketplaceAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(
                PlataformaFreela.FREELANCER,
                MarketplaceAdapterErrorType.FALHA_REDE,
                "Falha ao consultar " + descricao,
                e
            );
        }
    }

    Map<String, String> carregarCookies() {
        String arquivo = config.getCookiesArquivo();
        if (arquivo.isBlank() || !java.nio.file.Files.exists(java.nio.file.Path.of(arquivo))) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(java.nio.file.Path.of(arquivo).toFile());
            Map<String, String> cookies = new HashMap<>();
            if (root.isObject()) {
                root.fields().forEachRemaining(entry -> cookies.put(entry.getKey(), entry.getValue().asText("")));
            } else if (root.isArray()) {
                for (JsonNode cookie : root) {
                    String name = cookie.path("name").asText("");
                    String value = cookie.path("value").asText("");
                    if (!name.isBlank() && !value.isBlank()) {
                        cookies.put(name, value);
                    }
                }
            }
            cookies.entrySet().removeIf(e -> e.getKey().isBlank() || e.getValue().isBlank());
            return cookies;
        } catch (Exception e) {
            throw new MarketplaceAdapterException(
                PlataformaFreela.FREELANCER,
                MarketplaceAdapterErrorType.FALHA_PARSE,
                "Falha ao carregar cookies Freelancer",
                e
            );
        }
    }

    private String construirCookieHeader(Map<String, String> cookies) {
        List<String> partes = new ArrayList<>();
        cookies.forEach((nome, valor) -> partes.add(nome + "=" + valor));
        return String.join("; ", partes);
    }

    private synchronized HttpClient client() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        }
        return httpClient;
    }
}
