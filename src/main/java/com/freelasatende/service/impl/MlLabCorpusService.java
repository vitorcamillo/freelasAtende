package com.freelasatende.service.impl;

import com.freelasatende.policy.OperationalSafetyPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MlLabCorpusService {

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
        ".md", ".txt", ".json", ".jsonl", ".log", ".html", ".yml", ".yaml", ".properties"
    );
    private static final Pattern SECRET_PATTERN = Pattern.compile(
        "(?i)(api[-_ ]?key|token|senha|password|cookie|authorization|bot[-_ ]?token|chat[-_ ]?id)\\s*[:=]\\s*[^\\s,;}{]+"
    );

    private final OperationalSafetyPolicy operationalSafetyPolicy;

    @Value("${ml-lab.corpus.source-roots:data,target,captures,data/ml-lab/ingest}")
    private String sourceRoots;

    @Value("${ml-lab.corpus.max-files:500}")
    private int maxCorpusFiles;

    @Value("${ml-lab.corpus.max-bytes-per-file:8000}")
    private int maxBytesPerFile;

    public Snapshot carregar() {
        List<String> snippets = new ArrayList<>();
        List<String> roots = parseRoots(sourceRoots);
        int limit = Math.max(10, maxCorpusFiles);
        int perFileLimit = Math.max(512, maxBytesPerFile);
        for (String rootText : roots) {
            if (snippets.size() >= limit) break;
            Path root = Path.of(rootText);
            if (!Files.exists(root)) {
                continue;
            }
            try (var stream = Files.walk(root, 4)) {
                stream.filter(Files::isRegularFile)
                    .filter(path -> snippets.size() < limit)
                    .filter(this::arquivoTextoPermitido)
                    .filter(path -> !path.toString().contains("/profiles/"))
                    .filter(path -> !path.toString().contains("/node_modules/"))
                    .filter(path -> !path.toString().contains("/.git/"))
                    .limit(limit - snippets.size())
                    .forEach(path -> snippets.add(snippet(path, perFileLimit)));
            } catch (Exception e) {
                snippets.add("Fonte indisponivel " + root + ": " + e.getMessage());
            }
        }
        List<String> limpos = snippets.stream()
            .filter(text -> text != null && !text.isBlank())
            .map(this::redigirSegredos)
            .toList();
        String summary = "Fontes lidas: " + roots + ". Arquivos uteis: " + limpos.size()
            + ". " + operationalSafetyPolicy.labCorpusSummarySuffix();
        return new Snapshot(summary, limpos.stream().limit(60).toList());
    }

    public String contexto(Snapshot corpus, int ciclo) {
        if (corpus == null || corpus.snippets().isEmpty()) {
            return "";
        }
        int total = corpus.snippets().size();
        int start = Math.floorMod(ciclo - 1, total);
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < Math.min(6, total); i++) {
            selected.add(corpus.snippets().get((start + i) % total));
        }
        return String.join("\n\n---\n\n", selected);
    }

    private boolean arquivoTextoPermitido(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return TEXT_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private String snippet(Path path, int limit) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            int size = Math.min(bytes.length, limit);
            String text = new String(bytes, 0, size, StandardCharsets.UTF_8);
            return path + "\n" + limitar(text.replace('\u0000', ' '), limit);
        } catch (Exception e) {
            return path + "\nErro ao ler: " + e.getMessage();
        }
    }

    private String redigirSegredos(String text) {
        return SECRET_PATTERN.matcher(text).replaceAll("$1=<redigido>");
    }

    private List<String> parseRoots(String raw) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isBlank()) roots.add(trimmed);
            }
        }
        roots.add("data/ml-lab/ingest");
        return new ArrayList<>(roots);
    }

    private String limitar(String text, int max) {
        if (text == null) return "";
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "...";
    }

    public record Snapshot(String summary, List<String> snippets) {}
}
