package com.freelasatende.scheduler;

import com.freelasatende.model.dto.AcaoControleDTO;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.service.interfaceServico.ControleOperacionalService;
import com.freelasatende.service.interfaceServico.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
final class PollingSchedulerOperatorNotifier {

    private final TelegramService telegramService;
    private final ControleOperacionalService controleOperacionalService;
    private final boolean diarioOperacionalEnabled;
    private final String arquivoDiarioOperacional;
    private final boolean checkinEnsinoEnabled;
    private final long checkinEnsinoIntervaloMinutos;
    private final String arquivoCheckinEnsino;
    private final boolean realWriteEnabled;
    private final boolean realWriteSimulateEnabled;
    private volatile long ultimoCheckinEnsinoMillis = 0;

    void notificarDiarioOperacional(int projetosLidos, long pendenciasAntes, long pendenciasDepois, ModoSistema modo) {
        if (!diarioOperacionalEnabled || pendenciasAntes < 0 || pendenciasDepois < 0) {
            return;
        }
        long pendenciasNovas = Math.max(0, pendenciasDepois - pendenciasAntes);
        if (pendenciasNovas == 0) {
            return;
        }
        PollingSchedulerDailyMarker marker = new PollingSchedulerDailyMarker(arquivoDiarioOperacional);
        if (marker.jaMarcadoHoje()) {
            log.debug("Diario operacional ja enviado hoje; pulando notificacao repetida.");
            return;
        }

        String travaCliente = realWriteEnabled
            ? (realWriteSimulateEnabled ? "SIMULACAO" : "ENVIO REAL HABILITADO")
            : "BLOQUEADA";

        boolean enviado = enviarNotificacaoSegura(
            "🧑‍💻 *Diário do junior*\n\n"
                + "Li " + projetosLidos + " projeto(s) e conferi a inbox.\n"
                + "Modo: " + modo + "\n"
                + "Pendências novas: " + pendenciasNovas + "\n"
                + "Pendências abertas: " + pendenciasDepois + "\n"
                + "Escrita ao cliente: " + travaCliente + "\n\n"
                + "O que preciso de você: responder os cards novos, ensinar regra ou reescrever antes de qualquer envio.",
            "diario operacional"
        );
        if (enviado) {
            marker.marcarHoje();
        }
    }

    void notificarCheckinEnsino(int projetosLidos, long pendenciasAntes, long pendenciasDepois, ModoSistema modo) {
        if (!checkinEnsinoEnabled
            || modo != ModoSistema.ENSINAMENTO
            || pendenciasAntes < 0
            || pendenciasDepois <= 0
            || pendenciasDepois > pendenciasAntes
            || !checkinEnsinoPermitido()) {
            return;
        }

        AcaoControleDTO foco = escolherAcaoParaCheckin();
        if (foco == null) {
            return;
        }
        if (checkinJaEnviadoParaFoco(foco)) {
            log.debug("Check-in de ensino ja enviado para acao {}; pulando repeticao.", foco.getId());
            return;
        }

        if (enviarCheckinEnsinoComBotoes(
            PollingSchedulerMessageFormatter.mensagemCheckinEnsino(projetosLidos, pendenciasDepois, foco),
            foco
        )) {
            ultimoCheckinEnsinoMillis = System.currentTimeMillis();
            marcarCheckinEnviado(foco);
        }
    }

    private boolean checkinEnsinoPermitido() {
        if (ultimoCheckinEnsinoMillis == 0) {
            return true;
        }
        long intervaloMillis = Math.max(1, checkinEnsinoIntervaloMinutos) * 60_000;
        return System.currentTimeMillis() - ultimoCheckinEnsinoMillis >= intervaloMillis;
    }

    private AcaoControleDTO escolherAcaoParaCheckin() {
        try {
            List<AcaoControleDTO> pendencias = controleOperacionalService.listarAcoesPendentes();
            if (pendencias == null || pendencias.isEmpty()) {
                return null;
            }
            return pendencias.stream()
                .filter(AcaoControleDTO::isFocoEnsinoAtual)
                .findFirst()
                .orElse(pendencias.get(0));
        } catch (Exception e) {
            log.debug("Nao foi possivel montar check-in de ensino: {}", e.getMessage());
            return null;
        }
    }

    private boolean checkinJaEnviadoParaFoco(AcaoControleDTO foco) {
        if (foco == null || foco.getId() == null || arquivoCheckinEnsino == null || arquivoCheckinEnsino.isBlank()) {
            return false;
        }
        try {
            Path arquivo = Path.of(arquivoCheckinEnsino);
            if (!Files.exists(arquivo)) {
                return false;
            }
            return Files.readString(arquivo, StandardCharsets.UTF_8).trim().equals(String.valueOf(foco.getId()));
        } catch (Exception e) {
            log.debug("Nao foi possivel ler marcador de check-in de ensino: {}", e.getMessage());
            return false;
        }
    }

    private void marcarCheckinEnviado(AcaoControleDTO foco) {
        if (foco == null || foco.getId() == null || arquivoCheckinEnsino == null || arquivoCheckinEnsino.isBlank()) {
            return;
        }
        try {
            Path arquivo = Path.of(arquivoCheckinEnsino);
            Path parent = arquivo.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(arquivo, String.valueOf(foco.getId()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("Nao foi possivel salvar marcador de check-in de ensino: {}", e.getMessage());
        }
    }

    private boolean enviarCheckinEnsinoComBotoes(String mensagem, AcaoControleDTO foco) {
        try {
            if (foco != null && foco.getId() != null) {
                telegramService.enviarAcaoPendenteComBotoes(mensagem, foco.getId());
            } else {
                telegramService.enviarNotificacao(mensagem);
            }
            return true;
        } catch (Exception e) {
            log.warn("Falha ao enviar check-in de ensino pelo Telegram: {}", e.getMessage());
            return false;
        }
    }

    private boolean enviarNotificacaoSegura(String mensagem, String contexto) {
        try {
            telegramService.enviarNotificacao(mensagem);
            return true;
        } catch (Exception e) {
            log.warn("Falha ao enviar {} pelo Telegram: {}", contexto, e.getMessage());
            return false;
        }
    }
}
