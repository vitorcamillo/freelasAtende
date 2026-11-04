package com.freelasatende.controller;

import com.freelasatende.model.entity.Aprendizado;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.scheduler.PollingScheduler;
import com.freelasatende.service.interfaceServico.AprendizadoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controller para configuração do modo do sistema.
 */
@RestController
@RequestMapping("/api/modos")
@RequiredArgsConstructor
public class ModoController {

    private final PollingScheduler pollingScheduler;
    private final AprendizadoService aprendizadoService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getModo() {
        return ResponseEntity.ok(Map.of(
            "modo", pollingScheduler.getModoAtual(),
            "descricao", getDescricaoModo(pollingScheduler.getModoAtual()),
            "pausado", pollingScheduler.isPausado(),
            "autoAtivo", pollingScheduler.isAutoAtivo()
        ));
    }

    @PutMapping
    public ResponseEntity<Map<String, String>> setModo(@RequestParam ModoSistema modo) {
        pollingScheduler.alterarModo(modo);
        return ResponseEntity.ok(Map.of(
            "mensagem", "Modo alterado para " + modo,
            "modo", modo.name()
        ));
    }

    @PostMapping("/pausar")
    public ResponseEntity<Map<String, Object>> pausar() {
        pollingScheduler.pausar();
        return ResponseEntity.ok(Map.of(
            "mensagem", "Sistema PAUSADO",
            "pausado", true
        ));
    }

    @PostMapping("/retomar")
    public ResponseEntity<Map<String, Object>> retomar() {
        pollingScheduler.retomar();
        return ResponseEntity.ok(Map.of(
            "mensagem", "Sistema RETOMADO",
            "pausado", false
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
            "pausado", pollingScheduler.isPausado(),
            "autoAtivo", pollingScheduler.isAutoAtivo(),
            "modo", pollingScheduler.getModoAtual()
        ));
    }

    @PostMapping("/auto/ativar")
    public ResponseEntity<Map<String, String>> ativarAuto() {
        pollingScheduler.ativarAuto();
        return ResponseEntity.ok(Map.of(
            "mensagem", "Modo AUTO ativado - sistema ligará automaticamente"
        ));
    }

    @PostMapping("/auto/desativar")
    public ResponseEntity<Map<String, String>> desativarAuto() {
        pollingScheduler.desativarAuto();
        return ResponseEntity.ok(Map.of(
            "mensagem", "Modo AUTO desativado"
        ));
    }

    private String getDescricaoModo(ModoSistema modo) {
        return switch (modo) {
            case LIVRE -> "Liberado: pode enviar abordagem inicial curta quando escrita real estiver ligada";
            case REGULAR -> "Opera com revisao quando ha duvidas e pode enviar abordagem inicial curta";
            case ENSINAMENTO -> "Ensino: pode enviar abordagem inicial curta; outras decisoes seguem em revisao";
        };
    }
}
