package com.freelasatende.service.impl;

import com.freelasatende.model.entity.Projeto;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

final class EscopoAtendimentoGuard {

    private EscopoAtendimentoGuard() {
    }

    static Optional<String> motivoBloqueio(Projeto projeto, String... contextoExtra) {
        String texto = textoProjeto(projeto, contextoExtra);
        if (texto.isBlank()) {
            return Optional.empty();
        }
        if (escopoTraducaoConteudo(texto) && !escopoTecnicoInternacionalizacao(texto)) {
            return Optional.of("fora do foco técnico: tradução, revisão ou conteúdo");
        }
        if (escopoAdministrativoContabil(texto)) {
            return Optional.of("fora do foco técnico: serviço administrativo, contábil ou fiscal");
        }
        if (escopoMarketing(texto) && !entregaProgramacaoExplicita(texto)) {
            return Optional.of("fora do foco técnico: marketing, campanha de anúncios ou social media");
        }
        if (escopoDesignConteudo(texto) && !entregaProgramacaoExplicita(texto)) {
            return Optional.of("fora do foco técnico: design, vídeo ou peça visual sem desenvolvimento");
        }
        return Optional.empty();
    }

    private static boolean escopoMarketing(String texto) {
        return texto.contains("profissional de marketing")
            || texto.contains("gestor de trafego")
            || texto.contains("trafego pago")
            || texto.contains("meta ads")
            || texto.contains("google ads")
            || contemPalavra(texto, "ads")
            || texto.contains("campanha mensal")
            || texto.contains("campanhas mensais")
            || texto.contains("gestao de campanhas")
            || texto.contains("gerenciamento de campanhas")
            || texto.contains("social media")
            || texto.contains("campanha de marketing")
            || texto.contains("estrategia de marketing")
            || texto.contains("lancamento")
            || texto.contains("venda de livros")
            || texto.contains("copywriter")
            || texto.contains("copywriting");
    }

    private static boolean escopoTraducaoConteudo(String texto) {
        return texto.contains("traducao")
            || texto.contains("traduzir")
            || texto.contains("tradutor")
            || texto.contains("revisao de texto")
            || texto.contains("revisor de texto")
            || texto.contains("legendagem")
            || texto.contains("transcricao")
            || texto.contains("redacao")
            || texto.contains("artigo para blog")
            || texto.contains("criacao de conteudo");
    }

    private static boolean escopoDesignConteudo(String texto) {
        return texto.contains("banner")
            || texto.contains("logo")
            || texto.contains("logotipo")
            || texto.contains("identidade visual")
            || texto.contains("edicao de video")
            || texto.contains("editor de video")
            || texto.contains("motion design")
            || texto.contains("motion designer")
            || texto.contains("after effects")
            || texto.contains("animacao para")
            || texto.contains("thumbnail")
            || texto.contains("post para instagram")
            || texto.contains("criativo para anuncio");
    }

    private static boolean escopoAdministrativoContabil(String texto) {
        return texto.contains("abertura de cnpj")
            || texto.contains("abrir cnpj")
            || texto.contains("regularizacao de cnpj")
            || texto.contains("regularizar cnpj")
            || texto.contains("servico contabil")
            || texto.contains("servicos contabeis")
            || texto.contains("assessoria contabil")
            || texto.contains("regularizacao fiscal")
            || texto.contains("declaracao de imposto")
            || texto.contains("declaracao imposto")
            || texto.contains("folha de pagamento");
    }

    private static boolean entregaProgramacaoExplicita(String texto) {
        return texto.contains("desenvolver site")
            || texto.contains("desenvolvimento de site")
            || texto.contains("criacao de site")
            || texto.contains("criar site")
            || texto.contains("desenvolver website")
            || texto.contains("criar website")
            || texto.contains("desenvolver landing page")
            || texto.contains("criacao de landing page")
            || texto.contains("criar landing page")
            || texto.contains("desenvolver sistema")
            || texto.contains("desenvolvimento de sistema")
            || texto.contains("implementar sistema")
            || texto.contains("desenvolver api")
            || texto.contains("implementar api")
            || texto.contains("desenvolver integracao")
            || texto.contains("implementar integracao")
            || texto.contains("programador")
            || texto.contains("desenvolvedor")
            || texto.contains("programacao");
    }

    private static boolean escopoTecnicoInternacionalizacao(String texto) {
        return texto.contains("i18n")
            || texto.contains("internacionalizacao")
            || texto.contains("multi idioma")
            || texto.contains("multidioma");
    }

    private static String textoProjeto(Projeto projeto, String... contextoExtra) {
        StringBuilder sb = new StringBuilder();
        if (projeto != null) {
            append(sb, projeto.getTitulo());
            append(sb, projeto.getDescricao());
            append(sb, projeto.getCategoria());
            append(sb, projeto.getSubcategoria());
            append(sb, projeto.getNivelExperiencia());
        }
        if (contextoExtra != null) {
            for (String texto : contextoExtra) {
                append(sb, texto);
            }
        }
        return normalizar(sb.toString());
    }

    private static void append(StringBuilder sb, String texto) {
        if (texto != null && !texto.isBlank()) {
            sb.append(' ').append(texto);
        }
    }

    private static boolean contemPalavra(String texto, String palavra) {
        return texto.matches(".*\\b" + palavra + "\\b.*");
    }

    private static String normalizar(String texto) {
        return Normalizer.normalize(texto != null ? texto : "", Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("<[^>]+>", " ")
            .replaceAll("[^a-z0-9\\s-]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
