package com.freelasatende.controller;

import com.freelasatende.model.dto.AcaoControleDTO;
import com.freelasatende.model.dto.ControleAcaoResultadoDTO;
import com.freelasatende.model.dto.ControleOverviewDTO;
import com.freelasatende.model.dto.EnsinoFocoDTO;
import com.freelasatende.model.dto.InboxAuditoriaDTO;
import com.freelasatende.model.dto.InboxConversaAuditoriaDTO;
import com.freelasatende.model.entity.MensagemClienteRegistro;
import com.freelasatende.scheduler.PollingScheduler;
import com.freelasatende.service.interfaceServico.ControleOperacionalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/controle")
@RequiredArgsConstructor
public class ControleController {

    private final ControleOperacionalService controleOperacionalService;
    private final PollingScheduler pollingScheduler;

    @GetMapping("/overview")
    public ResponseEntity<ControleOverviewDTO> overview() {
        return ResponseEntity.ok(controleOperacionalService.obterOverview());
    }

    @GetMapping("/acoes-pendentes")
    public ResponseEntity<List<AcaoControleDTO>> listarAcoesPendentes() {
        return ResponseEntity.ok(controleOperacionalService.listarAcoesPendentes());
    }

    @GetMapping("/acoes/{id}")
    public ResponseEntity<AcaoControleDTO> buscarAcao(@PathVariable Long id) {
        return controleOperacionalService.buscarAcao(id)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/acoes/{id}/aprovar")
    public ResponseEntity<ControleAcaoResultadoDTO> aprovar(
        @PathVariable Long id,
        @RequestBody(required = false) Map<String, String> body
    ) {
        return ResponseEntity.ok(controleOperacionalService.aprovarAcao(id, comentario(body)));
    }

    @PostMapping("/acoes/{id}/recusar")
    public ResponseEntity<ControleAcaoResultadoDTO> recusar(
        @PathVariable Long id,
        @RequestBody(required = false) Map<String, String> body
    ) {
        return ResponseEntity.ok(controleOperacionalService.recusarAcao(id, comentario(body)));
    }

    @PostMapping("/acoes/{id}/reescrever")
    public ResponseEntity<ControleAcaoResultadoDTO> reescrever(
        @PathVariable Long id,
        @RequestBody Map<String, String> body
    ) {
        String texto = body != null ? body.getOrDefault("texto", "") : "";
        String comentario = comentario(body);
        return ResponseEntity.ok(controleOperacionalService.reescreverAcao(id, texto, comentario));
    }

    @PostMapping("/acoes/{id}/contexto")
    public ResponseEntity<ControleAcaoResultadoDTO> registrarContextoEnsino(
        @PathVariable Long id,
        @RequestBody(required = false) Map<String, String> body
    ) {
        String orientacao = textoEnsino(body);
        return ResponseEntity.ok(controleOperacionalService.registrarContextoEnsino(id, orientacao));
    }

    @PostMapping("/acoes/{id}/responder-duvida")
    public ResponseEntity<ControleAcaoResultadoDTO> responderDuvidaIa(
        @PathVariable Long id,
        @RequestBody(required = false) Map<String, String> body
    ) {
        String resposta = body != null ? body.getOrDefault("resposta", "") : "";
        String regra = body != null ? body.getOrDefault("regra", "") : "";
        return ResponseEntity.ok(controleOperacionalService.responderDuvidaIa(id, resposta, regra));
    }

    @PostMapping("/sincronizar-inbox")
    public ResponseEntity<ControleAcaoResultadoDTO> sincronizarInboxParaEnsino() {
        return ResponseEntity.ok(controleOperacionalService.sincronizarInboxParaEnsino());
    }

    @PostMapping("/reset-operacional")
    public ResponseEntity<ControleAcaoResultadoDTO> resetarRodadaOperacional() {
        return ResponseEntity.ok(controleOperacionalService.resetarRodadaOperacional());
    }

    @PostMapping("/auditar-inbox")
    public ResponseEntity<InboxAuditoriaDTO> auditarInboxParaEnsino() {
        return ResponseEntity.ok(controleOperacionalService.auditarInboxEnsinoReadOnly());
    }

    @GetMapping("/auditoria-inbox")
    public ResponseEntity<InboxAuditoriaDTO> obterAuditoriaInbox() {
        return ResponseEntity.ok(controleOperacionalService.obterUltimaAuditoriaInbox());
    }

    @GetMapping("/auditoria-inbox/conversas")
    public ResponseEntity<List<InboxConversaAuditoriaDTO>> listarConversasAuditoriaInbox() {
        return ResponseEntity.ok(controleOperacionalService.listarConversasUltimaAuditoriaInbox());
    }

    @PostMapping("/sincronizar-projetos")
    public ResponseEntity<ControleAcaoResultadoDTO> sincronizarProjetosParaEnsino() {
        return ResponseEntity.ok(controleOperacionalService.sincronizarProjetosParaEnsino());
    }

    @PostMapping("/projetos/{id}/reprocessar")
    public ResponseEntity<ControleAcaoResultadoDTO> reprocessarProjetoIgnorado(@PathVariable Long id) {
        return ResponseEntity.ok(
            controleOperacionalService.reprocessarProjetoIgnorado(id, pollingScheduler.getModoAtual())
        );
    }

    @GetMapping("/bloqueios")
    public ResponseEntity<List<MensagemClienteRegistro>> listarBloqueios() {
        return ResponseEntity.ok(controleOperacionalService.listarBloqueiosRecentes());
    }

    @GetMapping("/foco-ensino")
    public ResponseEntity<EnsinoFocoDTO> obterFocoEnsino() {
        return ResponseEntity.ok(controleOperacionalService.obterFocoEnsino());
    }

    @PostMapping("/foco-ensino/acoes/{id}")
    public ResponseEntity<EnsinoFocoDTO> definirFocoEnsino(@PathVariable Long id) {
        return ResponseEntity.ok(controleOperacionalService.definirFocoEnsino(id));
    }

    @PostMapping("/foco-ensino/limpar")
    public ResponseEntity<EnsinoFocoDTO> limparFocoEnsino() {
        return ResponseEntity.ok(controleOperacionalService.limparFocoEnsino());
    }

    private String comentario(Map<String, String> body) {
        if (body == null) {
            return "";
        }
        String motivo = body.get("motivoAprendizado");
        if (motivo != null && !motivo.isBlank()) {
            return motivo;
        }
        String comentario = body.get("comentario");
        return comentario == null ? "" : comentario;
    }

    private String textoEnsino(Map<String, String> body) {
        if (body == null) {
            return "";
        }
        String texto = body.get("texto");
        if (texto != null && !texto.isBlank()) {
            return texto;
        }
        return comentario(body);
    }
}
