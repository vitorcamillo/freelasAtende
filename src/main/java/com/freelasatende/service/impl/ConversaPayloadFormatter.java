package com.freelasatende.service.impl;

import com.freelasatende.model.dto.ConversaMensagemControleDTO;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ConversaPayloadFormatter {

    private ConversaPayloadFormatter() {
    }

    static List<String> duvidasInternasConversa(MensagemConversa ultimaRecebida) {
        String texto = normalizarTextoConversa(ultimaRecebida != null ? ultimaRecebida.getTexto() : "");
        String trecho = resumirTextoConversa(ultimaRecebida != null ? ultimaRecebida.getTexto() : "");
        if (texto.contains("prazo") || texto.contains("data") || texto.contains("urgente")) {
            return List.of("confirmar prazo antes de assumir entrega; gatilho: \"" + trecho + "\"");
        }
        if (texto.contains("valor") || texto.contains("preco") || texto.contains("preço") || texto.contains("orcamento") || texto.contains("orçamento")) {
            return List.of("confirmar se deve falar valor agora ou pedir escopo antes; gatilho: \"" + trecho + "\"");
        }
        if (texto.contains("acesso") || texto.contains("login") || texto.contains("hospedagem") || texto.contains("painel")) {
            return List.of("confirmar acesso tecnico antes de prometer execucao; gatilho: \"" + trecho + "\"");
        }
        if (texto.contains("whatsapp") || texto.contains("fora da plataforma") || texto.contains("por fora")) {
            return List.of("manter conversa dentro da plataforma e evitar contato externo; gatilho: \"" + trecho + "\"");
        }
        if (texto.contains("ajust") || texto.contains("alter") || texto.contains("mudan")) {
            return List.of("confirmar se ajuste muda escopo, prazo ou prioridade; gatilho: \"" + trecho + "\"");
        }
        if (texto.contains("conteudo") || texto.contains("conteúdo") || texto.contains("texto") || texto.contains("imagem")) {
            return List.of("confirmar se materiais do cliente ja estao prontos; gatilho: \"" + trecho + "\"");
        }
        return List.of("validar se a resposta segue seu tom e nao repete fala anterior; gatilho: \"" + trecho + "\"");
    }

    static String perguntaEnsinoConversa(MensagemConversa ultimaRecebida, ConversaResumo conversa) {
        String texto = normalizarTextoConversa(ultimaRecebida != null ? ultimaRecebida.getTexto() : "");
        String projeto = rotuloConversa(conversa);
        String trecho = resumirTextoConversa(ultimaRecebida != null ? ultimaRecebida.getTexto() : "");
        if (texto.contains("prazo") || texto.contains("data") || texto.contains("urgente")) {
            return "Em " + projeto + ", cliente puxou prazo: \"" + trecho + "\". Respondo com estimativa ou confirmo prioridade antes?";
        }
        if (texto.contains("valor") || texto.contains("preco") || texto.contains("preço") || texto.contains("orcamento") || texto.contains("orçamento")) {
            return "Em " + projeto + ", cliente tocou em valor: \"" + trecho + "\". Falo faixa agora ou pergunto escopo antes?";
        }
        if (texto.contains("acesso") || texto.contains("login") || texto.contains("hospedagem") || texto.contains("painel")) {
            return "Em " + projeto + ", falta acesso tecnico: \"" + trecho + "\". Pergunto por acesso agora ou sigo geral?";
        }
        if (texto.contains("whatsapp") || texto.contains("fora da plataforma") || texto.contains("por fora")) {
            return "Em " + projeto + ", cliente citou contato externo: \"" + trecho + "\". Reforco que seguimos por aqui?";
        }
        if (texto.contains("ajust") || texto.contains("alter") || texto.contains("mudan")) {
            return "Em " + projeto + ", cliente pediu ajuste: \"" + trecho + "\". Valido escopo antes de prometer prazo?";
        }
        if (texto.contains("conteudo") || texto.contains("conteúdo") || texto.contains("texto") || texto.contains("imagem")) {
            return "Em " + projeto + ", cliente falou de material: \"" + trecho + "\". Pergunto se esta pronto antes de seguir?";
        }
        return "Em " + projeto + ", esta mensagem precisa de direcao: \"" + trecho + "\". Essa resposta segue o historico ou ensino outra regra?";
    }

    static List<ConversaMensagemControleDTO> ultimasMensagensPorAutor(
        List<MensagemConversa> mensagens,
        boolean minhas
    ) {
        return mensagens.stream()
            .filter(ConversaPayloadFormatter::mensagemVisivelConversa)
            .filter(mensagem -> Boolean.TRUE.equals(mensagem.getEnviadaPorMim()) == minhas)
            .sorted(Comparator.comparing(
                (MensagemConversa msg) -> msg.getDataCriacaoMillis() != null ? msg.getDataCriacaoMillis() : 0L
            ).reversed())
            .limit(3)
            .map(mensagem -> mapearMensagemControle(mensagem, false, null))
            .toList();
    }

    static List<String> resumirMensagensIgnoradas(List<MensagemConversa> mensagens) {
        int minhas = 0;
        int apagadas = 0;
        int sistema = 0;
        int semTexto = 0;

        for (MensagemConversa mensagem : mensagens) {
            if (mensagem == null) {
                continue;
            }
            if (Boolean.TRUE.equals(mensagem.getExcluida())) {
                apagadas++;
                continue;
            }
            if (Boolean.TRUE.equals(mensagem.getSistema())) {
                sistema++;
                continue;
            }
            if (mensagem.getTexto() == null || mensagem.getTexto().isBlank()) {
                semTexto++;
                continue;
            }
            if (Boolean.TRUE.equals(mensagem.getEnviadaPorMim())) {
                minhas++;
            }
        }

        List<String> resumo = new ArrayList<>();
        if (minhas > 0) {
            resumo.add(minhas + " mensagem(ns) minha(s) detectada(s): entram no contexto, mas nao acionam resposta.");
        }
        if (apagadas > 0) {
            resumo.add(apagadas + " mensagem(ns) apagada(s) ignorada(s).");
        }
        if (sistema > 0) {
            resumo.add(sistema + " mensagem(ns) de sistema ignorada(s).");
        }
        if (semTexto > 0) {
            resumo.add(semTexto + " mensagem(ns) sem texto ignorada(s).");
        }
        if (resumo.isEmpty()) {
            resumo.add("Nenhuma mensagem ignorada.");
        }
        return resumo;
    }

    static String nomeMensagemOuFallback(MensagemConversa mensagem, String fallback) {
        if (mensagem.getNomePessoa() != null && !mensagem.getNomePessoa().isBlank()) {
            return mensagem.getNomePessoa();
        }
        return fallback;
    }

    private static String normalizarTextoConversa(String texto) {
        return texto == null ? "" : texto.toLowerCase();
    }

    private static String rotuloConversa(ConversaResumo conversa) {
        if (conversa == null) {
            return "esta conversa";
        }
        if (conversa.getNomeProjeto() != null && !conversa.getNomeProjeto().isBlank()) {
            return conversa.getNomeProjeto();
        }
        if (conversa.getIdConversa() != null) {
            return "conversa " + conversa.getIdConversa();
        }
        return "esta conversa";
    }

    private static String resumirTextoConversa(String texto) {
        if (texto == null || texto.isBlank()) {
            return "sem texto visivel";
        }
        String limpo = texto.replaceAll("\\s+", " ").trim();
        return limpo.length() <= 90 ? limpo : limpo.substring(0, 87) + "...";
    }

    private static boolean mensagemVisivelConversa(MensagemConversa mensagem) {
        if (mensagem == null) {
            return false;
        }
        if (Boolean.TRUE.equals(mensagem.getExcluida()) || Boolean.TRUE.equals(mensagem.getSistema())) {
            return false;
        }
        return mensagem.getTexto() != null && !mensagem.getTexto().isBlank();
    }

    private static ConversaMensagemControleDTO mapearMensagemControle(
        MensagemConversa mensagem,
        boolean ignorada,
        String motivoIgnorada
    ) {
        return ConversaMensagemControleDTO.builder()
            .id(mensagem.getId())
            .autor(Boolean.TRUE.equals(mensagem.getEnviadaPorMim()) ? "EU" : "CLIENTE")
            .nome(mensagem.getNomePessoa())
            .texto(mensagem.getTexto())
            .dataCriacaoMillis(mensagem.getDataCriacaoMillis())
            .ignorada(ignorada)
            .motivoIgnorada(motivoIgnorada)
            .build();
    }
}
