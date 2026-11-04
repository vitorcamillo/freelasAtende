package com.freelasatende.service.impl;

import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.entity.Proposta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

final class AutoAtendimentoTextHelper {

    private AutoAtendimentoTextHelper() {
    }

    static String descreverOrigemValor(Projeto projeto, Proposta proposta) {
        BigDecimal media = projeto != null ? projeto.getMediaProposta() : null;
        BigDecimal orcamento = projeto != null ? projeto.getOrcamento() : null;
        BigDecimal valorMinimo = proposta != null ? proposta.getValorMinimo() : null;
        BigDecimal valorFinal = proposta != null ? proposta.getValorProposto() : null;
        BigDecimal base;
        String origem;

        if (media != null && media.compareTo(BigDecimal.ZERO) > 0) {
            base = media.multiply(BigDecimal.valueOf(1.1d));
            Integer numeroPropostas = projeto != null ? projeto.getNumeroPropostas() : null;
            origem = "media de propostas visiveis (+10%)"
                + (numeroPropostas != null ? " em " + numeroPropostas + " propostas" : "");
        } else if (orcamento != null) {
            base = orcamento;
            origem = "orcamento informado pelo cliente";
        } else {
            base = null;
            origem = "sem base real de valor na plataforma";
        }

        StringBuilder descricao = new StringBuilder(origem)
            .append(" | Base: ")
            .append(base != null ? formatarMoeda(base) : "não informada");

        if (valorFinal != null) {
            descricao.append(" | Final: ").append(formatarMoeda(valorFinal));
            if (valorMinimo != null
                && base != null
                && valorFinal.compareTo(valorMinimo) == 0
                && base.compareTo(valorMinimo) < 0) {
                descricao.append(" (ajustado para valor minimo do projeto: ")
                    .append(formatarMoeda(valorMinimo))
                    .append(")");
            }
        }

        return descricao.toString();
    }

    static String formatarMoeda(BigDecimal valor) {
        if (valor == null) {
            return "Não informado pela plataforma";
        }
        return "R$ " + valor.setScale(2, RoundingMode.HALF_UP).toPlainString().replace(".", ",");
    }

    static String formatarHistoricoConversa(List<MensagemConversa> mensagens, int limiteMensagens, int limiteChars) {
        List<MensagemConversa> visiveis = mensagens.stream()
            .filter(AutoAtendimentoTextHelper::mensagemVisivelConversa)
            .sorted(Comparator.comparing(
                mensagem -> mensagem.getDataCriacaoMillis() != null ? mensagem.getDataCriacaoMillis() : 0L
            ))
            .toList();
        int inicio = Math.max(0, visiveis.size() - limiteMensagens);
        StringBuilder sb = new StringBuilder();
        if (inicio > 0) {
            sb.append("[historico anterior omitido: ")
                .append(inicio)
                .append(" mensagem(ns)]\n");
        }
        for (MensagemConversa mensagem : visiveis.subList(inicio, visiveis.size())) {
            sb.append(Boolean.TRUE.equals(mensagem.getEnviadaPorMim()) ? "Eu" : "Cliente")
                .append(": ")
                .append(limitarTextoParaContexto(mensagem.getTexto(), limiteChars))
                .append("\n");
        }
        return sb.toString();
    }

    /**
     * Alerta de contato liberado. O texto integral vai junto porque a extracao
     * por campo e tolerante: se a plataforma mudar o formato, o operador ainda
     * recebe o conteudo completo e nao perde o contato.
     */
    static String formatarContatoLiberado(
        PlataformaFreela plataforma,
        String nomeProjeto,
        String nomeClienteConversa,
        ContatoLiberadoDetector.ContatoLiberado contato
    ) {
        String nome = !contato.nome().isBlank()
            ? contato.nome()
            : (nomeClienteConversa == null ? "" : nomeClienteConversa.trim());
        StringBuilder sb = new StringBuilder();
        sb.append("📇 Consegui os contatos do cliente!\n\n");
        sb.append("Plataforma: ").append(plataforma == null ? "-" : plataforma.key()).append("\n");
        sb.append("Projeto: ").append(nomeProjeto == null || nomeProjeto.isBlank() ? "-" : nomeProjeto).append("\n");
        sb.append("Cliente: ").append(nome.isBlank() ? "-" : nome).append("\n");
        if (!contato.email().isBlank()) {
            sb.append("E-mail: ").append(contato.email()).append("\n");
        }
        if (!contato.telefone().isBlank()) {
            sb.append("Telefone: ").append(contato.telefone()).append("\n");
        }
        sb.append("\nMensagem da plataforma:\n").append(contato.textoIntegral());
        return neutralizarMarkdown(sb.toString());
    }

    /**
     * O Telegram envia com parse_mode=Markdown e o texto da plataforma vem com
     * crase e sublinhado (titulo de projeto entre crases, por exemplo). Isso
     * quebrava o parse e so passava pelo fallback sem formatacao -- medido em
     * producao: 4 erros 400 "can't parse entities" em 15/09. Aqui os caracteres
     * de marcacao viram texto comum antes do envio.
     */
    static String neutralizarMarkdown(String texto) {
        return texto == null ? "" : texto
            .replace("`", "'")
            .replace("*", "")
            .replace("_", "-")
            .replace("[", "(")
            .replace("]", ")");
    }

    static String formatarContextoProjeto(Projeto projeto) {
        if (projeto == null) {
            return "";
        }
        return "Título: " + valorTexto(projeto.getTitulo()) + "\n"
            + "Categoria: " + valorTexto(projeto.getCategoria()) + "\n"
            + "Subcategoria: " + valorTexto(projeto.getSubcategoria()) + "\n"
            + "Nível: " + valorTexto(projeto.getNivelExperiencia()) + "\n"
            + "Valor informado: " + formatarMoeda(projeto.getOrcamento()) + "\n"
            + "Valor mínimo: " + formatarMoeda(projeto.getValorMinimo()) + "\n"
            + "Média proposta: " + formatarMoeda(projeto.getMediaProposta()) + "\n"
            + "Propostas: " + (projeto.getNumeroPropostas() != null ? projeto.getNumeroPropostas() : "-") + "\n"
            + "Interessados: " + (projeto.getInteressados() != null ? projeto.getInteressados() : "-") + "\n"
            + "Cliente: " + valorTexto(projeto.getNomeCliente()) + "\n"
            + "Descrição curta: " + valorTexto(projeto.getDescricaoCurta()) + "\n"
            + "Descrição completa: " + limitarTextoParaContexto(projeto.getDescricao(), 1600);
    }

    static String limitarTextoParaContexto(String texto, int limite) {
        if (texto == null || texto.isBlank()) {
            return "-";
        }
        if (texto.length() <= limite) {
            return texto;
        }
        return texto.substring(0, limite).trim() + "...";
    }

    static String duvidaParaColetarValorReal() {
        return "Qual escopo você quer nesta primeira entrega e qual prazo ideal para publicar?";
    }

    static String primeiraDuvida(List<String> duvidas) {
        if (duvidas == null || duvidas.isEmpty() || duvidas.get(0) == null || duvidas.get(0).isBlank()) {
            return "confirmar o ponto essencial antes de enviar";
        }
        return duvidas.get(0);
    }

    static String extrairSlug(String urlProjeto, Long projetoId) {
        if (urlProjeto == null || urlProjeto.isBlank()) {
            return null;
        }

        int indiceProject = urlProjeto.indexOf("/project/");
        if (indiceProject >= 0) {
            String trecho = urlProjeto.substring(indiceProject + 9);
            String sufixo = "-" + projetoId;
            int fim = trecho.indexOf(sufixo);
            return fim > 0 ? trecho.substring(0, fim) : null;
        }

        int indiceJob = urlProjeto.indexOf("/job/");
        int tamanhoPrefixo = 5;
        if (indiceJob < 0) {
            indiceJob = urlProjeto.indexOf("/jobs/");
            tamanhoPrefixo = 6;
        }
        if (indiceJob < 0) {
            return null;
        }
        String trecho = urlProjeto.substring(indiceJob + tamanhoPrefixo);
        int fim = trecho.indexOf('?');
        if (fim >= 0) {
            trecho = trecho.substring(0, fim);
        }
        fim = trecho.indexOf('#');
        if (fim >= 0) {
            trecho = trecho.substring(0, fim);
        }
        return trecho.isBlank() ? null : trecho.replaceFirst("/+$", "");
    }

    static String safeComentario(String comentario) {
        return comentario == null || comentario.isBlank() ? "sem comentário" : comentario;
    }

    static String escaparMarkdown(String texto) {
        if (texto == null) {
            return "";
        }
        return texto.replace("_", "\\_")
            .replace("*", "\\*")
            .replace("[", "\\[")
            .replace("`", "\\`");
    }

    static String previewLogEnvio(String mensagem) {
        if (mensagem == null) {
            return "";
        }
        String normalizada = mensagem.replaceAll("\\s+", " ").trim();
        if (normalizada.length() <= 180) {
            return normalizada;
        }
        return normalizada.substring(0, 180).trim() + "...";
    }

    static String anexarObservacao(String atual, String nova) {
        String limpa = nova == null ? "" : nova.trim();
        if (limpa.isBlank()) {
            return atual;
        }
        if (atual == null || atual.isBlank()) {
            return limpa;
        }
        if (atual.contains(limpa)) {
            return atual;
        }
        return atual + "\n" + limpa;
    }

    static String valorTexto(String texto) {
        return texto == null || texto.isBlank() ? "-" : texto;
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
}
