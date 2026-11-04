package com.freelasatende.service;

import com.freelasatende.policy.OperationalSafetyPolicy;
import com.freelasatende.service.impl.MlLabCorpusService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MlLabCorpusServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void corpusRedactsSecretsAndSkipsBrowserProfiles() throws Exception {
        Path source = tempDir.resolve("corpus");
        Files.createDirectories(source.resolve("profiles/Default"));
        Files.writeString(source.resolve("contexto.md"), """
            Regra segura de atendimento.
            api-key=segredo-local
            """);
        Files.writeString(source.resolve("profiles/Default/session.json"), "token=nao-deve-entrar");

        MlLabCorpusService service = new MlLabCorpusService(new OperationalSafetyPolicy());
        ReflectionTestUtils.setField(service, "sourceRoots", source.toString());
        ReflectionTestUtils.setField(service, "maxCorpusFiles", 20);
        ReflectionTestUtils.setField(service, "maxBytesPerFile", 1200);

        MlLabCorpusService.Snapshot snapshot = service.carregar();

        assertTrue(snapshot.summary().contains("Escrita externa bloqueada"));
        assertTrue(snapshot.snippets().stream().anyMatch(snippet -> snippet.contains("api-key=<redigido>")));
        assertFalse(snapshot.snippets().stream().anyMatch(snippet -> snippet.contains("segredo-local")));
        assertFalse(snapshot.snippets().stream().anyMatch(snippet -> snippet.contains("nao-deve-entrar")));
    }

    @Test
    void contextoRotatesCorpusSnippetsByCycle() {
        MlLabCorpusService service = new MlLabCorpusService(new OperationalSafetyPolicy());
        MlLabCorpusService.Snapshot snapshot = new MlLabCorpusService.Snapshot("ok", List.of("a", "b", "c"));

        assertTrue(service.contexto(snapshot, 1).startsWith("a"));
        assertTrue(service.contexto(snapshot, 2).startsWith("b"));
        assertTrue(service.contexto(new MlLabCorpusService.Snapshot("empty", List.of()), 1).isEmpty());
    }
}
