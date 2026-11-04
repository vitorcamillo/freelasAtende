package com.freelasatende.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Component
class ManualVozLoader {

    private static final String ARQUIVO_MANUAL_VOZ = "data/manual-voz-freelancer.md";
    private static final String ARQUIVO_MANUAL_VOZ_WHATSAPP = "data/manual-voz-whatsapp.md";
    private static final int LIMITE_POR_ARQUIVO = 3000;
    private static final int LIMITE_TOTAL = 6000;

    String carregar() {
        try {
            StringBuilder manual = new StringBuilder();
            adicionarManualVoz(manual, ARQUIVO_MANUAL_VOZ);
            adicionarManualVoz(manual, ARQUIVO_MANUAL_VOZ_WHATSAPP);
            String texto = manual.toString().trim();
            if (!texto.isBlank()) {
                return texto.substring(0, Math.min(LIMITE_TOTAL, texto.length()));
            }
        } catch (Exception e) {
            log.debug("Manual de voz indisponivel: {}", e.getMessage());
        }
        return fallback();
    }

    private void adicionarManualVoz(StringBuilder destino, String caminho) throws IOException {
        Path arquivo = Paths.get(caminho);
        if (!Files.exists(arquivo)) {
            return;
        }
        String manual = Files.readString(arquivo).trim();
        if (manual.isBlank()) {
            return;
        }
        if (!destino.isEmpty()) {
            destino.append("\n\n");
        }
        destino.append(manual, 0, Math.min(LIMITE_POR_ARQUIVO, manual.length()));
    }

    private String fallback() {
        return """
            - Escrever como freelancer experiente, direto e simpatico.
            - Nao usar lista numerada em mensagem ao cliente.
            - Fazer no maximo uma pergunta essencial por vez.
            - Nao citar IA, bot, sistema, teste, validacao ou automacao.
            - Nao pedir contato ou pagamento fora da plataforma.
            - Evitar fechamento repetido e linguagem de call center.
            """;
    }
}
