package com.freelasatende.service.impl;

import com.freelasatende.config.TelegramConfig;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.MensagemClienteRegistro;
import com.freelasatende.scheduler.PollingScheduler;
import com.freelasatende.service.interfaceServico.AutoAtendimentoService;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.ControleOperacionalService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.HistoricoService;
import com.freelasatende.service.interfaceServico.IaProvider;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.freelasatende.service.impl.TelegramActionMessageFormatter.*;

/**
 * Handler de comandos recebidos via Telegram.
 * Faz polling periódico e processa comandos do usuário.
 *
 * Comandos suportados:
 *   /status  — Status do sistema
 *   /modo    — Alterar modo (LIVRE/REGULAR/ENSINAMENTO)
 *   /parar   — Pausar monitoramento
 *   /retomar — Retomar monitoramento
 *   /stats   — Estatísticas detalhadas
 *   /cookies — Atualizar cookies manualmente
 *   /ia      — Info sobre provider de IA
 *   /help    — Lista de comandos
 *
 * Aprovação/reprovação de ações pendentes é feita via botões.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramCommandHandler {

    private static final Path ARQUIVO_REGRAS_OPERACIONAIS = Path.of("data", "regras-comportamento.md");
    private static final Path ARQUIVO_MANUAL_VOZ = Path.of("data", "manual-voz-freelancer.md");

    private final TelegramServiceImpl telegramService;
    private final TelegramConfig telegramConfig;
    private final PollingScheduler pollingScheduler;
    private final AutoAtendimentoService autoAtendimentoService;
    private final GerenciadorConexoesService gerenciadorConexoes;
    private final HistoricoService historicoService;
    private final AutenticacaoService autenticacaoService;
    private final IaProvider iaProvider;
    private final AvaliadorIaService avaliadorIaService;
    private final ControleOperacionalService controleOperacionalService;
    private final MarketplaceGateway marketplaceGateway;
    private final MfaCodeInbox mfaCodeInbox;

    private final AtomicLong ultimoUpdateId = new AtomicLong(0);
    private final AtomicReference<EntradaBotaoPendente> entradaBotaoPendente = new AtomicReference<>();

    @Value("${scheduling.enabled:true}")
    private boolean schedulingEnabled;

    @Value("${telegram.operator-chat.ia-enabled:true}")
    private boolean operatorChatIaEnabled;

    @Value("${telegram.update-offset-file:data/telegram-update-offset.txt}")
    private String arquivoOffsetTelegram = "data/telegram-update-offset.txt";

    enum TipoEntradaBotao { REESCREVER, ENSINAR }

    record EntradaBotaoPendente(Long acaoId, TipoEntradaBotao tipo) {}

    @Scheduled(fixedRate = 10000) // Verifica a cada 10 segundos
    public void verificarComandosAgendado() {
        if (!schedulingEnabled) {
            return;
        }
        verificarComandos();
    }

    @PostConstruct
    void carregarOffsetPersistido() {
        try {
            Path path = Path.of(arquivoOffsetTelegram);
            if (!Files.isRegularFile(path)) {
                return;
            }
            String valor = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (valor.isBlank()) {
                return;
            }
            long offset = Long.parseLong(valor);
            if (offset >= 0) {
                ultimoUpdateId.set(offset);
                log.info("[TELEGRAM] Offset restaurado do arquivo: {}", offset);
            }
        } catch (Exception e) {
            log.warn("[TELEGRAM] Não foi possível restaurar offset persistido: {}", e.getMessage());
        }
    }

    public void verificarComandos() {
        try {
            log.trace("[TELEGRAM] Polling updates a partir do offset {}", ultimoUpdateId.get());
            JsonNode updates = telegramService.obterUpdates(ultimoUpdateId.get());
            if (updates == null) {
                log.debug("[TELEGRAM] obterUpdates retornou null");
                return;
            }

            JsonNode result = updates.path("result");
            if (!result.isArray()) {
                log.debug("[TELEGRAM] result não é array");
                return;
            }

            if (result.isEmpty()) {
                log.trace("[TELEGRAM] Nenhum update novo");
                return;
            }

            log.debug("[TELEGRAM] {} updates encontrados", result.size());

            for (JsonNode update : result) {
                long updateId = update.path("update_id").asLong();
                try {
                    JsonNode callback = update.path("callback_query");
                    if (!callback.isMissingNode()) {
                        String chatIdCallback = callback.path("message").path("chat").path("id").asText();
                        String callbackData = callback.path("data").asText();
                        log.debug("[TELEGRAM] Callback recebido - chatId: {}, data: {}", chatIdCallback, callbackData);
                        if (chatIdCallback.equals(telegramConfig.getChatId())) {
                            processarCallback(callback);
                        } else {
                            log.warn("[TELEGRAM] Callback de chat não autorizado: {}", chatIdCallback);
                        }
                        marcarUpdateProcessado(updateId);
                        continue;
                    }

                    JsonNode message = update.path("message");
                    if (message.isMissingNode()) {
                        marcarUpdateProcessado(updateId);
                        continue;
                    }

                    String chatId = message.path("chat").path("id").asText();
                    String texto = message.path("text").asText("");

                    // Verificar se é do chat autorizado
                    if (!chatId.equals(telegramConfig.getChatId())) {
                        log.warn("Mensagem de chat não autorizado: {}", chatId);
                        marcarUpdateProcessado(updateId);
                        continue;
                    }

                    // Durante login assistido, o código MFA tem prioridade: evita que um
                    // número (ex.: "810061") seja interpretado como número de pedido.
                    if (mfaCodeInbox.aguardandoCodigo() && mfaCodeInbox.ofertarCodigo(texto)) {
                        log.info("[TELEGRAM] Código MFA recebido e roteado ao login assistido");
                        marcarUpdateProcessado(updateId);
                        continue;
                    }

                    if (texto.startsWith("/")) {
                        processarComando(texto);
                    } else if (!texto.isBlank()) {
                        processarMensagemLivre(texto);
                    }
                    marcarUpdateProcessado(updateId);
                } catch (Exception e) {
                    log.warn("[TELEGRAM] Falha ao processar update {}. Offset preservado para retry: {}",
                        updateId,
                        e.getMessage(),
                        e);
                    return;
                }
            }
        } catch (Exception e) {
            log.debug("Erro ao verificar comandos Telegram: {}", e.getMessage());
        }
    }

    private void marcarUpdateProcessado(long updateId) {
        long novoOffset = ultimoUpdateId.updateAndGet(atual -> Math.max(atual, updateId + 1));
        persistirOffset(novoOffset);
    }

    private void persistirOffset(long offset) {
        try {
            Path path = Path.of(arquivoOffsetTelegram);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                path,
                Long.toString(offset),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception e) {
            log.warn("[TELEGRAM] Não foi possível persistir offset {}: {}", offset, e.getMessage());
        }
    }

    public void processarComando(String comando) {
        String[] partes = comando.trim().split("\\s+", 3);
        String cmd = partes[0].toLowerCase();

        log.info("Comando Telegram recebido: {}", cmd);

        switch (cmd) {
            case "/status" -> enviarStatus();
            case "/modo" -> alterarModo(partes);
            case "/parar", "/pausar", "/desligar" -> pausarSistema();
            case "/retomar", "/ligar" -> retomarSistema();
            case "/stats" -> enviarEstatisticas();
            case "/cookies" -> atualizarCookies(partes);
            case "/ia" -> enviarInfoIa();
            case "/regras" -> enviarRegrasOperacionais();
            case "/duvidas", "/dúvidas" -> TelegramPendingActionCommandExecutor.enviarDuvidasOperacionais(
                partes,
                telegramService,
                controleOperacionalService
            );
            case "/memoria", "/memória" -> TelegramPendingActionCommandExecutor.enviarContextoOperacional(
                partes,
                telegramService,
                controleOperacionalService,
                avaliadorIaService,
                pollingScheduler,
                iaProvider
            );
            case "/saldo" -> enviarSaldo();
            case "/pendencias" -> TelegramPendingActionCommandExecutor.enviarPendencias(
                partes,
                telegramService,
                controleOperacionalService
            );
            case "/perguntas" -> TelegramPendingActionCommandExecutor.enviarPerguntas(
                partes,
                telegramService,
                controleOperacionalService
            );
            case "/bloqueios" -> enviarBloqueios();
            case "/acao" -> respostaAcaoSomentePorBotao();
            case "/conversa" -> enviarDiagnosticoConversa(partes);
            case "/falar" -> conversarComOperador(partes);
            case "/foco", "/limparfoco" -> respostaAcaoSomentePorBotao();
            case "/ensinar" -> respostaAcaoSomentePorBotao();
            case "/contexto" -> contextoOuEnsino(partes);
            case "/aprovar", "/recusar", "/reescrever" -> respostaAcaoSomentePorBotao();
            case "/limparmemoria" -> limparMemoria();
            case "/help", "/start" -> enviarAjuda();
            default -> telegramService.enviarNotificacao(
                "❓ Comando desconhecido: " + cmd + "\nUse /help para ver comandos");
        }
    }

    private void processarCallback(JsonNode callback) {
        TelegramCallbackProcessor.processar(
            callback,
            telegramService,
            controleOperacionalService,
            entradaBotaoPendente
        );
    }

    private void enviarStatus() {
        TelegramSystemCommandExecutor.enviarStatus(
            telegramService,
            gerenciadorConexoes,
            autenticacaoService,
            historicoService,
            pollingScheduler,
            autoAtendimentoService,
            iaProvider
        );
    }

    private void alterarModo(String[] partes) {
        TelegramSystemCommandExecutor.alterarModo(telegramService, pollingScheduler, partes);
    }

    private void pausarSistema() {
        pollingScheduler.pausar();
        telegramService.enviarNotificacao("⏸ Sistema PAUSADO\nUse /retomar para reativar");
    }

    private void retomarSistema() {
        pollingScheduler.retomar();
        telegramService.enviarNotificacao("▶️ Sistema RETOMADO\nMonitoramento ativo");
    }

    private void enviarEstatisticas() {
        TelegramSystemCommandExecutor.enviarEstatisticas(
            telegramService,
            historicoService,
            gerenciadorConexoes,
            autoAtendimentoService,
            iaProvider,
            pollingScheduler
        );
    }

    private void atualizarCookies(String[] partes) {
        TelegramSystemCommandExecutor.atualizarCookies(telegramService, autenticacaoService, partes);
    }

    private void enviarInfoIa() {
        TelegramSystemCommandExecutor.enviarInfoIa(telegramService, iaProvider);
    }

    private void respostaAcaoSomentePorBotao() {
        telegramService.enviarNotificacao(
            "Essa decisão é feita pelos botões da pendência.\n\n"
                + "Use Aprovar, Recusar, Reescrever, Ensinar, Focar ou Ver ação no próprio cartão."
        );
    }

    private void enviarAjuda() {
        telegramService.enviarNotificacao(TelegramSystemMessageFormatter.ajuda());
    }

    private void enviarSaldo() {
        String statusConexoes = gerenciadorConexoes.getStatusConexoes();
        telegramService.enviarNotificacao("💰 *Saldo de Conexões*\n\n" + statusConexoes);
    }

    private void limparMemoria() {
        telegramService.enviarNotificacao("⚙️ *Iniciando sumarização da IA*... Isso pode levar alguns segundos.");
        new Thread(() -> {
            autoAtendimentoService.otimizarMemoriaContexto();
        }).start();
    }

    private void enviarRegrasOperacionais() {
        Map<String, Object> memoria = TelegramOperationalContextHelper.diagnosticoMemoriaSeguro(avaliadorIaService);
        telegramService.enviarNotificacao(limitarMensagemTelegram(
            TelegramCommandMessageFormatter.regrasOperacionais(
                memoria,
                ARQUIVO_MANUAL_VOZ,
                ARQUIVO_REGRAS_OPERACIONAIS
            )
        ));
    }

    private void conversarComOperador(String[] partes) {
        String texto = juntarArgumentos(partes, 1);
        if (texto.isBlank()) {
            telegramService.enviarNotificacao(
                "Para conversar comigo, mande texto normal.\n"
                    + "Para decidir pendência, use os botões do cartão."
            );
            return;
        }
        TelegramFreeTextProcessor.responderConversaOperador(
            texto,
            telegramService,
            operatorChatIaEnabled,
            iaProvider,
            avaliadorIaService,
            controleOperacionalService,
            pollingScheduler
        );
    }

    private void contextoOuEnsino(String[] partes) {
        TelegramPendingActionCommandExecutor.enviarContextoOperacional(
            partes,
            telegramService,
            controleOperacionalService,
            avaliadorIaService,
            pollingScheduler,
            iaProvider
        );
    }

    private void enviarBloqueios() {
        List<MensagemClienteRegistro> bloqueios = controleOperacionalService.listarBloqueiosRecentes();
        if (bloqueios.isEmpty()) {
            telegramService.enviarNotificacao("Nenhum bloqueio recente do guardião.");
            return;
        }

        telegramService.enviarNotificacao(TelegramCommandMessageFormatter.bloqueios(bloqueios));
    }

    private void enviarDiagnosticoConversa(String[] partes) {
        if (partes.length < 2) {
            telegramService.enviarNotificacao("Uso: /conversa {id}");
            return;
        }
        Long id = parseLong(partes[1]);
        if (id == null) {
            telegramService.enviarNotificacao("ID inválido: " + partes[1]);
            return;
        }

        try {
            ConversaResumo conversa = marketplaceGateway.buscarConversaInbox(id)
                .orElse(ConversaResumo.builder().idConversa(id).build());
            List<MensagemConversa> mensagens = marketplaceGateway.listarMensagensConversa(id);
            telegramService.enviarNotificacao(limitarMensagemTelegram(
                TelegramCommandMessageFormatter.diagnosticoConversa(id, conversa, mensagens)
            ));
        } catch (Exception e) {
            telegramService.enviarNotificacao("Falha ao diagnosticar conversa " + id + ": " + e.getMessage());
        }
    }

    private void processarMensagemLivre(String texto) {
        TelegramFreeTextProcessor.processarMensagemLivre(
            texto,
            entradaBotaoPendente,
            controleOperacionalService,
            telegramService,
            operatorChatIaEnabled,
            iaProvider,
            avaliadorIaService,
            pollingScheduler
        );
    }

    private String juntarArgumentos(String[] partes, int inicio) {
        if (partes == null || partes.length <= inicio) {
            return "";
        }
        return String.join(" ", java.util.Arrays.copyOfRange(partes, inicio, partes.length)).trim();
    }

    private Long parseLong(String valor) {
        try {
            return Long.parseLong(valor);
        } catch (Exception e) {
            return null;
        }
    }

}
