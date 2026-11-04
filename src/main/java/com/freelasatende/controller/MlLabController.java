package com.freelasatende.controller;

import com.freelasatende.model.dto.MlLabDtos;
import com.freelasatende.service.interfaceServico.MlLabService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ml-lab")
@RequiredArgsConstructor
public class MlLabController {

    private final MlLabService mlLabService;

    @GetMapping("/runs")
    public ResponseEntity<List<MlLabDtos.RunOverview>> listarRuns() {
        return ResponseEntity.ok(mlLabService.listarRuns());
    }

    @PostMapping("/runs")
    public ResponseEntity<MlLabDtos.RunOverview> criarRun(@RequestBody(required = false) MlLabDtos.CreateRunRequest request) {
        return ResponseEntity.ok(mlLabService.criarRun(request));
    }

    @PostMapping("/runs/{id}/start")
    public ResponseEntity<MlLabDtos.ActionResult> iniciarRun(@PathVariable Long id) {
        return ResponseEntity.ok(mlLabService.iniciarRun(id));
    }

    @PostMapping("/runs/{id}/pause")
    public ResponseEntity<MlLabDtos.ActionResult> pausarRun(@PathVariable Long id) {
        return ResponseEntity.ok(mlLabService.pausarRun(id));
    }

    @PostMapping("/runs/{id}/stop")
    public ResponseEntity<MlLabDtos.ActionResult> pararRun(@PathVariable Long id) {
        return ResponseEntity.ok(mlLabService.pararRun(id));
    }

    @GetMapping("/runs/{id}/overview")
    public ResponseEntity<MlLabDtos.RunOverview> obterOverview(@PathVariable Long id) {
        return ResponseEntity.ok(mlLabService.obterOverview(id));
    }

    @GetMapping("/runs/{id}/agents")
    public ResponseEntity<List<MlLabDtos.AgentView>> listarAgentes(@PathVariable Long id) {
        return ResponseEntity.ok(mlLabService.listarAgentes(id));
    }

    @PostMapping("/runs/{id}/agents/{agentKey}/chat")
    public ResponseEntity<MlLabDtos.ChatResponse> conversar(
        @PathVariable Long id,
        @PathVariable String agentKey,
        @RequestBody(required = false) MlLabDtos.ChatRequest request
    ) {
        return ResponseEntity.ok(mlLabService.conversar(id, agentKey, request));
    }

    @PostMapping("/candidates/{candidateId}/approve")
    public ResponseEntity<MlLabDtos.ActionResult> aprovarCandidata(@PathVariable Long candidateId) {
        return ResponseEntity.ok(mlLabService.aprovarCandidata(candidateId));
    }
}
