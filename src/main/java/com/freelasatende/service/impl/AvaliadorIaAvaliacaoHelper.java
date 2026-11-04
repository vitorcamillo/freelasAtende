package com.freelasatende.service.impl;

import com.freelasatende.model.entity.Projeto;
import com.freelasatende.service.interfaceServico.IaProvider;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

final class AvaliadorIaAvaliacaoHelper {

    private AvaliadorIaAvaliacaoHelper() {
    }

    static AvaliacaoInterpretada interpretarRespostaAvaliacao(Projeto projeto, String resposta) {
        String texto = resposta != null ? resposta.trim() : "";
        String upper = texto.toUpperCase(Locale.ROOT);

        if (upper.startsWith("SIM")) {
            Optional<String> bloqueioEscopo = EscopoAtendimentoGuard.motivoBloqueio(projeto);
            if (bloqueioEscopo.isPresent()) {
                return new AvaliacaoInterpretada(
                    false,
                    bloqueioEscopo.get(),
                    "triagem inicial bloqueada por escopo fora do foco tecnico",
                    null
                );
            }
            if (temBloqueadorCriticoParaAbordagemInicial(projeto)) {
                return new AvaliacaoInterpretada(
                    false,
                    "projeto exige capacidade especializada ou envolve risco sensivel",
                    "triagem inicial bloqueada por risco especializado identificado no projeto",
                    null
                );
            }
            return new AvaliacaoInterpretada(true, null, null, extrairMotivoInteresse(texto));
        }

        if (upper.startsWith("NAO") || upper.startsWith("NÃO")) {
            String motivo = extrairMotivoRecusa(texto);
            if (motivoApenasPreco(motivo)
                && projetoTemEntregaTecnicaAtendivel(projeto)) {
                return new AvaliacaoInterpretada(
                    true,
                    null,
                    "preco nao bloqueia abordagem inicial de projeto tecnicamente atendivel",
                    motivo
                );
            }
            if (projetoWebCompativelParaAbordagemInicial(projeto)) {
                return new AvaliacaoInterpretada(
                    true,
                    null,
                    "projeto web compativel liberado para abordagem inicial; escopo sera qualificado depois",
                    motivo
                );
            }
            if (motivoApenasValorAusente(motivo) && projetoWebSimplesSeguro(projeto)) {
                return new AvaliacaoInterpretada(
                    true,
                    null,
                    "projeto web simples dentro do escopo; valor ausente vira pergunta pendente",
                    motivo
                );
            }
            return new AvaliacaoInterpretada(
                false,
                motivo != null ? motivo : "IA recusou sem motivo claro",
                null,
                null
            );
        }

        if (projetoWebCompativelParaAbordagemInicial(projeto)) {
            return new AvaliacaoInterpretada(
                true,
                null,
                "resposta fora do formato, mas projeto web compativel pode receber abordagem inicial",
                "projeto web compativel sem bloqueadores criticos para triagem inicial"
            );
        }

        return new AvaliacaoInterpretada(
            false,
            "IA respondeu fora do formato de decisão",
            "resposta sem SIM/NAO: " + limitarTexto(texto, 160),
            null
        );
    }

    /**
     * Diz se a resposta tem decisao utilizavel (SIM/NAO), mesmo com variacoes
     * de acento, maiusculas ou prefixo antes da palavra. Usada para decidir
     * entre aceitar a resposta ou pular para o proximo modelo da cadeia.
     */
    public static boolean decisaoUtil(String resposta) {
        if (resposta == null || resposta.isBlank()) {
            return false;
        }
        String up = resposta.trim().toUpperCase(Locale.ROOT).replace("Á", "A");
        if (up.startsWith("SIM") || up.startsWith("NAO")) {
            return true;
        }
        return up.matches("(?s).*(SIM|NAO)\s*[-–:].*");
    }

    /**
     * Quando a resposta nao tem decisao utilizavel, repete a chamada pulando o
     * modelo que respondeu mal (cadeia de fallback do provider). Se nenhum
     * modelo alternativo devolver decisao, devolve a resposta original e a
     * interpretacao por regras locais decide.
     */
    public static String respostaComFallbackDeFormato(String resposta, String prompt, IaProvider iaProvider)
            throws IaProvider.IaProviderException {
        if (decisaoUtil(resposta)) {
            return resposta;
        }
        String alternativa = iaProvider.enviarPulandoModelo(prompt, 1500, iaProvider.getModelo());
        return decisaoUtil(alternativa) ? alternativa : resposta;
    }

    private static String extrairMotivoInteresse(String resposta) {
        if (resposta == null || resposta.isBlank()) {
            return null;
        }
        String texto = resposta.trim();
        if (!texto.toUpperCase(Locale.ROOT).startsWith("SIM")) {
            return null;
        }
        String parte = texto.length() >= 3 ? texto.substring(3).trim() : "";
        if (parte.startsWith("-") || parte.startsWith(":")) {
            parte = parte.substring(1).trim();
        }
        return parte.isBlank() ? null : limitarTexto(parte, 180);
    }

    private static String extrairMotivoRecusa(String resposta) {
        if (resposta == null || resposta.isBlank()) {
            return null;
        }
        String texto = resposta.trim();
        String normalizado = normalizarBusca(texto);
        int inicio = normalizado.startsWith("nao") ? 3 : (normalizado.startsWith("não") ? 4 : 0);
        String parte = texto.length() >= inicio ? texto.substring(inicio).trim() : "";
        if (parte.startsWith("-") || parte.startsWith(":")) {
            parte = parte.substring(1).trim();
        }
        return parte.isBlank() ? null : parte;
    }

    private static boolean motivoApenasValorAusente(String motivo) {
        if (motivo == null || motivo.isBlank()) {
            return false;
        }
        String texto = normalizarBusca(motivo);
        boolean falaDeValorAusente = texto.contains("valor nao informado")
            || texto.contains("sem valor informado")
            || texto.contains("valor nao foi informado")
            || texto.contains("sem valor definido")
            || texto.contains("orcamento nao informado")
            || texto.contains("sem orcamento");
        if (!falaDeValorAusente) {
            return false;
        }
        return !(texto.contains("falta de expertise")
            || texto.contains("sem expertise")
            || texto.contains("fora do escopo")
            || texto.contains("nao dominamos")
            || texto.contains("complexidade")
            || texto.contains("hardware")
            || texto.contains("nativo")
            || texto.contains("especializado")
            || texto.contains("ilegal")
            || texto.contains("risco"));
    }

    private static boolean motivoApenasPreco(String motivo) {
        if (motivo == null || motivo.isBlank()) {
            return false;
        }
        String texto = normalizarBusca(motivo);
        boolean falaDePreco = texto.contains("orcamento")
            || texto.contains("preco")
            || texto.contains("valor")
            || texto.contains("budget");
        boolean precoBloqueou = texto.contains("abaixo")
            || texto.contains("baixo")
            || texto.contains("insuficiente")
            || texto.contains("incompativel")
            || texto.contains("minimo")
            || texto.contains("patamar")
            || texto.contains("faixa");
        if (!falaDePreco || !precoBloqueou) {
            return false;
        }
        return !(texto.contains("falta de expertise")
            || texto.contains("sem expertise")
            || texto.contains("nao temos expertise")
            || texto.contains("fora do escopo")
            || texto.contains("fora da stack")
            || texto.contains("fora do stack")
            || texto.contains("tecnologia incompativel")
            || texto.contains("app nativo")
            || texto.contains("aplicativo nativo")
            || texto.contains("desktop")
            || texto.contains("hardware")
            || texto.contains("biometr")
            || texto.contains("financeir")
            || texto.contains("juridic")
            || texto.contains("seguranca sensivel")
            || texto.contains("risco sensivel")
            || texto.contains("ilegal"));
    }

    private static boolean projetoTemEntregaTecnicaAtendivel(Projeto projeto) {
        String texto = textoProjeto(projeto);
        if (texto.isBlank() || temBloqueadorCriticoParaAbordagemInicial(projeto)) {
            return false;
        }
        return texto.contains("desenvolv")
            || texto.contains("programador")
            || texto.contains("programacao")
            || texto.contains("implementar")
            || texto.contains("integracao")
            || texto.contains("integrar")
            || texto.contains("api")
            || texto.contains("homologar")
            || texto.contains("corrigir")
            || texto.contains("ajuste tecnico")
            || texto.contains("migrar sistema");
    }

    private static boolean projetoWebSimplesSeguro(Projeto projeto) {
        String texto = textoProjeto(projeto);
        if (texto.isBlank() || temBloqueadorCriticoParaAbordagemInicial(projeto)) {
            return false;
        }

        boolean sinalWebSimples = texto.contains("wordpress")
            || texto.contains("landing page")
            || texto.contains(" landing ")
            || texto.contains(" lp ")
            || texto.contains("site institucional")
            || texto.contains("website")
            || texto.contains("site profissional")
            || texto.contains("web design")
            || texto.contains("html")
            || texto.contains("css")
            || texto.contains("pagina de captura")
            || texto.contains("pagina do produto")
            || texto.contains("one-page")
            || texto.contains("botao para abrir o whatsapp")
            || texto.contains("formulario de contato");

        boolean claroParaExecutar = texto.contains("wordpress")
            || texto.contains("template")
            || texto.contains("hostgator")
            || texto.contains("hostinger")
            || texto.contains("dominio")
            || texto.contains("formulario")
            || texto.contains("whatsapp")
            || texto.contains("paginas")
            || texto.contains("pagina")
            || texto.contains("blog")
            || texto.contains("conteudo");

        boolean complexo = texto.contains("sistema")
            || texto.contains("dashboard")
            || texto.contains("aplicativo")
            || contemPalavra(texto, "app")
            || texto.contains("plataforma")
            || texto.contains("erp")
            || texto.contains("crm")
            || texto.contains("rastreador")
            || texto.contains("hardware")
            || texto.contains("gestao de pedidos")
            || texto.contains("gestao de estoque")
            || texto.contains("calculo de frete")
            || texto.contains("marketplace");

        return sinalWebSimples && claroParaExecutar && !complexo;
    }

    private static boolean projetoWebCompativelParaAbordagemInicial(Projeto projeto) {
        String texto = textoProjeto(projeto);
        if (texto.isBlank() || temBloqueadorCriticoParaAbordagemInicial(projeto)) {
            return false;
        }

        boolean dominioWeb = texto.contains("wordpress")
            || texto.contains("site")
            || texto.contains("website")
            || texto.contains("blog")
            || texto.contains("landing page")
            || texto.contains("app web")
            || texto.contains("web app")
            || texto.contains("frontend")
            || texto.contains("front-end")
            || texto.contains("react")
            || texto.contains("remix")
            || texto.contains("typescript")
            || texto.contains("supabase")
            || texto.contains("saas");

        boolean demandaTecnica = texto.contains("desenvolv")
            || texto.contains("programador")
            || texto.contains("integracao")
            || texto.contains("implementar")
            || texto.contains("finalizar")
            || texto.contains("ajuste")
            || texto.contains("corrigir")
            || texto.contains("criar");

        return dominioWeb && demandaTecnica;
    }

    private static boolean temBloqueadorCriticoParaAbordagemInicial(Projeto projeto) {
        String texto = textoProjeto(projeto);
        return EscopoAtendimentoGuard.motivoBloqueio(projeto).isPresent()
            || temBloqueadorForte(texto)
            || texto.contains("verificacao facial")
            || texto.contains("reconhecimento facial")
            || texto.contains("biometr")
            || texto.contains("tribunal")
            || texto.contains("dados juridicos")
            || texto.contains("processo judicial");
    }

    private static boolean temBloqueadorForte(String textoNormalizado) {
        return textoNormalizado.contains("n8n")
            || textoNormalizado.contains("make")
            || textoNormalizado.contains("integromat")
            || textoNormalizado.contains("chatwoot")
            || textoNormalizado.contains("mql5")
            || textoNormalizado.contains("mt5")
            || textoNormalizado.contains("metatrader")
            || textoNormalizado.contains("esp32")
            || textoNormalizado.contains("arduino")
            || textoNormalizado.contains("kotlin")
            || textoNormalizado.contains("swift")
            || textoNormalizado.contains("android nativo")
            || textoNormalizado.contains("ios nativo")
            || textoNormalizado.contains("precatorio")
            || textoNormalizado.contains("pje")
            || textoNormalizado.contains("e-saj")
            || textoNormalizado.contains("cheat")
            || textoNormalizado.contains("wallhack")
            || textoNormalizado.contains("hack ")
            || textoNormalizado.contains("robux")
            || textoNormalizado.contains("hotmart pages");
    }

    private static boolean contemPalavra(String textoNormalizado, String palavra) {
        if (textoNormalizado == null || palavra == null) {
            return false;
        }
        return textoNormalizado.matches(".*\\b" + palavra + "\\b.*");
    }

    private static String textoProjeto(Projeto projeto) {
        if (projeto == null) {
            return "";
        }
        return normalizarBusca(String.join(" ",
            projeto.getTitulo() != null ? projeto.getTitulo() : "",
            projeto.getDescricao() != null ? projeto.getDescricao() : "",
            projeto.getCategoria() != null ? projeto.getCategoria() : "",
            projeto.getSubcategoria() != null ? projeto.getSubcategoria() : "",
            projeto.getNivelExperiencia() != null ? projeto.getNivelExperiencia() : ""
        ));
    }

    private static String normalizarBusca(String texto) {
        if (texto == null) {
            return "";
        }
        return Normalizer.normalize(texto, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("<[^>]+>", " ")
            .replaceAll("[^a-z0-9\\s-]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static String limitarTexto(String texto, int limite) {
        if (texto == null || texto.length() <= limite) {
            return texto;
        }
        return texto.substring(0, Math.max(0, limite - 3)) + "...";
    }

    record AvaliacaoInterpretada(
        boolean capaz,
        String motivoRecusa,
        String observacao,
        String motivoInteresse
    ) {}
}
