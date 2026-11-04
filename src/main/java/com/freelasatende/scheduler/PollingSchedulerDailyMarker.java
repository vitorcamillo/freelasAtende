package com.freelasatende.scheduler;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

@Slf4j
final class PollingSchedulerDailyMarker {

    private final String arquivo;

    PollingSchedulerDailyMarker(String arquivo) {
        this.arquivo = arquivo;
    }

    boolean jaMarcadoHoje() {
        try {
            Path path = Path.of(arquivo);
            if (!Files.isRegularFile(path)) {
                return false;
            }
            String valor = Files.readString(path, StandardCharsets.UTF_8).trim();
            return LocalDate.now().toString().equals(valor);
        } catch (Exception e) {
            log.debug("Nao foi possivel ler controle do diario operacional: {}", e.getMessage());
            return false;
        }
    }

    void marcarHoje() {
        try {
            Path path = Path.of(arquivo);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, LocalDate.now().toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("Nao foi possivel gravar controle do diario operacional: {}", e.getMessage());
        }
    }
}
