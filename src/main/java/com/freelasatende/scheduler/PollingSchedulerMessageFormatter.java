package com.freelasatende.scheduler;

import com.freelasatende.model.dto.AcaoControleDTO;

import java.util.List;

final class PollingSchedulerMessageFormatter {

    private PollingSchedulerMessageFormatter() {
    }

    static String mensagemCheckinEnsino(int projetosLidos, long pendenciasAbertas, AcaoControleDTO acao) {
        return "🧑‍💻 Pergunta do junior\n\n"
            + "Estou rodando. Li " + projetosLidos + " projeto(s) e conferi a inbox.\n"
            + "Tenho " + pendenciasAbertas + " pendência(s) abertas.\n"
            + "Escrita ao cliente: BLOQUEADA.\n\n"
            + "Foco #" + valor(acao.getId()) + "\n"
            + blocoCodigo(texto(acao.getTituloProjeto()), 180) + "\n\n"
            + "Mensagem-fonte:\n"
            + blocoCodigo(textoCurto(acao.getMensagemGatilhoTexto(), "sem mensagem-fonte salva"), 260) + "\n\n"
            + "Minha leitura:\n"
            + blocoCodigo(textoCurto(acao.getAnaliseIa(), texto(acao.getResumoInterno())), 260) + "\n\n"
            + "O que ainda preciso saber:\n"
            + blocoCodigo(perguntaCheckin(acao), 260) + "\n\n"
            + "Mensagem aguardando revisão:\n"
            + blocoCodigo(textoCurto(acao.getTextoClientePreview(), "sem mensagem"), 420) + "\n\n"
            + "Pode me responder normal aqui. Se for decisão de envio, sigo pelos botões do card.";
    }

    private static String perguntaCheckin(AcaoControleDTO acao) {
        String pergunta = primeiroTexto(
            acao.getPerguntaParaUsuario(),
            primeiroItem(acao.getDuvidasInternas()),
            primeiroItem(acao.getInformacoesPendentes()),
            acao.getRegraAprendizadoSugerida()
        );
        return textoCurto(pergunta, "qual regra devo usar quando aparecer projeto parecido?");
    }

    private static String primeiroTexto(String... valores) {
        if (valores == null) {
            return null;
        }
        for (String valor : valores) {
            if (valor != null && !valor.isBlank()) {
                return valor;
            }
        }
        return null;
    }

    private static String primeiroItem(List<String> valores) {
        if (valores == null) {
            return null;
        }
        return valores.stream()
            .filter(valor -> valor != null && !valor.isBlank())
            .findFirst()
            .orElse(null);
    }

    private static String textoCurto(String valor, String fallback) {
        return limitarTexto(texto(valor != null ? valor : fallback), 260);
    }

    private static String texto(String valor) {
        return valor == null || valor.isBlank() ? "-" : valor.trim();
    }

    private static String valor(Long valor) {
        return valor != null ? valor.toString() : "-";
    }

    private static String limitarTexto(String texto, int limite) {
        if (texto == null || texto.length() <= limite) {
            return texto;
        }
        return texto.substring(0, Math.max(0, limite - 3)) + "...";
    }

    private static String blocoCodigo(String texto, int limite) {
        return "```\n" + limparCodigo(limitarTexto(texto(texto), limite)) + "\n```";
    }

    private static String limparCodigo(String texto) {
        if (texto == null || texto.isBlank()) {
            return "-";
        }
        return texto.replace("```", "'''").replace("`", "'");
    }
}
