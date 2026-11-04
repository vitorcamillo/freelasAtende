package com.freelasatende.service.impl;

import com.freelasatende.service.interfaceServico.TelegramService;
import io.github.selenium.javaDriverless.JavaDriverless;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * Trata a verificacao em duas etapas (OTP/MFA) de marketplaces externos
 * durante o login assistido via Chrome real (JavaDriverless).
 *
 * <p>Reaproveita o mesmo mecanismo de handoff do login do 99Freelas: o codigo
 * chega pelo poller unico do Telegram ({@link MfaCodeInbox}) ou, como fallback
 * manual, por um arquivo em disco ({@code data/<plataforma>-otp.txt}). O codigo
 * nunca e adivinhado nem burlado; se nao chegar dentro do tempo, o login segue
 * o proprio caminho e a janela do Chrome continua aberta para digitacao manual.</p>
 *
 * <p>E parametrizado por plataforma para servir Upwork, Freelancer e futuras
 * integracoes sem duplicar logica.</p>
 */
@Slf4j
final class MarketplaceOtpHandler {

    private final String plataforma;
    private final String arquivoOtp;
    private final String[] inputSelectors;
    private final String[] submitTextos;
    private final TelegramService telegramService;
    private final MfaCodeInbox mfaCodeInbox;
    private final long timeoutMillis;
    private final GmailOtpReader gmailOtpReader;
    private final String gmailRemetenteQuery;

    MarketplaceOtpHandler(
        String plataforma,
        String arquivoOtp,
        String[] inputSelectors,
        String[] submitTextos,
        TelegramService telegramService,
        MfaCodeInbox mfaCodeInbox,
        long timeoutMillis,
        GmailOtpReader gmailOtpReader,
        String gmailRemetenteQuery
    ) {
        this.plataforma = plataforma;
        this.arquivoOtp = arquivoOtp;
        this.inputSelectors = inputSelectors;
        this.submitTextos = submitTextos;
        this.telegramService = telegramService;
        this.mfaCodeInbox = mfaCodeInbox;
        this.timeoutMillis = timeoutMillis;
        this.gmailOtpReader = gmailOtpReader;
        this.gmailRemetenteQuery = gmailRemetenteQuery;
    }

    /**
     * Detecta se a pagina atual esta pedindo codigo de verificacao.
     * Heuristica por presenca de campo de codigo e por texto/URL comuns.
     */
    boolean pareceTelaOtp(JavaDriverless driver) {
        for (String sel : inputSelectors) {
            if (sel != null && !sel.isBlank() && LoginDriverlessDomHelper.elementoVisivel(driver, sel)) {
                return true;
            }
        }
        // Leitura tolerante: sob carga o CDP estoura ("Erro ao obter URL") e a
        // excecao abortava o login inteiro justamente na deteccao do OTP. Sem
        // URL a heuristica cai nos seletores acima, que ja cobrem a tela.
        String url = LoginDriverlessDomHelper.urlAtualSegura(driver);
        if (url != null) {
            String u = url.toLowerCase();
            if (u.contains("otp") || u.contains("verify") || u.contains("mfa")
                || u.contains("two-factor") || u.contains("2fa") || u.contains("challenge")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Aguarda o codigo (Telegram ou arquivo), preenche e submete.
     *
     * @return URL atual apos a tentativa (inalterada se houve timeout).
     */
    String resolver(JavaDriverless driver) throws Exception {
        log.info("[OTP][{}] Tela de verificacao (OTP/MFA) detectada.", plataforma);
        try { driver.screenshot(plataforma.toLowerCase() + "_otp_aguardando.png"); } catch (Exception ignore) {}

        notificarTelegram();
        log.info("   Aguardando codigo OTP via Telegram ou arquivo {} (timeout {}s)...",
            arquivoOtp, timeoutMillis / 1000);

        String codigo = aguardarCodigo();
        if (codigo == null) {
            log.warn("   [OTP][{}] Timeout aguardando codigo. A janela segue aberta para digitacao manual.", plataforma);
            return LoginDriverlessDomHelper.urlAtualSegura(driver);
        }

        preencherCodigo(driver, codigo);
        TimeUnit.SECONDS.sleep(1);
        clicarSubmit(driver);
        TimeUnit.SECONDS.sleep(8);
        return LoginDriverlessDomHelper.urlAtualSegura(driver);
    }

    private void notificarTelegram() {
        if (telegramService == null) {
            return;
        }
        try {
            telegramService.enviarNotificacao(
                "Acao necessaria - " + plataforma + "\n\n"
                + "O login assistido esta pedindo o codigo de verificacao (OTP/2FA).\n"
                + "Responda com o codigo numerico, ou grave em " + arquivoOtp + ".");
        } catch (Exception e) {
            log.warn("   Erro ao notificar Telegram sobre OTP {}: {}", plataforma, e.getMessage());
        }
    }

    private String aguardarCodigo() throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        long inicio = System.currentTimeMillis();
        mfaCodeInbox.iniciarEspera();
        try {
            while (System.currentTimeMillis() < deadline) {
                // Fonte primaria: e-mail de verificacao lido do Gmail (read-only).
                String viaGmail = lerCodigoGmail(inicio);
                if (viaGmail != null) {
                    return viaGmail;
                }
                String viaTelegram = mfaCodeInbox.aguardar(3_000L);
                if (viaTelegram != null) {
                    log.info("   [OTP][{}] Codigo recebido via Telegram.", plataforma);
                    return viaTelegram;
                }
                String viaArquivo = lerCodigoArquivo();
                if (viaArquivo != null) {
                    return viaArquivo;
                }
            }
            return null;
        } finally {
            mfaCodeInbox.encerrarEspera();
        }
    }

    private String lerCodigoGmail(long inicioLogin) {
        if (gmailOtpReader == null || gmailRemetenteQuery == null || gmailRemetenteQuery.isBlank()) {
            return null;
        }
        if (!gmailOtpReader.disponivel()) {
            return null;
        }
        // Aceita apenas e-mails recentes o suficiente para pertencer a este login.
        int idadeMin = 15;
        String codigo = gmailOtpReader.buscarCodigo(gmailRemetenteQuery, idadeMin);
        if (codigo != null) {
            log.info("   [OTP][{}] Codigo obtido automaticamente por e-mail (Gmail).", plataforma);
        }
        return codigo;
    }

    private String lerCodigoArquivo() {
        try {
            File f = new File(arquivoOtp);
            if (!f.exists()) {
                return null;
            }
            String content = new String(Files.readAllBytes(f.toPath())).trim();
            if (!content.isEmpty()) {
                String digits = content.replaceAll("[^0-9]", "");
                log.info("   [OTP][{}] Codigo lido do arquivo.", plataforma);
                f.delete();
                return digits.isEmpty() ? null : digits;
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private void preencherCodigo(JavaDriverless driver, String codigo) {
        log.info("   [OTP][{}] Preenchendo codigo...", plataforma);
        String seletorJs = montarSeletorJs();
        String safeCodigo = codigo.replaceAll("[^0-9]", "");
        driver.executeScript(
            "var inputs = document.querySelectorAll(" + seletorJs + ");"
            + "var code = '" + safeCodigo + "';"
            + "var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;"
            + "if (inputs.length > 1) {"
            + "  for (var i = 0; i < inputs.length && i < code.length; i++) {"
            + "    var el = inputs[i]; el.focus(); setter.call(el, code[i]);"
            + "    el.dispatchEvent(new Event('input', {bubbles:true}));"
            + "    el.dispatchEvent(new Event('change', {bubbles:true}));"
            + "  }"
            + "} else if (inputs.length === 1) {"
            + "  var input = inputs[0]; input.focus(); setter.call(input, '');"
            + "  for (var j = 0; j < code.length; j++) {"
            + "    setter.call(input, input.value + code[j]);"
            + "    input.dispatchEvent(new Event('input', {bubbles:true}));"
            + "  }"
            + "  input.dispatchEvent(new Event('change', {bubbles:true}));"
            + "}"
        );
    }

    private void clicarSubmit(JavaDriverless driver) {
        log.info("   [OTP][{}] Confirmando codigo...", plataforma);
        StringBuilder cond = new StringBuilder();
        for (String t : submitTextos) {
            if (t == null || t.isBlank()) {
                continue;
            }
            if (cond.length() > 0) {
                cond.append(" || ");
            }
            cond.append("b.innerText.toLowerCase().includes('")
                .append(t.toLowerCase().replace("'", ""))
                .append("')");
        }
        if (cond.length() == 0) {
            cond.append("b.type === 'submit'");
        }
        driver.executeScript(
            "var btn = Array.from(document.querySelectorAll('button, input[type=submit]'))"
            + ".find(function(b){ return " + cond + "; });"
            + "if (btn) btn.click();"
        );
    }

    private String montarSeletorJs() {
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < inputSelectors.length; i++) {
            if (inputSelectors[i] == null || inputSelectors[i].isBlank()) {
                continue;
            }
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(inputSelectors[i].replace("'", "\\'"));
        }
        sb.append("'");
        return sb.toString();
    }
}
