package com.freelasatende.service.impl;

import com.freelasatende.config.IaConfig;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.service.interfaceServico.IaProvider;
import com.freelasatende.util.RetryUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
final class AvaliadorIaMemoriaHelper {

    enum ContextoUso {
        TRIAGEM,
        CONVERSA,
        PROPOSTA
    }

    private AvaliadorIaMemoriaHelper() {
    }

    static String carregarConhecimento(Class<?> tipo, String arquivoConhecimento) {
        try {
            var inputStream = tipo.getResourceAsStream(arquivoConhecimento);
            if (inputStream != null) {
                return new String(inputStream.readAllBytes());
            }
        } catch (Exception e) {
            log.warn("Não encontrou arquivo de conhecimento");
        }
        return "{}";
    }

    static String carregarContexto(
        String arquivoMemoria,
        Object memoriaLock,
        ObjectMapper objectMapper,
        ContextoUso uso
    ) {
        try {
            Path arquivo = Paths.get(arquivoMemoria);
            synchronized (memoriaLock) {
                if (Files.exists(arquivo)) {
                    String json = Files.readString(arquivo);
                    JsonNode root = objectMapper.readTree(json);
                    return AvaliadorIaMemoriaPromptHelper.formatarContexto(root, uso);
                }
            }
        } catch (Exception e) {
            log.warn("Não encontrou arquivo de contexto");
        }
        return "Nenhuma interação anterior registrada.";
    }

    static boolean atualizarMemoria(
        Projeto projeto,
        boolean capaz,
        String resposta,
        String arquivoMemoria,
        Object memoriaLock,
        ObjectMapper objectMapper,
        IaProvider iaProvider
    ) {
        try {
            synchronized (memoriaLock) {
                Path arquivo = Paths.get(arquivoMemoria);
                criarDiretorioMemoriaSeNecessario(arquivo);
                ObjectNode root;

                if (Files.exists(arquivo)) {
                    try {
                        root = (ObjectNode) objectMapper.readTree(Files.readString(arquivo));
                    } catch (Exception parseException) {
                        log.warn("Memória corrompida detectada. Recriando arquivo de contexto.");
                        root = objectMapper.createObjectNode();
                        root.put("versao", "1.0");
                    }
                } else {
                    root = objectMapper.createObjectNode();
                    root.put("versao", "1.0");
                }
                root.put("data_atualizacao", LocalDateTime.now().toString());
                root.put("proximo_projeto_id", projeto.getId() + 1);

                ObjectNode stats = (ObjectNode) root.get("estatisticas");
                if (stats == null) {
                    stats = objectMapper.createObjectNode();
                    root.set("estatisticas", stats);
                }
                stats.put("total_projetos_avaliados", stats.path("total_projetos_avaliados").asInt() + 1);
                if (capaz) {
                    stats.put("total_aprovados", stats.path("total_aprovados").asInt() + 1);
                } else {
                    stats.put("total_recusados", stats.path("total_recusados").asInt() + 1);
                }
                int totalAvaliados = stats.path("total_projetos_avaliados").asInt();
                if (totalAvaliados > 0) {
                    stats.put("taxa_sucesso",
                        (double) stats.path("total_aprovados").asInt() / totalAvaliados);
                }

                JsonNode historico = root.has("historico_recente")
                    ? root.get("historico_recente")
                    : null;
                if (historico == null || !historico.isArray()) {
                    historico = objectMapper.createArrayNode();
                    root.set("historico_recente", historico);
                }

                ((ArrayNode) historico).addObject()
                    .put("id", projeto.getId())
                    .put("titulo", projeto.getTitulo())
                    .put("tipo", capaz ? "APROVADO" : "RECUSADO")
                    .put("resposta_ia", resposta)
                    .put("provider", iaProvider.getNome())
                    .put("modelo", iaProvider.getModelo())
                    .put("data", LocalDateTime.now().toString());

                if (((ArrayNode) historico).size() > 50) {
                    ((ArrayNode) historico).remove(0);
                }

                root.put("data_atualizacao", LocalDateTime.now().toString());

                Files.writeString(arquivo, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root));

                return ((ArrayNode) historico).size() == 30;
            }
        } catch (Exception e) {
            log.error("Erro ao atualizar memoria: {}", e.getMessage());
            return false;
        }
    }

    static void otimizarMemoriaContexto(
        IaConfig iaConfig,
        IaProvider iaProvider,
        String arquivoMemoria,
        Object memoriaLock,
        ObjectMapper objectMapper
    ) {
        try {
            synchronized (memoriaLock) {
                Path arquivo = Paths.get(arquivoMemoria);
                if (!Files.exists(arquivo)) return;

                ObjectNode root = (ObjectNode) objectMapper.readTree(Files.readString(arquivo));
                JsonNode historico = root.get("historico_recente");

                if (historico != null && historico.isArray() && historico.size() >= 30) {
                    log.debug("Iniciando otimização da memória de contexto da IA ({} itens)...", historico.size());

                    String historicoString = objectMapper.writeValueAsString(historico);
                    String prompt = String.format("""
                        Você é um otimizador de regras de negócio.
                        Analise este histórico de projetos avaliados pelo usuário.
                        Sua tarefa é destilar os padrões: o que o usuário sempre aprova e o que sempre recusa?

                        Gere um resumo enxuto (máximo 5 regras concisas) de como o sistema deve avaliar projetos no futuro.
                        Não cite IDs. Apenas as regras consolidadas.

                        Histórico bruto:
                        %s
                        """, historicoString);

                    String resumo = RetryUtils.comRetryChecked(
                        () -> iaProvider.enviar(prompt, 1500),
                        iaConfig.getMaxRetries(),
                        "otimizar-memoria"
                    );

                    if (resumo != null && !resumo.isBlank()) {
                        root.put("regras_consolidadas", resumo.trim());
                        ArrayNode novoHistorico = objectMapper.createArrayNode();
                        for (int i = historico.size() - 10; i < historico.size(); i++) {
                            novoHistorico.add(historico.get(i));
                        }
                        root.set("historico_recente", novoHistorico);
                        root.put("ultima_otimizacao", LocalDateTime.now().toString());

                        Files.writeString(arquivo, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
                        log.debug("Memória otimizada com sucesso! Novas regras geradas.");
                    }
                }
            }
        } catch (Exception e) {
            log.error("Erro ao otimizar memória de contexto: {}", e.getMessage());
        }
    }

    static void salvarRegrasConsolidadas(
        String novasRegras,
        String arquivoMemoria,
        Object memoriaLock,
        ObjectMapper objectMapper
    ) {
        try {
            synchronized (memoriaLock) {
                Path arquivo = Paths.get(arquivoMemoria);
                criarDiretorioMemoriaSeNecessario(arquivo);
                ObjectNode root;

                if (Files.exists(arquivo)) {
                    root = (ObjectNode) objectMapper.readTree(Files.readString(arquivo));
                } else {
                    root = objectMapper.createObjectNode();
                    root.put("versao", "1.0");
                }

                root.put("regras_consolidadas", novasRegras != null ? novasRegras.trim() : "");
                root.put("data_atualizacao", LocalDateTime.now().toString());
                root.put("ultima_edicao_manual", LocalDateTime.now().toString());

                Files.writeString(arquivo, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root));
            }

            log.info("Regras consolidadas salvas manualmente pelo usuario ({} chars)",
                novasRegras != null ? novasRegras.length() : 0);
        } catch (Exception e) {
            log.error("Erro ao salvar regras consolidadas: {}", e.getMessage());
            throw new RuntimeException("Falha ao salvar regras: " + e.getMessage(), e);
        }
    }

    static Map<String, Object> obterDiagnosticoMemoriaIa(
        IaConfig iaConfig,
        IaProvider iaProvider,
        Map<Long, Boolean> cacheAvaliacoes,
        String arquivoMemoria,
        Object memoriaLock,
        ObjectMapper objectMapper
    ) {
        Map<String, Object> diagnostico = new LinkedHashMap<>();
        List<String> alertas = new ArrayList<>();

        String apiKey = iaConfig.getApiKey() != null ? iaConfig.getApiKey().trim() : "";
        boolean apiKeyConfigurada = !apiKey.isBlank();
        boolean autenticacaoGerenciadaPeloCli = "codex".equalsIgnoreCase(iaProvider.getNome());

        diagnostico.put("provider", iaProvider.getNome());
        diagnostico.put("modelo", iaProvider.getModelo());
        diagnostico.put("configProvider", iaConfig.getProvider());
        diagnostico.put("baseUrl", iaConfig.getBaseUrl());
        diagnostico.put("maxRetries", iaConfig.getMaxRetries());
        diagnostico.put("timeoutSegundos", iaConfig.getTimeout());
        diagnostico.put("apiKeyConfigurada", apiKeyConfigurada);
        diagnostico.put("autenticacaoGerenciadaPeloCli", autenticacaoGerenciadaPeloCli);
        diagnostico.put("cacheAvaliacoesTamanho", cacheAvaliacoes.size());
        diagnostico.put("timestampConsulta", LocalDateTime.now().toString());

        if (!apiKeyConfigurada && !autenticacaoGerenciadaPeloCli) {
            alertas.add("IA_API_KEY nao configurada");
        }

        Path arquivo = Paths.get(arquivoMemoria);
        diagnostico.put("arquivoMemoriaPath", arquivo.toAbsolutePath().toString());
        diagnostico.put("arquivoMemoriaExiste", Files.exists(arquivo));

        synchronized (memoriaLock) {
            if (!Files.exists(arquivo)) {
                alertas.add("Arquivo de memoria ainda nao foi criado");
                diagnostico.put("saude", alertas.isEmpty() ? "OK" : "ATENCAO");
                diagnostico.put("alertas", alertas);
                return diagnostico;
            }

            try {
                diagnostico.put("arquivoMemoriaTamanhoBytes", Files.size(arquivo));
                JsonNode root = objectMapper.readTree(Files.readString(arquivo));

                diagnostico.put("versaoMemoria", root.path("versao").asText("desconhecida"));
                String dataAtualizacao = root.path("data_atualizacao").asText(null);
                diagnostico.put("dataAtualizacaoMemoria", dataAtualizacao);
                diagnostico.put("ultimaOtimizacao", root.path("ultima_otimizacao").asText(null));

                if (dataAtualizacao != null) {
                    try {
                        LocalDateTime atualizacao = LocalDateTime.parse(dataAtualizacao);
                        long minutosSemAtualizar = Duration.between(atualizacao, LocalDateTime.now()).toMinutes();
                        diagnostico.put("minutosSemAtualizar", minutosSemAtualizar);
                        if (minutosSemAtualizar > 180) {
                            alertas.add("Memoria sem atualizacao ha mais de 180 minutos");
                        }
                    } catch (Exception ignored) {
                        alertas.add("Data de atualizacao da memoria em formato invalido");
                    }
                }

                JsonNode stats = root.path("estatisticas");
                int totalAvaliados = stats.path("total_projetos_avaliados").asInt(0);
                int totalAprovados = stats.path("total_aprovados").asInt(0);
                int totalRecusados = stats.path("total_recusados").asInt(0);
                double taxaSucesso = stats.path("taxa_sucesso").asDouble(
                    totalAvaliados > 0 ? (double) totalAprovados / totalAvaliados : 0.0
                );

                diagnostico.put("totalProjetosAvaliados", totalAvaliados);
                diagnostico.put("totalAprovados", totalAprovados);
                diagnostico.put("totalRecusados", totalRecusados);
                diagnostico.put("taxaSucesso", taxaSucesso);

                JsonNode historico = root.path("historico_recente");
                int historicoTotal = historico.isArray() ? historico.size() : 0;
                int historicoAprovados = 0;
                int historicoRecusados = 0;
                Map<String, Object> ultimaEntrada = null;

                if (historico.isArray()) {
                    for (JsonNode item : historico) {
                        String tipo = item.path("tipo").asText("");
                        if ("APROVADO".equalsIgnoreCase(tipo)) {
                            historicoAprovados++;
                        } else if ("RECUSADO".equalsIgnoreCase(tipo)) {
                            historicoRecusados++;
                        }
                    }

                    if (historicoTotal > 0) {
                        JsonNode ultimo = historico.get(historicoTotal - 1);
                        ultimaEntrada = new LinkedHashMap<>();
                        ultimaEntrada.put("id", ultimo.path("id").asLong());
                        ultimaEntrada.put("titulo", ultimo.path("titulo").asText(""));
                        ultimaEntrada.put("tipo", ultimo.path("tipo").asText(""));
                        ultimaEntrada.put("data", ultimo.path("data").asText(""));
                        ultimaEntrada.put("provider", ultimo.path("provider").asText(""));
                        ultimaEntrada.put("modelo", ultimo.path("modelo").asText(""));
                    }
                } else {
                    alertas.add("historico_recente nao esta em formato de lista");
                }

                diagnostico.put("historicoTotal", historicoTotal);
                diagnostico.put("historicoAprovados", historicoAprovados);
                diagnostico.put("historicoRecusados", historicoRecusados);
                diagnostico.put("ultimaEntradaHistorico", ultimaEntrada);

                String regrasConsolidadas = root.path("regras_consolidadas").asText("");
                boolean temRegrasConsolidadas = regrasConsolidadas != null && !regrasConsolidadas.isBlank();
                diagnostico.put("temRegrasConsolidadas", temRegrasConsolidadas);
                diagnostico.put("regrasConsolidadasTamanho", regrasConsolidadas != null ? regrasConsolidadas.length() : 0);
                diagnostico.put(
                    "regrasConsolidadasPreview",
                    temRegrasConsolidadas
                        ? regrasConsolidadas.substring(0, Math.min(300, regrasConsolidadas.length()))
                        : ""
                );

                if (!temRegrasConsolidadas) {
                    alertas.add("Sem regras consolidadas geradas ainda");
                }
                if (totalAvaliados == 0) {
                    alertas.add("Sem projetos avaliados na memoria");
                }
            } catch (Exception e) {
                alertas.add("Erro ao ler arquivo de memoria: " + e.getMessage());
                diagnostico.put("erroLeituraMemoria", e.getMessage());
            }
        }

        diagnostico.put("saude", alertas.isEmpty() ? "OK" : "ATENCAO");
        diagnostico.put("alertas", alertas);
        return diagnostico;
    }

    static void criarDiretorioMemoriaSeNecessario(Path arquivo) throws java.io.IOException {
        Path parent = arquivo.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }
}
