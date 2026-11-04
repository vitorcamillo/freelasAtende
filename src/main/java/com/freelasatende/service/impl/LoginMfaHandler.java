package com.freelasatende.service.impl;

import com.freelasatende.service.interfaceServico.TelegramService;
import io.github.selenium.javaDriverless.JavaDriverless;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
final class LoginMfaHandler {

    private final TelegramService telegramService;
    private final MfaCodeInbox mfaCodeInbox;

    String lidarComMfa(JavaDriverless driver) throws Exception {
        log.info("🔒 Tela de Autenticação Multifator (MFA) detectada!");

        String infoMfa = extrairInfoMfa(driver);
        log.info("   {}", infoMfa != null && !infoMfa.isBlank() ? infoMfa.replace("\n", " ") : "MFA requisitado");
        try { driver.screenshot("mfa_aguardando.png"); } catch (Exception ignore) {}

        notificarTelegram(infoMfa);
        log.info("   ⏳ Aguardando código MFA via Telegram (Timeout 120s)...");

        String codigoMfa = aguardarCodigoMfa();
        if (codigoMfa == null) {
            log.warn("   ⚠️ Timeout de 120s aguardando código MFA. O login pode falhar.");
            return driver.getCurrentUrl();
        }

        preencherCodigo(driver, codigoMfa);
        TimeUnit.SECONDS.sleep(1);

        log.info("   🖱️ Clicando em Confirmar (MFA)...");
        driver.executeScript(
            "var btn = Array.from(document.querySelectorAll('button')).find(b => b.innerText.includes('Confirmar') || b.className.includes('btn-green'));" +
            "if(btn) btn.click();"
        );
        TimeUnit.SECONDS.sleep(8);
        return driver.getCurrentUrl();
    }

    private String extrairInfoMfa(JavaDriverless driver) {
        return LoginDriverlessDomHelper.safeGetString(driver, "(() => {" +
            "let txt = '';" +
            "document.querySelectorAll('p, div, span').forEach(el => {" +
            "  if(el.innerText && el.innerText.includes('Tipo de autenticação:')) txt = el.innerText;" +
            "});" +
            "return txt;" +
        "})()");
    }

    private void notificarTelegram(String infoMfa) {
        if (telegramService == null) {
            return;
        }
        try {
            String msg = "🔒 *Ação Necessária - 99Freelas*\n\n" +
                "O login está solicitando Autenticação Multifator (MFA).\n" +
                "Info: `" + (infoMfa != null ? infoMfa.replace("\n", " ") : "Desconhecido") + "`\n\n" +
                "Por favor, responda com o código numérico.";
            telegramService.enviarNotificacao(msg);
        } catch (Exception e) {
            log.warn("Erro ao notificar Telegram sobre MFA: {}", e.getMessage());
        }
    }

    private String aguardarCodigoMfa() throws InterruptedException {
        long mfaDeadline = System.currentTimeMillis() + 120_000L;
        mfaCodeInbox.iniciarEspera();
        try {
            while (System.currentTimeMillis() < mfaDeadline) {
                // Código entregue pelo poller único do Telegram (TelegramCommandHandler).
                String codigoTelegram = mfaCodeInbox.aguardar(3_000L);
                if (codigoTelegram != null) {
                    log.info("   ✅ Código MFA recebido via Telegram: {}", codigoTelegram);
                    return codigoTelegram;
                }

                // Fallback manual: arquivo data/mfa_code.txt.
                String codigoArquivo = lerCodigoArquivo();
                if (codigoArquivo != null) {
                    return codigoArquivo;
                }
            }
            return null;
        } finally {
            mfaCodeInbox.encerrarEspera();
        }
    }

    private String lerCodigoArquivo() {
        File dir = new File("data");
        if (!dir.exists()) dir.mkdirs();
        File mfaFile = new File(dir, "mfa_code.txt");
        if (!mfaFile.exists()) {
            return null;
        }
        try {
            String content = new String(Files.readAllBytes(mfaFile.toPath())).trim();
            if (!content.isEmpty()) {
                log.info("   ✅ Código MFA lido do arquivo: {}", content);
                mfaFile.delete();
                return content;
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private void preencherCodigo(JavaDriverless driver, String codigoMfa) {
        log.info("   📝 Preenchendo código MFA...");
        driver.executeScript(
            "var input = document.querySelector('#codigo, input[name=\"codigo\"], input.codigo-mfa, input[type=tel]');" +
            "if(input) {" +
            "  input.focus();" +
            "  input.value = '';" +
            "  let text = '" + codigoMfa + "';" +
            "  let setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;" +
            "  for(let i = 0; i < text.length; i++) {" +
            "    setter.call(input, input.value + text[i]);" +
            "    input.dispatchEvent(new Event('input', {bubbles:true}));" +
            "  }" +
            "  input.dispatchEvent(new Event('change', {bubbles:true}));" +
            "}"
        );
    }
}
