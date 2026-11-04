package com.freelasatende.service.impl;

import com.freelasatende.config.IaConfig;
import com.freelasatende.service.interfaceServico.IaProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

@Slf4j
public class CodexCliProvider implements IaProvider {

    private static final String PROVIDER_NAME = "codex";
    private static final String DEFAULT_CODEX_MODEL = "gpt-5.4";
    private static final String DEFAULT_CODEX_EFFORT = "medium";
    private static final String LEGACY_DEFAULT_MODEL = "codex-default";
    private static final long COOLDOWN_CONTA_INVALIDA_MILLIS = TimeUnit.HOURS.toMillis(2);
    private static final long COOLDOWN_CONTA_LIMITADA_MILLIS = TimeUnit.HOURS.toMillis(5);

    private final IaConfig config;
    private final String cwd;
    private final ConcurrentMap<Path, Long> contasIndisponiveisAte = new ConcurrentHashMap<>();

    public CodexCliProvider(IaConfig config) {
        this(config, System.getProperty("user.dir"));
    }

    CodexCliProvider(IaConfig config, String cwd) {
        this.config = config;
        this.cwd = cwd;
    }

    @Override
    public String enviar(String prompt, int maxTokens) throws IaProviderException {
        if (prompt == null || prompt.isBlank()) {
            throw new IaProviderException("Prompt vazio para codex", 400, false);
        }

        Path responseFile = null;
        Path diagnosticFile = null;
        try {
            responseFile = Files.createTempFile("freelas-codex-response-", ".txt");
            diagnosticFile = Files.createTempFile("freelas-codex-diagnostic-", ".txt");

            IaProviderException ultimaFalhaConta = null;
            boolean algumaContaEmCooldown = false;
            List<Path> homes = accountHomes();
            for (int i = 0; i < homes.size(); i++) {
                Path accountHome = homes.get(i);
                if (contaEmCooldown(accountHome)) {
                    algumaContaEmCooldown = true;
                    continue;
                }
                try {
                    Files.writeString(responseFile, "", StandardCharsets.UTF_8);
                    String resposta = enviarComConta(prompt, responseFile, diagnosticFile, accountHome);
                    contasIndisponiveisAte.remove(accountHome);
                    return resposta;
                } catch (IaProviderException e) {
                    if (!falhaDeConta(e)) {
                        throw e;
                    }
                    ultimaFalhaConta = e;
                    marcarContaIndisponivel(accountHome, e);
                    log.warn("Conta Codex perfil #{} indisponivel para {}: {}",
                        i + 1, PROVIDER_NAME, e.getMessage());
                }
            }

            throw ultimaFalhaConta == null
                ? falhaPorCooldown(algumaContaEmCooldown)
                : ultimaFalhaConta;
        } catch (IOException e) {
            throw new IaProviderException("Erro ao iniciar codex: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IaProviderException("Chamada codex interrompida", e);
        } finally {
            removerArquivoTemporario(responseFile);
            removerArquivoTemporario(diagnosticFile);
        }
    }

    private String enviarComConta(
        String prompt,
        Path responseFile,
        Path diagnosticFile,
        Path accountHome
    ) throws IOException, InterruptedException, IaProviderException {
        ProcessBuilder builder = new ProcessBuilder(command(responseFile))
            .redirectErrorStream(true)
            .redirectOutput(diagnosticFile.toFile());
        builder.environment().put("CODEX_HOME", accountHome.toString());

        Process process = builder.start();

        process.getOutputStream().write(normalizarPromptParaStdin(prompt).getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();

        boolean finished = process.waitFor(timeoutSeconds() + 10L, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IaProviderException("Timeout ao comunicar com codex", 504, true);
        }

        String diagnostic = lerArquivoUtf8Seguro(diagnosticFile).trim();
        if (process.exitValue() != 0) {
            if (indicaFalhaAutenticacao(diagnostic)) {
                throw new IaProviderException(
                    "CODEX_AUTH_INVALIDA: autenticacao do Codex CLI ausente ou expirada",
                    401,
                    false
                );
            }
            if (indicaLimiteConta(diagnostic)) {
                throw new IaProviderException(
                    "CODEX_CONTA_LIMITADA: limite ou creditos da conta Codex esgotados",
                    429,
                    false
                );
            }
            throw new IaProviderException(
                "codex retornou codigo " + process.exitValue() + ": " + resumirDiagnosticoSeguro(diagnostic),
                process.exitValue(),
                true
            );
        }

        String response = lerArquivoUtf8Seguro(responseFile).trim();
        if (response.isBlank()) {
            throw new IaProviderException("codex retornou resposta vazia", 502, true);
        }
        return response;
    }

    @Override
    public String getNome() {
        return PROVIDER_NAME;
    }

    @Override
    public String getModelo() {
        return modeloCodex();
    }

    @Override
    public boolean estaDisponivel() {
        try {
            return "OK".equalsIgnoreCase(enviar("Responda somente OK", 10).trim());
        } catch (Exception e) {
            log.warn("codex nao disponivel: {}", e.getMessage());
            return false;
        }
    }

    private List<String> command(Path responseFile) {
        List<String> command = new ArrayList<>();
        command.add("codex");
        command.add("exec");
        command.add("-c");
        command.add("approval_policy=never");
        command.add("--ephemeral");
        command.add("--skip-git-repo-check");
        command.add("--ignore-user-config");
        command.add("--sandbox");
        command.add("read-only");
        command.add("--cd");
        command.add(cwd);
        command.add("--output-last-message");
        command.add(responseFile.toString());
        command.add("--model");
        command.add(modeloCodex());
        command.add("-c");
        command.add("model_reasoning_effort=\"" + DEFAULT_CODEX_EFFORT + "\"");
        command.add("-");
        return command;
    }

    private String modeloCodex() {
        String model = config.getModel();
        if (model == null || model.isBlank() || LEGACY_DEFAULT_MODEL.equalsIgnoreCase(model)) {
            return DEFAULT_CODEX_MODEL;
        }
        return model;
    }

    private int timeoutSeconds() {
        return Math.max(15, config.getTimeout());
    }

    private List<Path> accountHomes() {
        Set<Path> homes = new LinkedHashSet<>();
        String configuredHomes = config.getCodexAccountHomes();
        if (configuredHomes != null && !configuredHomes.isBlank()) {
            for (String home : configuredHomes.split(":")) {
                adicionarHome(homes, home);
            }
            return List.copyOf(homes);
        }
        adicionarHome(homes, System.getenv("CODEX_HOME"));
        adicionarHome(homes, "/var/lib/codex");
        return List.copyOf(homes);
    }

    private void adicionarHome(Set<Path> homes, String home) {
        if (home == null || home.isBlank()) {
            return;
        }
        Path accountHome = Path.of(home.trim()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(accountHome.resolve("auth.json"))) {
            log.debug("Ignorando CODEX_HOME sem auth.json: {}", accountHome);
            return;
        }
        homes.add(accountHome);
    }

    private boolean falhaDeConta(IaProviderException e) {
        return e.getMessage() != null
            && (e.getMessage().contains("CODEX_AUTH_INVALIDA")
            || e.getMessage().contains("CODEX_CONTA_LIMITADA"));
    }

    private boolean contaEmCooldown(Path accountHome) {
        Long indisponivelAte = contasIndisponiveisAte.get(accountHome);
        if (indisponivelAte == null) {
            return false;
        }
        long agora = System.currentTimeMillis();
        if (agora >= indisponivelAte) {
            contasIndisponiveisAte.remove(accountHome, indisponivelAte);
            return false;
        }
        return true;
    }

    private IaProviderException falhaPorCooldown(boolean algumaContaEmCooldown) {
        if (algumaContaEmCooldown) {
            return new IaProviderException(
                "CODEX_CONTA_LIMITADA: todas as contas Codex estao em cooldown por limite ou autenticacao",
                429,
                false
            );
        }
        return new IaProviderException("Nenhuma conta Codex configurada", 401, false);
    }

    private void marcarContaIndisponivel(Path accountHome, IaProviderException e) {
        long cooldown = e.getMessage() != null && e.getMessage().contains("CODEX_CONTA_LIMITADA")
            ? COOLDOWN_CONTA_LIMITADA_MILLIS
            : COOLDOWN_CONTA_INVALIDA_MILLIS;
        contasIndisponiveisAte.put(accountHome, System.currentTimeMillis() + cooldown);
    }

    private boolean indicaFalhaAutenticacao(String diagnostic) {
        if (diagnostic == null || diagnostic.isBlank()) {
            return false;
        }
        String texto = diagnostic.toLowerCase(Locale.ROOT);
        return texto.contains("not logged in")
            || texto.contains("please log in")
            || texto.contains("sign in again")
            || texto.contains("authentication")
            || texto.contains("unauthorized")
            || texto.contains("access token")
            || texto.contains("refresh token")
            || texto.contains("refresh_token_reused")
            || texto.contains("could not be refreshed")
            || texto.contains("token expired")
            || texto.contains("token_expired")
            || texto.contains("stream did not contain valid utf-8")
            || texto.contains("invalid utf-8")
            || texto.contains("401");
    }

    private String resumirDiagnosticoSeguro(String diagnostic) {
        if (diagnostic == null || diagnostic.isBlank()) {
            return "[sem diagnostico]";
        }
        String normalizado = diagnostic
            .replaceAll("(?i)(bearer|token|authorization|api[_-]?key)[=: ]+[^\\s]+", "$1=[redacted]")
            .replaceAll("[A-Za-z0-9_-]{24,}", "[redacted]")
            .replaceAll("\\s+", " ")
            .trim();
        return normalizado.length() <= 500 ? normalizado : normalizado.substring(0, 500);
    }

    private String lerArquivoUtf8Seguro(Path arquivo) throws IOException {
        byte[] bytes = Files.readAllBytes(arquivo);
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    }

    private String normalizarPromptParaStdin(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        StringBuilder seguro = new StringBuilder(prompt.length());
        for (int offset = 0; offset < prompt.length(); ) {
            char ch = prompt.charAt(offset);
            if (Character.isHighSurrogate(ch)) {
                if (offset + 1 < prompt.length() && Character.isLowSurrogate(prompt.charAt(offset + 1))) {
                    seguro.appendCodePoint(Character.toCodePoint(ch, prompt.charAt(offset + 1)));
                    offset += 2;
                    continue;
                }
                seguro.append('\uFFFD');
                offset++;
                continue;
            }
            if (Character.isLowSurrogate(ch)) {
                seguro.append('\uFFFD');
                offset++;
                continue;
            }
            int codePoint = ch;
            offset++;
            if (Character.isISOControl(codePoint)
                && codePoint != '\n'
                && codePoint != '\r'
                && codePoint != '\t') {
                seguro.append(' ');
                continue;
            }
            seguro.appendCodePoint(codePoint);
        }
        return seguro.toString();
    }

    private boolean indicaLimiteConta(String diagnostic) {
        if (diagnostic == null || diagnostic.isBlank()) {
            return false;
        }
        String texto = diagnostic.toLowerCase(Locale.ROOT);
        return texto.contains("usage limit")
            || texto.contains("hit your usage")
            || texto.contains("purchase more credits")
            || texto.contains("no capacity available")
            || texto.contains("rate limit")
            || texto.contains("429");
    }

    private void removerArquivoTemporario(Path arquivo) {
        if (arquivo == null) {
            return;
        }
        try {
            Files.deleteIfExists(arquivo);
        } catch (IOException e) {
            log.debug("Nao foi possivel remover arquivo temporario do codex: {}", arquivo.getFileName());
        }
    }
}
