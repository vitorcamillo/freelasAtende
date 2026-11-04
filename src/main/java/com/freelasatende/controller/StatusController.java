package com.freelasatende.controller;

import com.freelasatende.config.TelegramConfig;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.MensagemClienteRegistro;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.MensagemClienteRegistroRepository;
import com.freelasatende.service.impl.LoginJavaDriverless;
import com.freelasatende.service.impl.LoginAssistidoAutoTrigger;
import com.freelasatende.service.impl.MarketplaceGateway;
import com.freelasatende.service.impl.UpworkLoginDriverless;
import com.freelasatende.service.impl.FreelancerLoginDriverless;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.TelegramService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controller para status e diagnosticos do sistema.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StatusController {

    private final MarketplaceGateway marketplaceGateway;
    private final TelegramService telegramService;
    private final GerenciadorConexoesService gerenciadorConexoes;
    private final TelegramConfig telegramConfig;
    private final AutenticacaoService autenticacaoService;
    private final AcaoPendenteRepository acaoPendenteRepository;
    private final MensagemClienteRegistroRepository mensagemClienteRegistroRepository;
    private final ObjectProvider<LoginJavaDriverless> loginJavaDriverless;
    private final LoginAssistidoAutoTrigger loginAssistidoAutoTrigger;
    private final ObjectProvider<UpworkLoginDriverless> upworkLoginDriverless;
    private final ObjectProvider<FreelancerLoginDriverless> freelancerLoginDriverless;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Boolean> marketplaces = marketplaceGateway.verificarLogins();
        boolean logado99 = Boolean.TRUE.equals(marketplaces.get("99FREELAS"));

        return ResponseEntity.ok(Map.of(
            "sistema", "FreelasAtende",
            "status99Freelas", logado99 ? "LOGADO" : "NAO_LOGADO",
            "marketplaces", marketplaces,
            "conexoes", gerenciadorConexoes.getStatusConexoes(),
            "javaVersion", System.getProperty("java.version")
        ));
    }

    @PostMapping("/telegram/test")
    public ResponseEntity<Map<String, String>> testarTelegram() {
        try {
            telegramService.enviarNotificacao(
                "✅ *Teste FreelasAtende*\n\n"
                + "Conexao com Telegram funcionando!\n"
                + "Você receberá notificacoes sobre projetos."
            );
            return ResponseEntity.ok(Map.of("mensagem", "Mensagem de teste enviada"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("erro", e.getMessage()));
        }
    }

    @PostMapping("/auth/login")
    public ResponseEntity<Map<String, Object>> forcarLogin() {
        try {
            autenticacaoService.renovarSessaoSeNecessario();
            boolean valida = autenticacaoService.verificarSessaoValida();
            return ResponseEntity.ok(Map.of(
                "mensagem", valida ? "Login realizado" : "Login solicitado, mas a sessao ainda nao ficou valida",
                "sessaoValida", valida
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("erro", e.getMessage()));
        }
    }

    @PostMapping("/auth/login-assistido")
    public ResponseEntity<Map<String, Object>> loginAssistido() {
        LoginJavaDriverless login = loginJavaDriverless.getIfAvailable();
        if (login == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "erro", "Login assistido indisponivel. Inicie com freelas.driverless.enabled=true."
            ));
        }

        boolean executado = login.executarLogin();
        boolean valida = autenticacaoService.verificarSessaoValida();
        return ResponseEntity.ok(Map.of(
            "mensagem", valida ? "Login assistido realizado" : "Login assistido finalizado, mas a sessao ainda nao ficou valida",
            "executado", executado,
            "sessaoValida", valida
        ));
    }

    @PostMapping("/auth/upwork/login-assistido")
    public ResponseEntity<Map<String, Object>> loginUpwork() {
        UpworkLoginDriverless login = upworkLoginDriverless.getIfAvailable();
        if (login == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "erro", "Login Upwork indisponivel. Inicie com upwork.driverless.enabled=true."
            ));
        }
        boolean executado = login.executarLogin();
        return ResponseEntity.ok(Map.of(
            "mensagem", executado
                ? "Login Upwork concluido e cookies exportados"
                : "Login Upwork finalizado, mas a sessao ainda nao ficou valida",
            "executado", executado
        ));
    }

    @PostMapping("/auth/freelancer/login-assistido")
    public ResponseEntity<Map<String, Object>> loginFreelancer() {
        FreelancerLoginDriverless login = freelancerLoginDriverless.getIfAvailable();
        if (login == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "erro", "Login Freelancer indisponivel. Inicie com freelancer.driverless.enabled=true."
            ));
        }
        boolean executado = login.executarLogin();
        return ResponseEntity.ok(Map.of(
            "mensagem", executado
                ? "Login Freelancer concluido e cookies exportados"
                : "Login Freelancer finalizado, mas a sessao ainda nao ficou valida",
            "executado", executado
        ));
    }

    /**
     * Atualiza cookies manualmente (colando JSON exportado do navegador) e testa se a sessão fica válida.
     *
     * Exemplo de body:
     * {"JSESSIONID":"...","sgcn":"...","cf_clearance":"..."}
     */
    @PostMapping("/auth/cookies")
    public ResponseEntity<Map<String, Object>> atualizarCookies(@RequestBody String jsonCookies) {
        try {
            autenticacaoService.carregarCookiesJson(jsonCookies);
            boolean valida = autenticacaoService.verificarSessaoValida();
            return ResponseEntity.ok(Map.of(
                "mensagem", "Cookies atualizados",
                "sessaoValida", valida
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("erro", e.getMessage()));
        }
    }

    @GetMapping("/auth/session")
    public ResponseEntity<Map<String, Object>> statusSessao() {
        boolean valida = marketplaceGateway.verificarLoginPadrao();
        if (!valida) {
            loginAssistidoAutoTrigger.dispararSeSessaoInvalida("auth-session");
        }
        return ResponseEntity.ok(Map.of("sessaoValida", valida));
    }

    @GetMapping("/conexoes")
    public ResponseEntity<Map<String, String>> getConexoes() {
        return ResponseEntity.ok(Map.of(
            "status", gerenciadorConexoes.getStatusConexoes(),
            "temDisponiveis", gerenciadorConexoes.temConexoesDisponiveis() ? "SIM" : "NAO"
        ));
    }

    @GetMapping("/debug/telegram")
    public ResponseEntity<Map<String, String>> debugTelegram() {
        return ResponseEntity.ok(Map.of(
            "botToken", telegramConfig.getBotToken() != null ? "SET (" + telegramConfig.getBotToken().length() + " chars)" : "NULL",
            "botTokenConfigurado", telegramConfig.getBotToken() != null && !telegramConfig.getBotToken().isBlank() ? "SIM" : "NAO",
            "chatId", valorConfiguradoMascarado(telegramConfig.getChatId()),
            "chatIdConfigurado", telegramConfig.getChatId() != null && !telegramConfig.getChatId().isBlank() ? "SIM" : "NAO"
        ));
    }

    private String valorConfiguradoMascarado(String valor) {
        if (valor == null || valor.isBlank()) {
            return "NULL";
        }
        return "SET (" + valor.trim().length() + " chars)";
    }

    @GetMapping("/acoes-pendentes")
    public ResponseEntity<List<AcaoPendente>> listarAcoesPendentes() {
        List<AcaoPendente> pendentes = acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.PENDENTE);
        return ResponseEntity.ok(pendentes);
    }

    @GetMapping("/acoes-pendentes/count")
    public ResponseEntity<Map<String, Long>> countAcoesPendentes() {
        long count = acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE);
        return ResponseEntity.ok(Map.of("total", count));
    }

    @GetMapping("/mensagens-cliente-registro")
    public ResponseEntity<List<MensagemClienteRegistro>> listarRegistrosSaidaCliente() {
        return ResponseEntity.ok(mensagemClienteRegistroRepository.findTop50ByOrderByDataDesc());
    }
}
