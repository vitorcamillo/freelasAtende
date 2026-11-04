package com.freelasatende.controller;

import com.freelasatende.model.entity.Aprendizado;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.service.interfaceServico.AprendizadoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controller para gerenciar aprendizados.
 */
@RestController
@RequestMapping("/api/aprendizados")
@RequiredArgsConstructor
public class AprendizadoController {

    private final AprendizadoService aprendizadoService;

    @GetMapping
    public ResponseEntity<List<Aprendizado>> listarAprendizados() {
        return ResponseEntity.ok(aprendizadoService.buscarHistorico());
    }

    @GetMapping("/regras")
    public ResponseEntity<List<String>> listarRegras() {
        return ResponseEntity.ok(aprendizadoService.buscarRegrasAprendidas());
    }

    @PostMapping
    public ResponseEntity<Aprendizado> criarAprendizado(
            @RequestBody CriarAprendizadoRequest request) {

        aprendizadoService.registrarDecisao(
            request.projetoId(),
            request.tituloProjeto(),
            request.foiAprovado(),
            request.motivo(),
            request.modoUsado(),
            request.feedback()
        );

        return ResponseEntity.ok().build();
    }

    public record CriarAprendizadoRequest(
        Long projetoId,
        String tituloProjeto,
        Boolean foiAprovado,
        String motivo,
        ModoSistema modoUsado,
        String feedback
    ) {}
}
