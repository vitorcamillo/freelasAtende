package com.freelasatende.service;

import com.freelasatende.config.IaConfig;
import com.freelasatende.service.impl.CodexCliProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

class CodexCliProviderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Provider codex resolve modelo padrao para Codex 5.4")
    void providerCodexExpoeModeloPadraoDoCli() {
        IaConfig config = new IaConfig();
        config.setProvider("codex");
        config.setModel("codex-default");

        CodexCliProvider provider = new CodexCliProvider(config);

        assertEquals("codex", provider.getNome());
        assertEquals("gpt-5.4", provider.getModelo());
    }

    @Test
    @DisplayName("Provider codex expoe modelo explicitamente configurado")
    void providerCodexExpoeModeloConfigurado() {
        IaConfig config = new IaConfig();
        config.setModel("modelo-configurado");

        CodexCliProvider provider = new CodexCliProvider(config);

        assertEquals("modelo-configurado", provider.getModelo());
    }

    @Test
    @DisplayName("Provider codex passa modelo e effort direto no exec nao interativo")
    void providerCodexUsaApprovalPolicyViaConfig() throws Exception {
        IaConfig config = new IaConfig();
        config.setProvider("codex");
        config.setModel("codex-default");

        CodexCliProvider provider = new CodexCliProvider(config);
        Method commandMethod = CodexCliProvider.class.getDeclaredMethod("command", java.nio.file.Path.class);
        commandMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) commandMethod.invoke(provider, java.nio.file.Path.of("resposta.txt"));

        assertEquals(List.of("codex", "exec", "-c", "approval_policy=never"), command.subList(0, 4));
        assertFalse(command.contains("--ask-for-approval"));
        assertEquals("gpt-5.4", command.get(command.indexOf("--model") + 1));
        assertEquals("model_reasoning_effort=\"medium\"", command.get(command.lastIndexOf("-c") + 1));
    }

    @Test
    @DisplayName("Provider codex identifica refresh token reutilizado como auth invalida")
    void providerCodexIdentificaRefreshTokenReutilizado() throws Exception {
        CodexCliProvider provider = new CodexCliProvider(new IaConfig());
        Method method = CodexCliProvider.class.getDeclaredMethod("indicaFalhaAutenticacao", String.class);
        method.setAccessible(true);

        boolean resultado = (boolean) method.invoke(provider,
            "Your access token could not be refreshed because your refresh token was already used. Please log out and sign in again.");

        assertEquals(true, resultado);
    }

    @Test
    @DisplayName("Provider codex identifica stream UTF-8 invalido como auth/config invalida")
    void providerCodexIdentificaStreamUtf8InvalidoComoAuthInvalida() throws Exception {
        CodexCliProvider provider = new CodexCliProvider(new IaConfig());
        Method method = CodexCliProvider.class.getDeclaredMethod("indicaFalhaAutenticacao", String.class);
        method.setAccessible(true);

        boolean resultado = (boolean) method.invoke(provider, "stream did not contain valid UTF-8");

        assertEquals(true, resultado);
    }

    @Test
    @DisplayName("Provider codex identifica limite de uso como troca de conta")
    void providerCodexIdentificaLimiteDeUso() throws Exception {
        CodexCliProvider provider = new CodexCliProvider(new IaConfig());
        Method method = CodexCliProvider.class.getDeclaredMethod("indicaLimiteConta", String.class);
        method.setAccessible(true);

        boolean resultado = (boolean) method.invoke(provider,
            "You've hit your usage limit. Upgrade to Pro or purchase more credits.");

        assertEquals(true, resultado);
    }

    @Test
    @DisplayName("Provider codex resume diagnostico sem expor segredos")
    void providerCodexResumeDiagnosticoSemExporSegredos() throws Exception {
        CodexCliProvider provider = new CodexCliProvider(new IaConfig());
        Method method = CodexCliProvider.class.getDeclaredMethod("resumirDiagnosticoSeguro", String.class);
        method.setAccessible(true);

        String resultado = (String) method.invoke(provider,
            "Erro modelo invalido token=abcdefghijklmnopqrstuvwxyz1234567890 detalhes extras"); // gitleaks:allow (token sintetico de teste)

        assertFalse(resultado.contains("abcdefghijklmnopqrstuvwxyz1234567890"));
        assertEquals(true, resultado.contains("Erro modelo invalido"));
    }

    @Test
    @DisplayName("Provider codex normaliza prompt para stdin UTF-8 seguro")
    void providerCodexNormalizaPromptParaStdinUtf8Seguro() throws Exception {
        CodexCliProvider provider = new CodexCliProvider(new IaConfig());
        Method method = CodexCliProvider.class.getDeclaredMethod("normalizarPromptParaStdin", String.class);
        method.setAccessible(true);

        String resultado = (String) method.invoke(provider, "texto\u0000quebrado\uD800\nok\tfim");

        assertEquals("texto quebrado\uFFFD\nok\tfim", resultado);
        assertFalse(resultado.contains("\u0000"));
    }

    @Test
    @DisplayName("Provider codex resolve homes configurados para rotacao de contas")
    void providerCodexResolveHomesConfiguradosParaRotacao() throws Exception {
        Path base = tempDir.resolve("base");
        Path conta1 = tempDir.resolve("accounts/1");
        Path conta2 = tempDir.resolve("accounts/2");
        Files.createDirectories(base);
        Files.createDirectories(conta1);
        Files.createDirectories(conta2);
        Files.writeString(base.resolve("auth.json"), "{}");
        Files.writeString(conta1.resolve("auth.json"), "{}");
        Files.writeString(conta2.resolve("auth.json"), "{}");

        IaConfig config = new IaConfig();
        config.setCodexAccountHomes(base + ":" + conta1 + ":" + conta2);
        CodexCliProvider provider = new CodexCliProvider(config);
        Method method = CodexCliProvider.class.getDeclaredMethod("accountHomes");
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Path> homes = (List<Path>) method.invoke(provider);

        assertEquals(base.toAbsolutePath().normalize(), homes.get(0));
        assertEquals(conta1.toAbsolutePath().normalize(), homes.get(1));
        assertEquals(conta2.toAbsolutePath().normalize(), homes.get(2));
    }

    @Test
    @DisplayName("Provider codex ignora homes configurados sem auth.json")
    void providerCodexIgnoraHomesSemAuthJson() throws Exception {
        Path baseSemAuth = tempDir.resolve("base-sem-auth");
        Path contaComAuth = tempDir.resolve("accounts/1");
        Files.createDirectories(baseSemAuth);
        Files.createDirectories(contaComAuth);
        Files.writeString(contaComAuth.resolve("auth.json"), "{}");

        IaConfig config = new IaConfig();
        config.setCodexAccountHomes(baseSemAuth + ":" + contaComAuth);
        CodexCliProvider provider = new CodexCliProvider(config);
        Method method = CodexCliProvider.class.getDeclaredMethod("accountHomes");
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Path> homes = (List<Path>) method.invoke(provider);

        assertEquals(List.of(contaComAuth.toAbsolutePath().normalize()), homes);
    }

    @Test
    @DisplayName("Provider codex le diagnostico com bytes invalidos sem quebrar")
    void providerCodexLeDiagnosticoComBytesInvalidosSemQuebrar() throws Exception {
        Path diagnostic = tempDir.resolve("diagnostic.bin");
        Files.write(diagnostic, new byte[]{'e', 'r', 'r', 'o', ' ', (byte) 0xC3, 0x28});
        CodexCliProvider provider = new CodexCliProvider(new IaConfig());
        Method method = CodexCliProvider.class.getDeclaredMethod("lerArquivoUtf8Seguro", Path.class);
        method.setAccessible(true);

        String resultado = (String) method.invoke(provider, diagnostic);

        assertTrue(resultado.contains("erro"));
        assertTrue(resultado.contains("\uFFFD"));
    }

    @Test
    @DisplayName("Provider codex classifica auth invalida e limite como falha de conta")
    void providerCodexClassificaFalhasDeConta() throws Exception {
        CodexCliProvider provider = new CodexCliProvider(new IaConfig());
        Method method = CodexCliProvider.class.getDeclaredMethod(
            "falhaDeConta",
            com.freelasatende.service.interfaceServico.IaProvider.IaProviderException.class
        );
        method.setAccessible(true);

        boolean authInvalida = (boolean) method.invoke(provider,
            new com.freelasatende.service.interfaceServico.IaProvider.IaProviderException(
                "CODEX_AUTH_INVALIDA: autenticacao do Codex CLI ausente ou expirada",
                401,
                false
            ));
        boolean contaLimitada = (boolean) method.invoke(provider,
            new com.freelasatende.service.interfaceServico.IaProvider.IaProviderException(
                "CODEX_CONTA_LIMITADA: limite ou creditos da conta Codex esgotados",
                429,
                false
            ));
        boolean timeout = (boolean) method.invoke(provider,
            new com.freelasatende.service.interfaceServico.IaProvider.IaProviderException(
                "Timeout ao comunicar com codex",
                504,
                true
            ));

        assertEquals(true, authInvalida);
        assertEquals(true, contaLimitada);
        assertEquals(false, timeout);
    }

    @Test
    @DisplayName("Provider codex coloca conta limitada em cooldown")
    void providerCodexColocaContaLimitadaEmCooldown() throws Exception {
        CodexCliProvider provider = new CodexCliProvider(new IaConfig());
        Path accountHome = Path.of("/var/lib/codex/accounts/1");
        Method marcar = CodexCliProvider.class.getDeclaredMethod(
            "marcarContaIndisponivel",
            Path.class,
            com.freelasatende.service.interfaceServico.IaProvider.IaProviderException.class
        );
        Method emCooldown = CodexCliProvider.class.getDeclaredMethod("contaEmCooldown", Path.class);
        marcar.setAccessible(true);
        emCooldown.setAccessible(true);

        marcar.invoke(provider, accountHome,
            new com.freelasatende.service.interfaceServico.IaProvider.IaProviderException(
                "CODEX_CONTA_LIMITADA: limite ou creditos da conta Codex esgotados",
                429,
                false
            ));

        assertEquals(true, emCooldown.invoke(provider, accountHome));
    }
}
