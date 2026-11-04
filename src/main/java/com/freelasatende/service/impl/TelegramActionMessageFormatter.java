package com.freelasatende.service.impl;

import com.freelasatende.config.OperacaoDefaults;
import com.freelasatende.model.dto.AcaoControleDTO;
import com.freelasatende.model.dto.ConversaMensagemControleDTO;
import com.freelasatende.model.dto.EnsinoFocoDTO;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.enums.PlataformaFreela;
import org.springframework.web.util.HtmlUtils;

import java.util.List;

final class TelegramActionMessageFormatter {

    private static final int LIMITE_MENSAGEM_TELEGRAM = OperacaoDefaults.LIMITE_MENSAGEM_TELEGRAM;

    private TelegramActionMessageFormatter() {
    }

    static String formatarResumoAcao(AcaoControleDTO acao) {
        return "%s#%d | %s | %s\nPlataforma: %s\nProjeto: %s\nAutoria: %s\nGatilho cliente: %s\nMinhas falas detectadas: %s\nConfiança: %s | Qualidade: %s/100 | Guardião: %s\nLeitura IA: %s\nAtitude: %s\nConversa ensino: %s\nPergunta para voce: %s\nResposta ao cliente:\n%s\n"
            .formatted(
                acao.isFocoEnsinoAtual() ? "FOCO ATUAL\n" : "",
                acao.getId(),
                escaparTelegram(valorObjeto(acao.getTipo())),
                escaparTelegram(valorObjeto(acao.getStatus())),
                escaparTelegram(plataformaLabel(acao)),
                escaparTelegram(valorTexto(acao.getTituloProjeto())),
                acao.isAutoriaConfiavel() ? "confiável" : "parcial",
                escaparTelegram(limitar(valorTexto(acao.getMensagemGatilhoTexto()), 140)),
                resumoMensagens(acao.getUltimasMensagensMinhas(), 120),
                escaparTelegram(valorObjeto(acao.getNivelConfianca())),
                acao.getScoreConfianca() != null ? acao.getScoreConfianca() : "-",
                escaparTelegram(valorObjeto(acao.getStatusGuardiao())),
                escaparTelegram(limitar(leituraIa(acao), 220)),
                escaparTelegram(limitar(atitudeIa(acao), 180)),
                escaparTelegram(statusConversaEnsino(acao)),
                escaparTelegram(limitar(acao.getPerguntaParaUsuario(), 180)),
                blocoCodigoTelegram(acao.getTextoClientePreview(), 420)
            );
    }

    static String formatarFocoEnsino(EnsinoFocoDTO foco) {
        if (foco == null || !foco.isAtivo()) {
            return "Foco de ensino\n\nStatus: SEM_FOCO\n" + valorTexto(foco != null ? foco.getMensagem() : null);
        }
        return "Foco de ensino\n\n"
            + "Status: " + escaparTelegram(valorTexto(foco.getStatus())) + "\n"
            + "Ação: #" + valorLong(foco.getAcaoId()) + "\n"
            + "Tipo: " + escaparTelegram(valorTexto(foco.getTipo() != null ? foco.getTipo().name() : null)) + "\n"
            + "Projeto: " + escaparTelegram(valorTexto(foco.getTituloProjeto())) + "\n"
            + "Projeto ID: " + valorLong(foco.getProjetoId()) + "\n"
            + "Conversa ID: " + valorLong(foco.getConversaId()) + "\n\n"
            + "Resposta em foco:\n" + blocoCodigoTelegram(foco.getTextoClientePreview(), 700) + "\n\n"
            + valorTexto(foco.getMensagem());
    }

    static String formatarDetalheAcao(AcaoControleDTO acao) {
        return "Acao #" + acao.getId()
            + " | " + escaparTelegram(valorObjeto(acao.getTipo()))
            + " | " + escaparTelegram(valorObjeto(acao.getStatus())) + "\n\n"
            + escaparTelegram(plataformaLabel(acao)) + "\n"
            + escaparTelegram(valorTexto(acao.getTituloProjeto())) + "\n"
            + escaparTelegram(valorTexto(acao.getNomeCliente())) + "\n\n"
            + "Gatilho\n"
            + blocoCodigoTelegram(acao.getMensagemGatilhoTexto(), 500) + "\n\n"
            + "Resposta ao cliente\n"
            + blocoCodigoTelegram(acao.getTextoClientePreview(), 1200) + "\n\n"
            + blocoHistorico(acao)
            + blocoLeituraIa(acao);
    }

    private static String blocoLeituraIa(AcaoControleDTO acao) {
        StringBuilder sb = new StringBuilder("Leitura\n");
        appendLinha(sb, "Motivo", leituraIa(acao), 240);
        appendLinha(sb, "Atitude", atitudeIa(acao), 180);
        appendLinha(sb, "Pendente", listaOuNenhum(acao.getInformacoesPendentes()), 180);
        appendLinha(sb, "Pergunta", acao.getPerguntaParaUsuario(), 180);
        appendLinha(sb, "Risco", listaOuNenhum(acao.getRiscosDecisao()), 180);
        return sb.toString();
    }

    private static String leituraIa(AcaoControleDTO acao) {
        if (acao.getAnaliseIa() != null && !acao.getAnaliseIa().isBlank()) {
            return acao.getAnaliseIa();
        }
        if (acao.getEntendimentoIa() != null && !acao.getEntendimentoIa().isBlank()) {
            return acao.getEntendimentoIa();
        }
        return "Sem leitura registrada.";
    }

    private static String atitudeIa(AcaoControleDTO acao) {
        if (acao.getAtitudeSugerida() != null && !acao.getAtitudeSugerida().isBlank()) {
            return acao.getAtitudeSugerida();
        }
        if (acao.getProximaAcaoSugerida() != null && !acao.getProximaAcaoSugerida().isBlank()) {
            return acao.getProximaAcaoSugerida();
        }
        return "Revisar pendência antes de qualquer envio.";
    }

    private static String statusConversaEnsino(AcaoControleDTO acao) {
        if (!acao.isConversaEnsinoObrigatoria()) {
            return "NAO_OBRIGATORIA";
        }
        return acao.isConversaEnsinoRespondida() ? "RESPONDIDA" : "OBRIGATORIA";
    }

    private static String plataformaLabel(AcaoControleDTO acao) {
        if (acao.getPlataformaLabel() != null && !acao.getPlataformaLabel().isBlank()) {
            return acao.getPlataformaLabel();
        }
        return PlataformaFreela.normalizar(acao.getPlataforma()).label();
    }

    private static String blocoHistorico(AcaoControleDTO acao) {
        StringBuilder sb = new StringBuilder("Historico\n");
        appendMensagemCurta(sb, "Cliente", acao.getUltimasMensagensCliente());
        appendMensagemCurta(sb, "Eu", acao.getUltimasMensagensMinhas());
        return sb.append('\n').toString();
    }

    private static void appendMensagemCurta(StringBuilder sb, String rotulo, List<ConversaMensagemControleDTO> mensagens) {
        if (mensagens == null || mensagens.isEmpty()) {
            return;
        }
        ConversaMensagemControleDTO mensagem = mensagens.get(0);
        sb.append("- ")
            .append(rotulo)
            .append(": ")
            .append(escaparTelegram(limitar(mensagem.getTexto(), 220)))
            .append('\n');
    }

    private static void appendLinha(StringBuilder sb, String rotulo, String valor, int limite) {
        String texto = valorTexto(valor);
        if ("-".equals(texto) || "nenhum".equalsIgnoreCase(texto)) {
            return;
        }
        sb.append("- ")
            .append(rotulo)
            .append(": ")
            .append(escaparTelegram(limitar(texto, limite)))
            .append('\n');
    }

    static String formatarMensagemDiagnostico(MensagemConversa mensagem) {
        String autor = autorDiagnostico(mensagem);
        String nome = valorTexto(mensagem.getNomePessoa());
        String texto = limitar(mensagem.getTexto(), 220);
        String sufixo = "IGNORADA".equals(autor)
            ? " (" + motivoIgnorada(mensagem) + ")"
            : "";
        return "- " + autor + " | " + escaparTelegram(nome) + ": " + escaparTelegram(valorTexto(texto)) + sufixo;
    }

    private static String autorDiagnostico(MensagemConversa mensagem) {
        if (Boolean.TRUE.equals(mensagem.getExcluida())
            || Boolean.TRUE.equals(mensagem.getSistema())
            || mensagem.getTexto() == null
            || mensagem.getTexto().isBlank()) {
            return "IGNORADA";
        }
        return Boolean.TRUE.equals(mensagem.getEnviadaPorMim()) ? "EU" : "CLIENTE";
    }

    private static String motivoIgnorada(MensagemConversa mensagem) {
        if (Boolean.TRUE.equals(mensagem.getExcluida())) {
            return "apagada";
        }
        if (Boolean.TRUE.equals(mensagem.getSistema())) {
            return "sistema";
        }
        if (mensagem.getTexto() == null || mensagem.getTexto().isBlank()) {
            return "sem texto";
        }
        return "nao acionavel";
    }

    private static String resumoMensagens(List<ConversaMensagemControleDTO> mensagens, int limiteTexto) {
        if (mensagens == null || mensagens.isEmpty()) {
            return "-";
        }
        ConversaMensagemControleDTO primeira = mensagens.get(0);
        String texto = limitar(primeira.getTexto(), limiteTexto);
        String extras = mensagens.size() > 1 ? " (+" + (mensagens.size() - 1) + ")" : "";
        return escaparTelegram(valorTexto(primeira.getNome()) + ": " + valorTexto(texto) + extras);
    }

    static String listaOuNenhum(List<String> itens) {
        return itens == null || itens.isEmpty() ? "nenhum" : String.join(", ", itens);
    }

    static String blocoCodigoTelegram(String texto, int limite) {
        return "```\n" + limparTextoCodigoTelegram(limitar(texto, limite)) + "\n```";
    }

    private static String limparTextoCodigoTelegram(String texto) {
        if (texto == null || texto.isBlank()) {
            return "-";
        }
        return HtmlUtils.htmlUnescape(texto).replace("```", "'''").replace("`", "'");
    }

    static String valorTexto(String texto) {
        return texto == null || texto.isBlank() ? "-" : HtmlUtils.htmlUnescape(texto);
    }

    static String valorObjeto(Object valor) {
        if (valor == null) {
            return "-";
        }
        String texto = HtmlUtils.htmlUnescape(String.valueOf(valor));
        return texto.isBlank() ? "-" : texto;
    }

    static String valorLong(Long valor) {
        return valor != null ? valor.toString() : "-";
    }

    static String escaparTelegram(String texto) {
        if (texto == null) {
            return "-";
        }
        return texto
            .replace("\\", "\\\\")
            .replace("_", "\\_")
            .replace("*", "\\*")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("`", "\\`");
    }

    static String limitar(String texto, int limite) {
        if (texto == null || texto.isBlank()) {
            return "-";
        }
        if (texto.length() <= limite) {
            return texto;
        }
        return texto.substring(0, limite).trim() + "...";
    }

    static String limitarMensagemTelegram(String texto) {
        if (texto == null) {
            return "";
        }
        if (texto.length() <= LIMITE_MENSAGEM_TELEGRAM) {
            return texto;
        }
        String cortado = texto.substring(0, LIMITE_MENSAGEM_TELEGRAM);
        if (contar(cortado, "```") % 2 != 0) {
            cortado += "\n```";
        }
        return cortado
            + "\n\n[texto muito longo, cortado apenas por limite tecnico do Telegram]";
    }

    private static int contar(String texto, String trecho) {
        int total = 0;
        int index = 0;
        while ((index = texto.indexOf(trecho, index)) >= 0) {
            total++;
            index += trecho.length();
        }
        return total;
    }

}
