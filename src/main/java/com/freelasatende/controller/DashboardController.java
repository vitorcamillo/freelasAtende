package com.freelasatende.controller;

import com.freelasatende.model.dto.DashboardStatsDTO;
import com.freelasatende.model.dto.ControleOverviewDTO;
import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.service.impl.MarketplaceGateway;
import com.freelasatende.service.impl.SystemHealthTracker;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.ControleOperacionalService;
import com.freelasatende.service.interfaceServico.DashboardService;
import com.freelasatende.service.interfaceServico.HistoricoService;
import com.freelasatende.util.AtendimentoTextos;
import com.freelasatende.util.RedatorSegredos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Controller para o dashboard em tempo real.
 * SSE conectado de verdade — cada log dispara broadcast para todos os clientes.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class DashboardController {

    private static final long SSE_TIMEOUT = 30 * 60 * 1000L; // 30 minutos
    private static final int MAX_BUFFER_LOGS = 500;
    private static final Deque<DashboardLogEntry> LOG_BUFFER = new ConcurrentLinkedDeque<>();
    private static final CopyOnWriteArrayList<SseEmitter> EMITTERS = new CopyOnWriteArrayList<>();

    private final DashboardService dashboardService;
    private final HistoricoService historicoService;
    private final AvaliadorIaService avaliadorIaService;
    private final SystemHealthTracker healthTracker;
    private final MarketplaceGateway marketplaceGateway;
    private final ControleOperacionalService controleOperacionalService;

    /**
     * Pagina principal do dashboard.
     */
    @GetMapping("/dashboard")
    public String getDashboard(Model model) {
        model.addAttribute("abordagemInicial", AtendimentoTextos.ABORDAGEM_INICIAL);
        return "dashboard";
    }

    /**
     * Estatisticas JSON para o dashboard.
     */
    @GetMapping("/api/dashboard/stats")
    @ResponseBody
    public ResponseEntity<DashboardStatsDTO> getStats() {
        return ResponseEntity.ok(dashboardService.getStats());
    }

    /**
     * Lista de projetos recentes processados no dia.
     */
    @GetMapping("/api/dashboard/projetos")
    @ResponseBody
    public ResponseEntity<List<Projeto>> getProjetosRecentes(
            @RequestParam(defaultValue = "20") int limite) {
        return ResponseEntity.ok(dashboardService.getProjetosRecentes(limite));
    }

    /**
     * Lista de projetos aprovados no dia.
     */
    @GetMapping("/api/dashboard/aprovados")
    @ResponseBody
    public ResponseEntity<List<Projeto>> getProjetosAprovados(
            @RequestParam(defaultValue = "50") int limite) {
        return ResponseEntity.ok(dashboardService.getProjetosAprovados(limite));
    }

    /**
     * Lista de projetos recusados no dia.
     */
    @GetMapping("/api/dashboard/recusados")
    @ResponseBody
    public ResponseEntity<List<Projeto>> getProjetosRecusados(
            @RequestParam(defaultValue = "50") int limite) {
        return ResponseEntity.ok(dashboardService.getProjetosRecusados(limite));
    }

    /**
     * Lista as conversas na inbox de todos os marketplaces ativos.
     */
    @GetMapping("/api/dashboard/conversas")
    @ResponseBody
    public ResponseEntity<List<com.freelasatende.model.dto.ConversaResumo>> getConversasInbox() {
        List<com.freelasatende.model.dto.ConversaResumo> conversas = marketplaceGateway.listarConversasInbox();
        log.info("[DASHBOARD] Endpoint /conversas retornando {} conversas", conversas.size());
        return ResponseEntity.ok(conversas);
    }

    /**
     * Lista as mensagens de uma conversa específica.
     */
    @GetMapping("/api/dashboard/conversas/{id}/mensagens")
    @ResponseBody
    public ResponseEntity<List<com.freelasatende.model.dto.MensagemConversa>> getMensagensConversa(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean visualizar) {
        return ResponseEntity.ok(marketplaceGateway.listarMensagensConversa(id, visualizar));
    }

    /**
     * Stream de logs em tempo real via SSE.
     * O cliente se inscreve e recebe logs conforme vao chegando.
     */
    @GetMapping(value = "/api/dashboard/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter streamLogs() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        EMITTERS.add(emitter);

        emitter.onCompletion(() -> EMITTERS.remove(emitter));
        emitter.onTimeout(() -> {
            EMITTERS.remove(emitter);
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // Conexao SSE ja foi encerrada pelo cliente/servidor.
            }
        });
        emitter.onError(e -> EMITTERS.remove(emitter));

        // Envia evento inicial de conexao
        try {
            emitter.send(SseEmitter.event()
                .name("connected")
                .data("Dashboard conectado"));
        } catch (Exception e) {
            EMITTERS.remove(emitter);
            log.error("Erro ao enviar evento inicial SSE", e);
        }

        log.info("Novo subscriber SSE conectado. Total: {}", EMITTERS.size());

        return emitter;
    }

    /**
     * Enviar log para o buffer e broadcast via SSE para todos os subscribers.
     * Pode ser chamado por qualquer servico do sistema.
     */
    public static void enviarLog(String tipo, String mensagem) {
        LocalDateTime timestamp = LocalDateTime.now();
        String tipoSafe = tipo != null ? tipo : "INFO";
        // Mascara segredos NA ENTRADA: o buffer serve /api/dashboard/logs e o
        // broadcast alimenta /logs/stream — nenhum dos dois pode repassar segredo.
        String mensagemSafe = RedatorSegredos.mascarar(mensagem != null ? mensagem : "");

        DashboardLogEntry entry = new DashboardLogEntry(timestamp, tipoSafe, mensagemSafe);
        LOG_BUFFER.addFirst(entry);
        while (LOG_BUFFER.size() > MAX_BUFFER_LOGS) {
            LOG_BUFFER.pollLast();
        }

        // Broadcast via SSE para todos os clientes conectados
        broadcastSse(entry);

        if ("WARN".equalsIgnoreCase(tipoSafe) || "ERROR".equalsIgnoreCase(tipoSafe)) {
            log.info("LOG: {} - {}", tipoSafe, mensagemSafe);
        } else {
            log.debug("LOG: {} - {}", tipoSafe, mensagemSafe);
        }
    }

    /**
     * Envia log entry para todos os emitters SSE conectados.
     */
    private static void broadcastSse(DashboardLogEntry entry) {
        if (EMITTERS.isEmpty()) {
            return;
        }

        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("timestamp", entry.timestamp().toString());
        payload.put("tipo", entry.tipo());
        payload.put("mensagem", entry.mensagem());

        for (SseEmitter emitter : EMITTERS) {
            try {
                emitter.send(SseEmitter.event()
                    .name("log")
                    .data(payload));
            } catch (Exception e) {
                EMITTERS.remove(emitter);
            }
        }
    }

    /**
     * Logs recentes (para polling inicial).
     */
    @GetMapping("/api/dashboard/logs")
    @ResponseBody
    public ResponseEntity<List<Map<String, String>>> getLogs(
            @RequestParam(defaultValue = "50") int limite) {
        int limiteNormalizado = Math.max(1, Math.min(limite, 200));

        List<DashboardLogEntry> logsHistorico = historicoService.buscarUltimas(limiteNormalizado).stream()
            .map(this::mapearInteracaoParaLog)
            .collect(Collectors.toCollection(ArrayList::new));

        List<DashboardLogEntry> logsMemoria = new ArrayList<>(LOG_BUFFER);
        logsHistorico.addAll(logsMemoria);

        List<Map<String, String>> resposta = logsHistorico.stream()
            .sorted(Comparator.comparing(DashboardLogEntry::timestamp).reversed())
            .limit(limiteNormalizado)
            .map(this::mapearLogParaPayload)
            .collect(Collectors.toList());

        return ResponseEntity.ok(resposta);
    }

    /**
     * Estatisticas resumidas para widgets.
     */
    @GetMapping("/api/dashboard/resumo")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getResumo() {
        DashboardStatsDTO stats = dashboardService.getStats();
        return ResponseEntity.ok(Map.of(
            "stats", stats,
            "projetosRecentes", dashboardService.getProjetosRecentes(5),
            "timestamp", java.time.LocalDateTime.now().toString()
        ));
    }

    /**
     * Diagnostico detalhado da memoria da IA para observabilidade no dashboard.
     */
    @GetMapping("/api/dashboard/ia/memoria")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getIaMemoria() {
        return ResponseEntity.ok(avaliadorIaService.obterDiagnosticoMemoriaIa());
    }

    /**
     * Salva regras consolidadas editadas manualmente pelo usuário no dashboard.
     */
    @org.springframework.web.bind.annotation.PostMapping("/api/dashboard/ia/memoria/regras")
    @ResponseBody
    public ResponseEntity<Map<String, String>> salvarRegrasIa(@org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        try {
            String novasRegras = body.getOrDefault("regras", "");
            avaliadorIaService.salvarRegrasConsolidadas(novasRegras);
            return ResponseEntity.ok(Map.of("status", "ok", "mensagem", "Regras salvas com sucesso!"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("status", "erro", "mensagem", e.getMessage()));
        }
    }

    /**
     * Saúde completa do sistema — dados reais de cada subsistema.
     */
    @GetMapping("/api/dashboard/health")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getHealth() {
        return ResponseEntity.ok(healthTracker.getHealthCompleto());
    }

    /**
     * Resumo operacional seguro para smoke tests e painel do operador.
     */
    @GetMapping("/api/dashboard/ops")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getOps() {
        ControleOverviewDTO overview = controleOperacionalService.obterOverview();
        Map<String, Object> payload = new LinkedHashMap<>();

        Map<String, Object> escrita = new LinkedHashMap<>();
        escrita.put("realHabilitada", overview.isEscritaRealHabilitada());
        escrita.put("simulacaoHabilitada", overview.isSimulacaoEnvioHabilitada());
        escrita.put("totalEnviosReais", overview.getTotalEnvios());
        escrita.put("totalEnviosSimulados", overview.getTotalEnviosSimulados());
        escrita.put("travaAtiva", !overview.isEscritaRealHabilitada());

        Map<String, Object> fila = new LinkedHashMap<>();
        fila.put("pendencias", overview.getTotalPendencias());
        fila.put("bloqueios", overview.getTotalBloqueios());
        fila.put("errosHoje", overview.getErrosHoje());
        fila.put("sessaoValida", overview.isSessaoValida());

        payload.put("modoSeguro", !overview.isEscritaRealHabilitada());
        payload.put("podeOperar", overview.isPodeOperar());
        payload.put("motivoOperacao", overview.getMotivoOperacao());
        payload.put("escrita", escrita);
        payload.put("fila", fila);
        payload.put("auditoriaInbox", overview.getAuditoriaInboxResumo());
        payload.put("alertas", montarAlertasOperacionais(overview));
        payload.put("timestamp", LocalDateTime.now().toString());

        return ResponseEntity.ok(payload);
    }

    /**
     * Erros recentes para exibição no dashboard.
     */
    @GetMapping("/api/dashboard/errors/recent")
    @ResponseBody
    public ResponseEntity<List<Map<String, String>>> getErrosRecentes(
            @RequestParam(defaultValue = "20") int limite) {
        // Copias mascaradas: nao altera os registros do tracker (podem ser imutaveis ou compartilhados).
        List<Map<String, String>> erros = new java.util.ArrayList<>();
        for (Map<String, String> erro : healthTracker.getErrosRecentes(limite)) {
            Map<String, String> copia = new java.util.LinkedHashMap<>(erro);
            String mensagem = copia.get("mensagem");
            if (mensagem != null) {
                copia.put("mensagem", RedatorSegredos.mascarar(mensagem));
            }
            erros.add(copia);
        }
        return ResponseEntity.ok(erros);
    }

    private DashboardLogEntry mapearInteracaoParaLog(Interacao interacao) {
        String tipo = classificarTipo(interacao != null ? interacao.getTipo().name() : null);
        String mensagem = interacao != null
            ? RedatorSegredos.mascarar(String.format(
                "%s | Projeto: %s | %s",
                interacao.getTipo().name(),
                interacao.getProjetoTitulo() != null ? interacao.getProjetoTitulo() : "N/A",
                interacao.getConteudo() != null ? interacao.getConteudo() : ""
            ))
            : "";
        LocalDateTime data = interacao != null && interacao.getData() != null
            ? interacao.getData()
            : LocalDateTime.now();
        return new DashboardLogEntry(data, tipo, mensagem);
    }

    private Map<String, String> mapearLogParaPayload(DashboardLogEntry entry) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("timestamp", entry.timestamp().toString());
        payload.put("tipo", entry.tipo());
        payload.put("mensagem", entry.mensagem());
        return payload;
    }

    private String classificarTipo(String tipoInteracao) {
        if (tipoInteracao == null) {
            return "INFO";
        }
        if (tipoInteracao.contains("ERRO")) {
            return "ERROR";
        }
        if (tipoInteracao.contains("LIMITE") || tipoInteracao.contains("TIMEOUT")) {
            return "WARN";
        }
        return "INFO";
    }

    private List<Map<String, String>> montarAlertasOperacionais(ControleOverviewDTO overview) {
        List<Map<String, String>> alertas = new ArrayList<>();
        if (overview.isEscritaRealHabilitada()) {
            alertas.add(alerta("CRITICO", "Escrita real habilitada", "Qualquer envio real exige revisao operacional explicita."));
        } else {
            alertas.add(alerta("OK", "Trava de escrita ativa", "Nenhum envio real e permitido com real.write.enabled=false."));
        }
        if (!overview.isSessaoValida()) {
            alertas.add(alerta("WARN", "Sessao 99Freelas invalida", "Leitura real pode falhar ate os cookies serem atualizados."));
        }
        if (overview.getErrosHoje() > 0) {
            alertas.add(alerta("WARN", "Erros registrados hoje", "Revise /api/dashboard/errors/recent antes de operar."));
        }
        if (overview.getTotalEnvios() > 0 && !overview.isEscritaRealHabilitada()) {
            alertas.add(alerta("CRITICO", "Envio real registrado com trava ativa", "Auditar imediatamente o caminho de envio."));
        }

        Map<String, Object> auditoria = overview.getAuditoriaInboxResumo();
        if (auditoria == null || !Boolean.TRUE.equals(auditoria.get("persistida"))) {
            alertas.add(alerta("INFO", "Auditoria de inbox pendente", "Execute Auditar leitura para registrar evidencia read-only."));
        } else if (vazamentoContexto(auditoria) > 0) {
            alertas.add(alerta("CRITICO", "Possivel vazamento de contexto", "Revise a auditoria persistida antes de aprovar qualquer acao."));
        }
        return alertas;
    }

    private Map<String, String> alerta(String nivel, String titulo, String mensagem) {
        Map<String, String> alerta = new LinkedHashMap<>();
        alerta.put("nivel", nivel);
        alerta.put("titulo", titulo);
        alerta.put("mensagem", mensagem);
        return alerta;
    }

    private long numero(Object valor) {
        if (valor instanceof Number number) {
            return number.longValue();
        }
        if (valor == null) {
            return 0L;
        }
        try {
            return Long.parseLong(valor.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private long vazamentoContexto(Map<String, Object> auditoria) {
        Object painel = auditoria.get("painelCenarios");
        if (painel instanceof Map<?, ?> painelMap) {
            return numero(painelMap.get("vazouContexto"));
        }
        return numero(auditoria.get("vazouContexto"));
    }

    private record DashboardLogEntry(LocalDateTime timestamp, String tipo, String mensagem) {}
}
