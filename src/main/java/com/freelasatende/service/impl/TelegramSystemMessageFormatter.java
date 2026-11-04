package com.freelasatende.service.impl;

import com.freelasatende.model.enums.ModoSistema;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

final class TelegramSystemMessageFormatter {

    private TelegramSystemMessageFormatter() {
    }

    static String formatarStatus(
        String statusConexoes,
        boolean sessaoValida,
        int interacoesDia,
        ModoSistema modo,
        boolean pausado,
        long pendencias,
        String iaNome,
        String iaModelo
    ) {
        return String.format(
            "📊 *Status do Sistema*\n\n" +
            "🔄 Modo: %s\n" +
            "⏸ Pausado: %s\n" +
            "🔐 Sessão: %s\n" +
            "📡 %s\n" +
            "⏳ Ações pendentes: %d\n" +
            "📝 Interações hoje: %d\n" +
            "🤖 IA: %s (%s)\n" +
            "🕐 %s",
            modo,
            pausado ? "SIM ⚠️" : "NÃO ✅",
            sessaoValida ? "Válida ✅" : "Inválida ❌",
            statusConexoes,
            pendencias,
            interacoesDia,
            iaNome,
            iaModelo,
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))
        );
    }

    static String formatarEstatisticas(
        int interacoesDia,
        String statusConexoes,
        long pendencias,
        String iaNome,
        String iaModelo,
        ModoSistema modo
    ) {
        return String.format(
            "📈 *Estatísticas*\n\n" +
            "📊 Interações hoje: %d\n" +
            "📡 %s\n" +
            "⏳ Pendências: %d\n" +
            "🤖 Provider: %s\n" +
            "📋 Modelo: %s\n" +
            "🔄 Modo: %s",
            interacoesDia,
            statusConexoes,
            pendencias,
            iaNome,
            iaModelo,
            modo
        );
    }

    static String formatarInfoIa(String iaNome, String iaModelo, boolean disponivel) {
        return String.format(
            "🤖 *Provider de IA*\n\n" +
            "Provider: %s\n" +
            "Modelo: %s\n" +
            "Disponível: %s",
            iaNome,
            iaModelo,
            disponivel ? "SIM ✅" : "NÃO ❌"
        );
    }

    static String ajuda() {
        return "🤖 *FreelasAtende - Botões*\n\n" +
            "Texto normal: conversa comigo.\n" +
            "Decisão de pendência: botão.\n" +
            "Ensinar: botão Ensinar, depois mensagem normal.\n" +
            "Reescrever: botão Reescrever, depois mensagem normal.\n" +
            "Aprovar, recusar, focar e abrir ação: botões da própria pendência.\n\n" +
            "Comandos ficam só como atalho legado e emergência operacional.";
    }
}
