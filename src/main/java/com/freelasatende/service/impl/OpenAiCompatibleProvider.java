package com.freelasatende.service.impl;

import com.freelasatende.config.IaConfig;
import com.freelasatende.service.interfaceServico.IaProvider;
import com.freelasatende.util.IaSaidaRaciocinio;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Provider genérico compatível com OpenAI API.
 * Funciona com: OpenAI, Azure OpenAI, Ollama, LM Studio,
 * ou qualquer backend OpenAI-compatible.
 *
 * Para Ollama: base-url=http://localhost:11434/v1
 * Para OpenAI: base-url=https://api.openai.com/v1
 */
@Slf4j
public class OpenAiCompatibleProvider implements IaProvider {

    private final IaConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleProvider(IaConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.getTimeout()))
            .build();
    }

    @Override
    public String enviar(String prompt, int maxTokens) throws IaProviderException {
        return tentarCamadas(prompt, maxTokens, null, "Nenhum modelo de IA foi configurado.");
    }

    /**
     * Repete a chamada pulando o modelo que respondeu inutilizavel (ex.:
     * decisao fora do formato SIM/NAO). A cadeia de fallback por erro de
     * transporte so percorre modelos quando a chamada FALHA; resposta mal
     * formatada volta HTTP 200 e pararia no primeiro modelo. Medido em
     * producao: modelo free responde "Analise o projeto..." sem decisao e o
     * projeto era RECUSADO por "fora do formato" sem consultar o proximo
     * modelo da cadeia.
     */
    @Override
    public String enviarPulandoModelo(String prompt, int maxTokens, String modeloAEvitar)
            throws IaProviderException {
        String alvo = modeloAEvitar == null ? "" : modeloAEvitar.trim();
        return tentarCamadas(prompt, maxTokens, alvo, "Nenhum modelo alternativo configurado.");
    }

    /** Uma camada da cadeia: modelos que compartilham a mesma base-url e chave. */
    private record Camada(String nome, Set<String> modelos, String baseUrl, String apiKey) {
    }

    /**
     * Percorre as camadas em ordem: principal (ia.model + ia.fallback-models),
     * secundaria (provedor proprio, ex.: z.ai) e terciaria (ia.tertiary-models,
     * de volta na base-url/chave principal, ex.: modelo pago barato do
     * OpenRouter). Passa para o proximo modelo em todo erro retryable (429
     * rate limit, 5xx como 503 overloaded, saldo/cota esgotados); erro nao
     * retryable aborta na hora.
     */
    private String tentarCamadas(String prompt, int maxTokens, String modeloAEvitar, String semModelo)
            throws IaProviderException {
        IaProviderException ultimaFalha = null;
        for (Camada camada : camadas()) {
            for (String modelo : camada.modelos()) {
                if (modelo.equals(modeloAEvitar)) {
                    continue;
                }
                try {
                    String resposta = enviarComModelo(prompt, maxTokens, modelo, camada.baseUrl(), camada.apiKey());
                    if (!"principal".equals(camada.nome())) {
                        log.info("Camadas anteriores de IA esgotadas; resposta obtida via camada {}, modelo {}",
                            camada.nome(), modelo);
                    }
                    return resposta;
                } catch (IaProviderException e) {
                    ultimaFalha = e;
                    if (!e.isRetriable()) {
                        throw e;
                    }
                    log.warn("Modelo {} da camada {} indisponivel (HTTP {}); tentando proximo da cadeia",
                        modelo, camada.nome(), e.getStatusCode());
                }
            }
        }
        throw ultimaFalha != null
            ? ultimaFalha
            : new IaProviderException(semModelo, 500, false);
    }

    private List<Camada> camadas() {
        List<Camada> camadas = new ArrayList<>(3);
        camadas.add(new Camada("principal", modelosParaTentativa(), config.getBaseUrl(), config.getApiKey()));
        camadas.add(new Camada("secundaria", modelosSecundariosParaTentativa(),
            config.getSecondaryBaseUrl(), config.getSecondaryApiKey()));
        camadas.add(new Camada("terciaria", separarModelos(config.getTertiaryModels()),
            config.getBaseUrl(), config.getApiKey()));
        return camadas;
    }

    private String enviarComModelo(String prompt, int maxTokens, String modelo, String baseUrl, String apiKey)
            throws IaProviderException {
        try {
            return enviarUmaVezComModelo(prompt, maxTokens, modelo, baseUrl, apiKey);
        } catch (IaProviderException e) {
            throw e;
        } catch (Exception e) {
            if (!ehFalhaDeDns(e)) {
                throw new IaProviderException("Erro ao comunicar com " + getNome() + ": " + descreverCausa(e), e);
            }
            // DNS do container pode falhar por alguns segundos (resolv.conf do
            // host instavel). Medido em 15/09/2026: 9 consultas seguidas ao
            // OpenRouter falharam em 2,8s com UnresolvedAddressException e ~1min
            // depois a resolucao normalizou sozinha. Re-tentar a MESMA chamada
            // cobre o solavanco sem trocar de modelo nem abortar a conversa.
            Exception falha = e;
            for (int tentativa = 1; tentativa <= 2; tentativa++) {
                try {
                    Thread.sleep(1500L * tentativa);
                    return enviarUmaVezComModelo(prompt, maxTokens, modelo, baseUrl, apiKey);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IaProviderException("Interrompido aguardando retry de DNS em " + getNome(), ie);
                } catch (IaProviderException ie) {
                    throw ie;
                } catch (Exception retry) {
                    falha = retry;
                }
            }
            throw new IaProviderException(
                "Erro ao comunicar com " + getNome() + ": falha de DNS apos retries: " + descreverCausa(falha),
                falha
            );
        }
    }

    /**
     * Detecta falha de resolucao de nome em qualquer nivel da causa.
     * E o modo de falha do solavanco de DNS medido em producao.
     */
    static boolean ehFalhaDeDns(Throwable e) {
        Throwable atual = e;
        while (atual != null) {
            if (atual instanceof UnresolvedAddressException || atual instanceof UnknownHostException) {
                return true;
            }
            atual = atual.getCause() == atual ? null : atual.getCause();
        }
        return false;
    }

    /**
     * Mensagem sempre util para operador: excecoes de transporte do
     * java.net.http costumam ter getMessage() nulo (medido: o card chegou ao
     * Telegram como "Erro ao comunicar com openai: null"), entao o nome da
     * classe e a causa raiz entram no lugar.
     */
    static String descreverCausa(Throwable e) {
        String mensagem = e.getMessage();
        String base = mensagem == null || mensagem.isBlank()
            ? e.getClass().getSimpleName()
            : mensagem;
        Throwable causa = e.getCause();
        if (causa != null && causa != e) {
            String causaMensagem = causa.getMessage();
            base += " (causa: " + causa.getClass().getSimpleName()
                + (causaMensagem == null || causaMensagem.isBlank() ? "" : ": " + causaMensagem) + ")";
        }
        return base;
    }

    private String enviarUmaVezComModelo(String prompt, int maxTokens, String modelo, String baseUrl, String apiKey)
            throws IaProviderException {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", modelo);
            body.put("max_tokens", maxTokens);
            body.put("temperature", 0.7);

            ArrayNode messages = body.putArray("messages");
            ObjectNode userMessage = messages.addObject();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);

            String jsonBody = objectMapper.writeValueAsString(body);
            String url = baseUrl + "/chat/completions";

            log.debug("Enviando para {}: {} (modelo: {})", getNome(), url, modelo);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(config.getTimeout()));

            // Adicionar API key apenas se fornecida (Ollama local não precisa)
            if (apiKey != null && !apiKey.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = enviarComPrazoTotal(requestBuilder.build(), modelo);

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode choices = root.path("choices");

                if (choices.isArray() && choices.size() > 0) {
                    String conteudo = choices.get(0).path("message").path("content").asText("");
                    // Modelos de raciocinio devolvem <think>...</think> ou
                    // prosa de pensamento no content; removido aqui, content
                    // que era so raciocinio fica vazio e cai no 422 retryable
                    // abaixo, passando para o proximo modelo da cadeia.
                    conteudo = IaSaidaRaciocinio.removerBlocos(conteudo);
                    // O roteador "openrouter/free" escolhe o modelo por conta
                    // propria e as vezes cai num classificador de seguranca
                    // (Nemotron Safety Guard / Llama Guard). Medido em producao
                    // em 05/10/2026: resposta ao cliente da Workana chegou como
                    // "User Safety: safe". Isso nao e texto gerado: pula para o
                    // proximo modelo da cadeia em vez de aceitar.
                    if (ehRespostaInutilizavel(conteudo)) {
                        String roteado = root.path("model").asText(modelo);
                        throw new IaProviderException(
                            "Modelo " + roteado + " devolveu saida inutilizavel (vazia ou de classificador de seguranca)",
                            422, true);
                    }
                    return conteudo;
                }

                log.warn("Resposta sem choices: {}", response.body());
                return "NAO";

            } else if (response.statusCode() == 429) {
                throw new IaProviderException("Rate limit: " + response.body(), 429, true);
            } else {
                // 403 aqui quase nunca e "credencial invalida": e o provedor
                // dizendo que AQUELE modelo saiu do ar. Medido em producao:
                // 225 erros em 4h com "The free MiniMax M3 and M2.7 models have
                // been retired", tratados como nao-retriaveis, o que abortava a
                // cadeia inteira e deixava projetos sem avaliacao -- o fallback
                // secundario (OpenRouter) nunca chegava a ser tentado.
                // Um 403 de modelo indisponivel deve pular para o proximo
                // modelo; 401 (chave invalida) continua fatal.
                boolean modeloIndisponivel = response.statusCode() == 403
                    && !ehFalhaDeCredencial(response.body());
                // Saldo zerado nao e erro de requisicao: e a conta daquela
                // base-url acabando. Medido em producao em 14/09: o provedor
                // principal passou a responder 400 "insufficient credits" e,
                // como 400 era fatal, a cadeia abortava no primeiro modelo --
                // 1.775 avaliacoes perdidas em 21h e nenhuma proposta enviada.
                // Sem credito, os demais modelos da MESMA chave tambem falham,
                // entao a cadeia escorre rapido ate o provedor secundario, que
                // tem chave propria.
                boolean saldoEsgotado = ehSaldoEsgotado(response.body());
                throw new IaProviderException(
                    "Erro " + response.statusCode() + ": " + response.body(),
                    response.statusCode(),
                    response.statusCode() >= 500 || modeloIndisponivel || saldoEsgotado
                );
            }

        } catch (IaProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new IaProviderException("Erro ao comunicar com " + getNome() + ": " + descreverCausa(e), e);
        }
    }

    /**
     * Distingue 403 de credencial (fatal) de 403 de modelo fora do ar
     * (retriavel, deve cair para o proximo modelo da cadeia).
     */
    static boolean ehFalhaDeCredencial(String corpo) {
        if (corpo == null || corpo.isBlank()) {
            return false;
        }
        String c = corpo.toLowerCase();
        return c.contains("invalid api key")
            || c.contains("invalid_api_key")
            || c.contains("incorrect api key")
            || c.contains("no auth credentials")
            || c.contains("authentication")
            || c.contains("unauthorized");
    }

    private static final java.util.regex.Pattern SAIDA_CLASSIFICADOR = java.util.regex.Pattern.compile(
        "(?is)^\\s*(?:(?:user|response|prompt)\\s+safety\\s*:\\s*(?:safe|unsafe)\\b.*"
            + "|(?:safe|unsafe)(?:\\s*\\n\\s*S\\d+(?:\\s*,\\s*S\\d+)*)?\\s*)$");

    /**
     * Resposta 200 que nao serve como texto gerado: vazia ou no formato de
     * modelo classificador de seguranca ("User Safety: safe", "safe",
     * "unsafe\nS1"). Tratada como retryable para a cadeia seguir.
     */
    static boolean ehRespostaInutilizavel(String conteudo) {
        if (conteudo == null || conteudo.isBlank()) {
            return true;
        }
        return SAIDA_CLASSIFICADOR.matcher(conteudo).matches();
    }

    /**
     * Detecta que a conta daquela base-url ficou sem saldo. O provedor pode
     * devolver 400, 402 ou 403 nesse caso, entao a decisao e pelo corpo, nao
     * pelo status. Vale como "tente o proximo": a cadeia principal se esgota e
     * o provedor secundario, com chave propria, assume.
     */
    static boolean ehSaldoEsgotado(String corpo) {
        if (corpo == null || corpo.isBlank()) {
            return false;
        }
        String c = corpo.toLowerCase();
        return c.contains("insufficient credits")
            || c.contains("insufficient_quota")
            || c.contains("insufficient balance")
            || c.contains("exceeded your current quota")
            || c.contains("payment required")
            || c.contains("purchase more credits")
            || c.contains("add more credits");
    }

    /**
     * HttpRequest.timeout() do JDK so cobre ate a chegada dos cabecalhos. O
     * OpenRouter devolve os cabecalhos na hora e segura o corpo (espacos de
     * keep-alive) enquanto o provedor processa; se o provedor trava, send()
     * espera para sempre. Medido em producao em 27/09/2026: a thread de
     * discovery ficou 6h parada em HttpClient.send e nenhum marketplace foi
     * lido no periodo (Freelancer.com.br, ultimo da fila, nunca rodava).
     * Aqui o prazo vale para a chamada inteira; estourou, cancela e devolve
     * erro retryable para a cadeia seguir para o proximo modelo.
     */
    private HttpResponse<String> enviarComPrazoTotal(HttpRequest request, String modelo) throws Exception {
        long prazoSegundos = Math.max(1, config.getTimeout());
        CompletableFuture<HttpResponse<String>> futuro =
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return futuro.get(prazoSegundos, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            futuro.cancel(true);
            throw new IaProviderException(
                "Timeout total de " + prazoSegundos + "s aguardando resposta do modelo " + modelo, 504, true);
        } catch (InterruptedException e) {
            futuro.cancel(true);
            Thread.currentThread().interrupt();
            throw new IaProviderException("Interrompido aguardando resposta de " + getNome(), e);
        } catch (ExecutionException e) {
            Throwable causa = e.getCause() != null ? e.getCause() : e;
            if (causa instanceof Exception excecao) {
                throw excecao;
            }
            throw new IaProviderException("Erro ao comunicar com " + getNome() + ": " + causa, e);
        }
    }

    private Set<String> modelosParaTentativa() {
        Set<String> modelos = new LinkedHashSet<>();
        if (config.getModel() != null && !config.getModel().isBlank()) {
            modelos.add(config.getModel().trim());
        }
        String configurados = config.getFallbackModels();
        if (configurados != null) {
            for (String modelo : configurados.split(",")) {
                if (!modelo.isBlank()) {
                    modelos.add(modelo.trim());
                }
            }
        }
        return modelos;
    }

    private Set<String> modelosSecundariosParaTentativa() {
        String baseUrl = config.getSecondaryBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return new LinkedHashSet<>();
        }
        return separarModelos(config.getSecondaryModels());
    }

    private static Set<String> separarModelos(String configurados) {
        Set<String> modelos = new LinkedHashSet<>();
        if (configurados == null || configurados.isBlank()) {
            return modelos;
        }
        for (String modelo : configurados.split(",")) {
            if (!modelo.isBlank()) {
                modelos.add(modelo.trim());
            }
        }
        return modelos;
    }

    @Override
    public String getNome() {
        return config.getProvider() != null ? config.getProvider() : "OpenAI-Compatible";
    }

    @Override
    public String getModelo() {
        return config.getModel();
    }

    @Override
    public boolean estaDisponivel() {
        try {
            enviar("Responda apenas: OK", 10);
            return true;
        } catch (Exception e) {
            log.warn("{} não disponível: {}", getNome(), e.getMessage());
            return false;
        }
    }
}
