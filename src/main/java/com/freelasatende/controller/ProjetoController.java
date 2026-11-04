package com.freelasatende.controller;

import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.StatusProjeto;
import com.freelasatende.repository.ProjetoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller para gerenciamento de projetos.
 */
@RestController
@RequestMapping("/api/projetos")
@RequiredArgsConstructor
public class ProjetoController {

    private final ProjetoRepository projetoRepository;

    @GetMapping
    public ResponseEntity<List<Projeto>> listarProjetos(
            @RequestParam(required = false) StatusProjeto status) {

        List<Projeto> projetos;
        if (status != null) {
            projetos = projetoRepository.findByStatus(status);
        } else {
            projetos = projetoRepository.findAll();
        }

        return ResponseEntity.ok(projetos);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Projeto> buscarProjeto(@PathVariable Long id) {
        return projetoRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/nao-processados")
    public ResponseEntity<List<Projeto>> projetosNaoProcessados() {
        return ResponseEntity.ok(
            projetoRepository.findByFoiProcessadoFalseOrderByDataCriacaoDesc()
        );
    }

    @PostMapping("/{id}/ignorar")
    public ResponseEntity<Void> ignorarProjeto(@PathVariable Long id) {
        return projetoRepository.findById(id)
            .map(projeto -> {
                projeto.setStatus(StatusProjeto.IGNORADO);
                projeto.setFoiProcessado(true);
                projeto.setDataProcessamento(java.time.LocalDateTime.now());
                projetoRepository.save(projeto);
                return ResponseEntity.ok().<Void>build();
            })
            .orElse(ResponseEntity.notFound().build());
    }
}
