package com.freelasatende.service.impl;

import com.freelasatende.model.dto.ProjetoInteresseResumoDTO;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;

import java.math.BigDecimal;

final class TelegramServiceMessageFormatter {

    private TelegramServiceMessageFormatter() {
    }

    static String montarMensagemDuvidas(Projeto projeto, String duvidas) {
        return String.format(
            "❓ *Dúvida antes de falar com cliente*\n\n" +
            "*%s*\n\n" +
            "ID: %d\n" +
            "Categoria: %s\n" +
            "Valor: %s\n\n" +
            "🧠 *Leitura da IA*\n" +
            "Motivo: Projeto parece possível, mas falta dado essencial antes de proposta segura.\n" +
            "Convicção: MEDIA COM DUVIDA\n" +
            "O que falta: %s\n" +
            "Atitude: conversar com você antes de transformar isso em pergunta ao cliente.\n\n" +
            "Nada é enviado sem aprovação.",
            escaparMarkdown(valorOuPadrao(projeto.getTitulo(), "Projeto sem título")),
            projeto.getId(),
            escaparMarkdown(valorOuPadrao(projeto.getCategoria(), "Não informada pela plataforma")),
            escaparMarkdown(formatarMoeda(projeto.getOrcamento())),
            escaparMarkdown(valorOuPadrao(duvidas, "Sem dúvida registrada."))
        );
    }

    static String montarMensagemProposta(Projeto projeto, Proposta proposta) {
        String conviccao = proposta != null
            && proposta.getValorProposto() != null
            && (proposta.getMediaMercado() != null || projeto.getOrcamento() != null)
            ? "ALTA"
            : "MEDIA";
        return String.format(
            "💰 *Proposta sugerida*\n\n" +
            "*%s*\n\n" +
            "ID: %d\n" +
            "Valor proposto: %s\n" +
            "Mínimo: %s\n" +
            "Média mercado: %s\n\n" +
            "🧠 *Leitura da IA*\n" +
            "Motivo: já existe base para rascunhar proposta, mas a decisão continua humana.\n" +
            "Convicção: %s\n" +
            "Sinais: valor proposto, mínimo e média foram separados para revisão.\n" +
            "Atenção: validar se valor, escopo e tom combinam com sua estratégia.\n" +
            "Atitude: aguardar aprovação, reescrita ou orientação de ensino.\n\n" +
            "Nada é enviado sem aprovação.",
            escaparMarkdown(valorOuPadrao(projeto.getTitulo(), "Projeto sem título")),
            projeto.getId(),
            escaparMarkdown(formatarMoeda(proposta != null ? proposta.getValorProposto() : null)),
            escaparMarkdown(formatarMoeda(proposta != null ? proposta.getValorMinimo() : null)),
            escaparMarkdown(formatarMoeda(proposta != null ? proposta.getMediaMercado() : null)),
            conviccao
        );
    }

    static String montarMensagemProjetoInteressante(Projeto projeto, ProjetoInteresseResumoDTO resumo) {
        String categoria = projeto.getCategoria() != null ? projeto.getCategoria() : projeto.getSubcategoria();
        String valor = projeto.getOrcamento() != null
            ? "R$ " + projeto.getOrcamento()
            : (projeto.getValorMinimo() != null ? "A partir de R$ " + projeto.getValorMinimo() : "Não informado pela plataforma");
        String motivo = resumo != null
            ? valorOuPadrao(resumo.getMotivoInteresse(), "Projeto passou na avaliação técnica inicial.")
            : "Projeto passou na avaliação técnica inicial.";
        String conviccao = resumo != null ? valorOuPadrao(resumo.getNivelConviccao(), "MEDIA") : "MEDIA";
        return String.format(
            "🎯 *Projeto interessante*\n\n" +
            "*%s*\n\n" +
            "ID: %d\n" +
            "Categoria: %s\n" +
            "Valor: %s\n" +
            "Propostas: %s\n" +
            "Nível: %s\n\n" +
            "🧠 *Leitura da IA*\n" +
            "Motivo: %s\n" +
            "Convicção: %s\n" +
            "Próximo: vou abrir uma pendência separada com resposta e botões.",
            escaparMarkdown(projeto.getTitulo()),
            projeto.getId(),
            escaparMarkdown(categoria != null ? categoria : "Não informada pela plataforma"),
            escaparMarkdown(valor),
            projeto.getNumeroPropostas() != null ? projeto.getNumeroPropostas() : "Não informado",
            escaparMarkdown(projeto.getNivelExperiencia() != null ? projeto.getNivelExperiencia() : "Não informado"),
            escaparMarkdown(motivo),
            escaparMarkdown(conviccao)
        );
    }

    static String escaparMarkdown(String texto) {
        if (texto == null) return "";
        return texto
            .replace("_", "\\_")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("~", "\\~")
            .replace("`", "\\`")
            .replace(">", "\\>")
            .replace("#", "\\#")
            .replace("+", "\\+")
            .replace("-", "\\-")
            .replace("=", "\\=")
            .replace("|", "\\|")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace(".", "\\.")
            .replace("!", "\\!");
    }

    static String removerMarkdown(String texto) {
        if (texto == null) return "";
        return texto.replace("*", "").replace("_", "").replace("`", "");
    }

    private static String valorOuPadrao(String valor, String padrao) {
        return valor == null || valor.isBlank() ? padrao : valor;
    }

    private static String formatarMoeda(BigDecimal valor) {
        if (valor == null) {
            return "Não informado pela plataforma";
        }
        return "R$ " + valor;
    }
}
