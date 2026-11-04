package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.freelasatende.model.entity.Aprendizado;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.MotivoAprendizado;
import com.freelasatende.model.enums.StatusRegraAprendida;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.repository.AprendizadoRepository;
import com.freelasatende.service.interfaceServico.AprendizadoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementação do serviço de aprendizado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AprendizadoServiceImpl implements AprendizadoService {

    private static final int MAX_REGRAS_CONSOLIDADAS = 30;
    private static final int MAX_CHARS_REGRAS_CONSOLIDADAS = 12_000;

    private final AprendizadoRepository aprendizadoRepository;
    private final ObjectMapper objectMapper;

    @Value("${ia.memoria-arquivo:data/memoria-contexto.json}")
    private String arquivoMemoria;

    @Override
    public Aprendizado salvar(Aprendizado aprendizado) {
        Aprendizado salvo = aprendizadoRepository.save(aprendizado);
        sincronizarMemoria(salvo);
        return salvo;
    }

    @Override
    public void registrarDecisao(Long projetoId, String tituloProjeto,
                                  Boolean foiAprovado, String motivo,
                                  ModoSistema modoUsado, String feedback) {
        MotivoAprendizado motivoAprendizado = MotivoAprendizado.fromTexto(
            feedback != null && !feedback.isBlank() ? feedback : motivo
        );
        registrarDecisaoDetalhada(
            projetoId,
            tituloProjeto,
            foiAprovado,
            null,
            modoUsado,
            feedback,
            motivoAprendizado,
            null,
            null,
            "LEGADO"
        );
    }

    @Override
    public void registrarDecisaoDetalhada(Long projetoId, String tituloProjeto,
                                          Boolean foiAprovado, TipoAcaoPendente tipoAcao,
                                          ModoSistema modoUsado, String feedback,
                                          MotivoAprendizado motivoAprendizado,
                                          String textoOriginal, String textoReescrito,
                                          String origemFeedback) {
        MotivoAprendizado motivoSeguro = motivoAprendizado != null
            ? motivoAprendizado
            : MotivoAprendizado.fromTexto(feedback);
        Aprendizado aprendizado = Aprendizado.builder()
            .data(LocalDateTime.now())
            .projetoId(projetoId)
            .tituloProjeto(tituloProjeto)
            .foiAprovado(foiAprovado)
            .motivo(tipoAcao != null ? tipoAcao.name() : null)
            .modoUsado(modoUsado)
            .feedbackUsuario(feedback)
            .regrasAprendidas(classificarFeedbackComoRegra(foiAprovado, feedback, motivoSeguro))
            .motivoAprendizado(motivoSeguro)
            .tipoAcao(tipoAcao)
            .statusRegra(StatusRegraAprendida.CONFIRMADA)
            .textoOriginal(textoOriginal)
            .textoReescrito(textoReescrito)
            .origemFeedback(origemFeedback)
            .build();

        Aprendizado salvo = aprendizadoRepository.save(aprendizado);
        atualizarRegras(feedback);
        sincronizarMemoria(salvo);
    }

    @Override
    public List<String> buscarRegrasAprendidas() {
        List<Aprendizado> aprendizados = aprendizadoRepository.findByFoiAprovadoTrueOrderByDataDesc();
        List<String> regras = new ArrayList<>();

        for (Aprendizado a : aprendizados) {
            if (a.getRegrasAprendidas() != null && !a.getRegrasAprendidas().isEmpty()) {
                regras.addAll(Arrays.asList(a.getRegrasAprendidas().split("\n")));
            }
        }

        return regras;
    }

    @Override
    public List<Aprendizado> buscarHistorico() {
        return aprendizadoRepository.findAll();
    }

    @Override
    public void atualizarRegras(String feedback) {
        if (feedback == null || feedback.isEmpty()) {
            return;
        }

        log.debug("Atualizando regras com feedback: {}", feedback);
    }

    @Override
    public Map<String, Object> obterResumoOperacional() {
        Map<String, Object> resumo = new LinkedHashMap<>();
        List<Aprendizado> ultimos = aprendizadoRepository
            .findTop20ByStatusRegraOrderByDataDesc(StatusRegraAprendida.CONFIRMADA);
        resumo.put("totalRegrasConfirmadas", aprendizadoRepository.countByStatusRegra(StatusRegraAprendida.CONFIRMADA));
        resumo.put("totalAguardandoConfirmacao", aprendizadoRepository.countByStatusRegra(StatusRegraAprendida.AGUARDANDO_CONFIRMACAO));
        resumo.put("ultimosAprendizados", ultimos.stream().map(this::resumoAprendizado).toList());
        resumo.put("alertasMemoria", alertasMemoria());
        return resumo;
    }

    private Map<String, Object> resumoAprendizado(Aprendizado aprendizado) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", aprendizado.getId());
        item.put("projetoId", aprendizado.getProjetoId());
        item.put("tituloProjeto", aprendizado.getTituloProjeto());
        item.put("tipoAcao", aprendizado.getTipoAcao() != null ? aprendizado.getTipoAcao().name() : null);
        item.put("motivoAprendizado", aprendizado.getMotivoAprendizado() != null ? aprendizado.getMotivoAprendizado().name() : null);
        item.put("foiAprovado", aprendizado.getFoiAprovado());
        item.put("regra", aprendizado.getRegrasAprendidas());
        item.put("data", aprendizado.getData());
        return item;
    }

    private String classificarFeedbackComoRegra(Boolean aprovado, String feedback, MotivoAprendizado motivoAprendizado) {
        String texto = feedback != null ? feedback.trim().toLowerCase() : "";
        MotivoAprendizado motivo = motivoAprendizado != null ? motivoAprendizado : MotivoAprendizado.fromTexto(texto);

        switch (motivo) {
            case BOM_TOM:
                return "Manter tom natural, curto e objetivo quando a mensagem for aprovada.";
            case ESCOPO_CLARO:
                return "Valorizar mensagens que conectam a resposta ao escopo real do projeto.";
            case ROBOTICO:
                return "Evitar tom robotico, template ou linguagem de sistema em mensagens ao cliente.";
            case GENERICO:
                return "Usar detalhes do projeto para evitar mensagem generica.";
            case RISCO_PLATAFORMA:
                return "Bloquear qualquer contato, pagamento ou combinacao fora da plataforma.";
            case DUPLICADO:
                return "Nao criar nova mensagem quando ja houver pendencia ou mensagem parecida.";
            case PERGUNTAS_DEMAIS:
                return "Limitar mensagens a uma ou duas perguntas realmente essenciais.";
            case FORA_DO_TOM:
                return "Reescrever com tom mais direto, humano e tranquilo antes de aprovar.";
            default:
                break;
        }

        if (texto.isBlank()) {
            return Boolean.TRUE.equals(aprovado)
                ? "Manter decisões aprovadas apenas quando houver motivo humano claro."
                : null;
        }

        if (texto.contains("robot") || texto.contains("robót") || texto.contains("sistema")
            || texto.contains("template") || texto.contains("automat")) {
            return "Evitar tom robotico, template ou linguagem de sistema em mensagens ao cliente.";
        }
        if (texto.contains("perguntou demais") || texto.contains("perguntas demais")
            || texto.contains("muitas perguntas")) {
            return "Limitar mensagens a uma ou duas perguntas realmente essenciais.";
        }
        if (texto.contains("generica") || texto.contains("genérica") || texto.contains("genérico")
            || texto.contains("generico")) {
            return "Usar detalhes do projeto para evitar mensagem generica.";
        }
        if (texto.contains("fora do tom") || texto.contains("tom errado") || texto.contains("frio demais")) {
            return "Reescrever com tom mais direto, humano e tranquilo antes de aprovar.";
        }
        if (texto.contains("natural") || texto.contains("boa resposta") || texto.contains("bom tom")) {
            return "Manter tom natural, curto e objetivo quando a mensagem for aprovada.";
        }
        if (Boolean.TRUE.equals(aprovado)) {
            return "Aplicar orientacao ensinada pelo usuario: " + limitarRegraAprendida(feedback.trim());
        }
        if (Boolean.FALSE.equals(aprovado)) {
            return "Mensagens recusadas devem ser revisadas por motivo humano antes de novo envio.";
        }
        return "Considerar feedback humano em novas revisoes de mensagem.";
    }

    private String limitarRegraAprendida(String regra) {
        if (regra == null || regra.isBlank()) {
            return "manter decisao aprovada somente com motivo humano claro.";
        }
        String normalizada = regra.replaceAll("\\s+", " ").trim();
        return normalizada.length() <= 240 ? normalizada : normalizada.substring(0, 240).trim();
    }

    private synchronized void sincronizarMemoria(Aprendizado aprendizado) {
        if (aprendizado == null || aprendizado.getRegrasAprendidas() == null || aprendizado.getRegrasAprendidas().isBlank()) {
            return;
        }
        try {
            Path arquivo = Path.of(arquivoMemoria);
            Path parent = arquivo.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            ObjectNode root = carregarOuCriarMemoria(arquivo);
            root.put("data_atualizacao", LocalDateTime.now().toString());

            ArrayNode regras = root.withArray("regras_ensinamento");
            ObjectNode regra = regras.addObject();
            regra.put("id_aprendizado", aprendizado.getId());
            regra.put("projeto_id", aprendizado.getProjetoId());
            regra.put("titulo", aprendizado.getTituloProjeto());
            regra.put("tipo_acao", aprendizado.getTipoAcao() != null ? aprendizado.getTipoAcao().name() : "");
            regra.put("motivo", aprendizado.getMotivoAprendizado() != null ? aprendizado.getMotivoAprendizado().name() : "");
            regra.put("aprovado", Boolean.TRUE.equals(aprendizado.getFoiAprovado()));
            regra.put("regra", aprendizado.getRegrasAprendidas());
            regra.put("data", aprendizado.getData() != null ? aprendizado.getData().toString() : LocalDateTime.now().toString());

            while (regras.size() > 80) {
                regras.remove(0);
            }

            root.put("regras_consolidadas", consolidarRegras(regras));
            Files.writeString(arquivo, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (Exception e) {
            log.warn("Nao foi possivel sincronizar aprendizado com memoria JSON: {}", e.getMessage());
        }
    }

    private ObjectNode carregarOuCriarMemoria(Path arquivo) {
        if (!Files.exists(arquivo)) {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("versao", "1.0");
            return root;
        }
        try {
            JsonNode node = objectMapper.readTree(Files.readString(arquivo));
            if (node instanceof ObjectNode objectNode) {
                return objectNode;
            }
            throw new IllegalStateException("Memoria nao e objeto JSON");
        } catch (Exception e) {
            criarBackupMemoria(arquivo, e.getMessage());
            ObjectNode root = objectMapper.createObjectNode();
            root.put("versao", "1.0");
            ArrayNode alertas = root.putArray("alertas_memoria");
            alertas.add("Memoria anterior estava corrompida e foi preservada em backup.");
            return root;
        }
    }

    private void criarBackupMemoria(Path arquivo, String motivo) {
        try {
            String timestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now());
            Path backup = arquivo.resolveSibling(arquivo.getFileName() + ".corrompido-" + timestamp + ".bak");
            Files.copy(arquivo, backup, StandardCopyOption.REPLACE_EXISTING);
            log.warn("Backup da memoria corrompida criado em {}: {}", backup, motivo);
        } catch (Exception backupError) {
            log.warn("Falha ao criar backup da memoria corrompida: {}", backupError.getMessage());
        }
    }

    private String consolidarRegras(ArrayNode regras) {
        // Reescrita total a cada sincronizacao: antes o texto antigo era re-apendado
        // infinitamente e a memoria cresceu para 122KB com blocos repetidos. Agora
        // mantemos apenas as regras unicas mais recentes, com limite de tamanho.
        List<String> linhas = new ArrayList<>();
        linhas.add("## Regras do modo ensinamento confirmadas");
        List<String> vistas = new ArrayList<>();
        int totalChars = linhas.get(0).length();
        for (int i = regras.size() - 1; i >= 0 && linhas.size() <= MAX_REGRAS_CONSOLIDADAS; i--) {
            JsonNode item = regras.get(i);
            String regra = item.path("regra").asText("").trim();
            if (regra.isBlank()) {
                continue;
            }
            if (vistas.contains(regra)) {
                continue;
            }
            String motivo = item.path("motivo").asText("").trim();
            String linha = motivo.isBlank() ? "- " + regra : "- [" + motivo + "] " + regra;
            if (totalChars + linha.length() + 1 > MAX_CHARS_REGRAS_CONSOLIDADAS) {
                break;
            }
            vistas.add(regra);
            linhas.add(linha);
            totalChars += linha.length() + 1;
        }
        return String.join("\n", linhas).trim();
    }

    private List<String> alertasMemoria() {
        try {
            Path arquivo = Path.of(arquivoMemoria);
            if (!Files.exists(arquivo)) {
                return List.of("Memoria JSON ainda nao existe.");
            }
            JsonNode root = objectMapper.readTree(Files.readString(arquivo));
            JsonNode alertas = root.path("alertas_memoria");
            if (!alertas.isArray()) {
                return List.of();
            }
            List<String> resultado = new ArrayList<>();
            alertas.forEach(a -> resultado.add(a.asText()));
            return resultado;
        } catch (Exception e) {
            return List.of("Memoria JSON indisponivel: " + e.getMessage());
        }
    }
}
