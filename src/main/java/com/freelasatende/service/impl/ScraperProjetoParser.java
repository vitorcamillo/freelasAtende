package com.freelasatende.service.impl;

import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.StatusProjeto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
class ScraperProjetoParser {

    private final String urlBase;

    List<Projeto> parsearProjetos(String html) {
        List<Projeto> projetos = new ArrayList<>();

        try {
            Document doc = Jsoup.parse(html);
            Elements cards = doc.select(
                ".project-card, .result-item, .result-container li, "
                    + "[class*='project-item'], [class*='project-list'] > *"
            );

            if (cards.isEmpty()) {
                cards = doc.select("a[href*='/project/']");
                log.debug("Fallback: encontrados {} links de projetos", cards.size());
            }

            for (Element card : cards) {
                try {
                    Projeto projeto = extrairProjetoDeCard(card);
                    if (projeto != null && projeto.getId() != null && projeto.getTitulo() != null) {
                        projetos.add(projeto);
                    }
                } catch (Exception e) {
                    log.debug("Erro ao parsear card: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("Erro no parsing Jsoup: {}", e.getMessage());
        }

        return projetos;
    }

    Projeto parsearDetalhes(String html, Long id) {
        Document doc = Jsoup.parse(html);
        if (isPaginaErroTemporario(doc)) {
            log.warn("Detalhes do projeto {} retornaram pagina de erro temporario; mantendo dados anteriores", id);
            return null;
        }
        Map<String, String> infoTabela = extrairTabelaInformacoes(doc);

        Projeto.ProjetoBuilder builder = Projeto.builder()
            .id(id)
            .status(StatusProjeto.NOVO);

        preencherTitulo(doc, id, builder);
        preencherDescricao(doc, id, builder);
        preencherCategoriaNivelEPropostas(doc, builder, infoTabela);
        preencherValores(doc, builder, infoTabela);
        preencherHabilidadesEAnexos(doc, builder);

        return builder.build();
    }

    private Projeto extrairProjetoDeCard(Element card) {
        Long id = null;
        String titulo = null;
        String href = null;

        if (card.hasAttr("data-id")) {
            try {
                id = Long.parseLong(card.attr("data-id"));
            } catch (NumberFormatException ignored) {
                // A extração por link cobre cards sem data-id válido.
            }
        }
        if (card.hasAttr("data-nome")) {
            titulo = card.attr("data-nome");
        }

        if (id == null || titulo == null) {
            Element link = card.selectFirst("a[href*='/project/']");
            if (link == null && card.tagName().equals("a") && card.attr("href").contains("/project/")) {
                link = card;
            }
            if (link != null) {
                href = link.attr("href");
                id = extrairIdDeUrl(href);
                if (titulo == null) {
                    Element h2 = card.selectFirst("h2, h3, .project-title, [class*='title']");
                    titulo = h2 != null ? h2.text().trim() : link.text().trim();
                }
            }
        }

        if (id == null || titulo == null || titulo.isEmpty()) {
            return null;
        }
        if (isTituloInvalidoProjeto(titulo)) {
            log.warn("Ignorando card com titulo invalido (possivel pagina de erro): id={} titulo='{}'", id, titulo);
            return null;
        }
        if (href == null) {
            href = "/project/projeto-" + id;
        }

        Element infoEl = card.selectFirst(".item-text.information, .information");
        String infoTexto = infoEl != null ? infoEl.text() : null;

        return Projeto.builder()
            .id(id)
            .titulo(titulo)
            .descricaoCurta(extrairDescricaoCurta(card))
            .urlProjeto(urlBase + href)
            .orcamento(extrairOrcamentoCard(card, infoTexto))
            .numeroPropostas(extrairNumeroPropostasCard(card, infoTexto))
            .nivelExperiencia(extrairNivelCard(card, infoTexto))
            .categoria(extrairCategoriaCard(card, infoTexto))
            .status(StatusProjeto.NOVO)
            .dataCriacao(LocalDateTime.now())
            .build();
    }

    private BigDecimal extrairOrcamentoCard(Element card, String infoTexto) {
        Element priceEl = card.selectFirst(".price, [class*='budget'], [class*='price'], [class*='valor']");
        BigDecimal orcamento = priceEl != null ? extrairValor(priceEl.text()) : null;
        if (orcamento == null) {
            orcamento = extrairValorPorRotulo(infoTexto, "valor");
        }
        return orcamento != null ? orcamento : extrairValorPorRotulo(infoTexto, "orcamento");
    }

    private Integer extrairNumeroPropostasCard(Element card, String infoTexto) {
        Element propostasEl = card.selectFirst(".proposals, [class*='proposal'], [class*='bid']");
        if (propostasEl != null) {
            String text = propostasEl.text().replaceAll("[^0-9]", "");
            if (!text.isEmpty()) {
                return Integer.parseInt(text);
            }
        }
        return extrairInteiroPorRotulo(infoTexto, "propostas");
    }

    private String extrairNivelCard(Element card, String infoTexto) {
        Element nivelEl = card.selectFirst(".experience-level, [class*='level'], [class*='nivel']");
        String nivel = nivelEl != null ? nivelEl.text().trim() : null;
        return nivel == null || nivel.isBlank() ? extrairSegmentoInformacao(infoTexto, 1) : nivel;
    }

    private String extrairDescricaoCurta(Element card) {
        Element descEl = card.selectFirst(".description, [class*='description'], [class*='descricao'], p");
        if (descEl == null) {
            return null;
        }
        String descCurta = descEl.text().trim();
        return descCurta.length() > 200 ? descCurta.substring(0, 200) + "..." : descCurta;
    }

    private String extrairCategoriaCard(Element card, String infoTexto) {
        Element catEl = card.selectFirst(".category, [class*='category'], [class*='categoria']");
        String categoria = catEl != null ? catEl.text().trim() : null;
        return categoria == null || categoria.isBlank() ? extrairSegmentoInformacao(infoTexto, 0) : categoria;
    }

    private void preencherTitulo(Document doc, Long id, Projeto.ProjetoBuilder builder) {
        Element h1 = doc.selectFirst("h1, [class*='title']");
        if (h1 != null && !h1.text().trim().isEmpty()) {
            builder.titulo(h1.text().trim());
            log.debug("Titulo extraido (h1) para projeto {}: {}", id, h1.text().trim());
            return;
        }

        log.debug("Nao encontrou h1 com texto para projeto {}", id);
        Element titleEl = doc.selectFirst("title");
        if (titleEl == null) {
            log.warn("Nao encontrou <title> para projeto {}", id);
            return;
        }
        String fullTitle = titleEl.text().trim();
        log.debug("Title tag completo para projeto {}: {}", id, fullTitle);
        String titulo;
        if (fullTitle.contains(" | ")) {
            titulo = fullTitle.substring(0, fullTitle.lastIndexOf(" | ")).trim();
        } else if (fullTitle.contains(" - ")) {
            titulo = fullTitle.substring(0, fullTitle.lastIndexOf(" - ")).trim();
        } else {
            titulo = fullTitle;
        }
        builder.titulo(titulo);
        log.debug("Titulo extraido do <title> para projeto {}: {}", id, titulo);
    }

    private void preencherDescricao(Document doc, Long id, Projeto.ProjetoBuilder builder) {
        Element descEl = doc.selectFirst(".project-description");
        if (descEl == null) {
            descEl = doc.selectFirst("[class*='description']");
        }
        if (descEl == null) {
            descEl = doc.selectFirst(".project-body");
        }
        if (descEl == null) {
            log.debug("Nao encontrou elemento de descricao para projeto {}", id);
            return;
        }

        String dataContent = descEl.attr("data-content");
        if (dataContent != null && !dataContent.isEmpty()) {
            builder.descricao(dataContent);
            log.debug("Descricao extraida para projeto {}: {} chars (from data-content)", id, dataContent.length());
        } else {
            String text = descEl.text().trim();
            builder.descricao(text);
            log.debug("Descricao extraida para projeto {}: {} chars (from text)", id, text.length());
        }
    }

    private void preencherCategoriaNivelEPropostas(
        Document doc,
        Projeto.ProjetoBuilder builder,
        Map<String, String> infoTabela
    ) {
        Element catEl = doc.selectFirst("[class*='category'], .breadcrumb a");
        if (catEl != null) {
            builder.categoria(catEl.text().trim());
        }
        Element infoEl = doc.selectFirst(".item-text.information, .information");
        String infoTexto = infoEl != null ? infoEl.text() : null;
        if (builder.build().getCategoria() == null) {
            String categoriaInfo = extrairSegmentoInformacao(infoTexto, 0);
            if (categoriaInfo != null && !categoriaInfo.isBlank()) {
                builder.categoria(categoriaInfo);
            }
            if (categoriaInfo == null && infoTabela.containsKey("categoria")) {
                builder.categoria(infoTabela.get("categoria"));
            }
        }
        if (infoTabela.containsKey("subcategoria")) {
            builder.subcategoria(infoTabela.get("subcategoria"));
        }
        String nivelInfo = extrairSegmentoInformacao(infoTexto, 1);
        if (nivelInfo != null && !nivelInfo.isBlank()) {
            builder.nivelExperiencia(nivelInfo);
        } else if (infoTabela.containsKey("nivel de experiencia")) {
            builder.nivelExperiencia(infoTabela.get("nivel de experiencia"));
        }
        Integer propostasInfo = extrairInteiroPorRotulo(infoTexto, "propostas");
        if (propostasInfo != null) {
            builder.numeroPropostas(propostasInfo);
        } else if (infoTabela.containsKey("propostas")) {
            String propostasTabela = infoTabela.get("propostas").replaceAll("[^0-9]", "");
            if (!propostasTabela.isBlank()) {
                builder.numeroPropostas(Integer.parseInt(propostasTabela));
            }
        }
        if (infoTabela.containsKey("interessados")) {
            String interessadosTabela = infoTabela.get("interessados").replaceAll("[^0-9]", "");
            if (!interessadosTabela.isBlank()) {
                builder.interessados(Integer.parseInt(interessadosTabela));
            }
        }
    }

    private void preencherValores(Document doc, Projeto.ProjetoBuilder builder, Map<String, String> infoTabela) {
        Element budgetEl = doc.selectFirst("[class*='budget'], [class*='price']");
        if (budgetEl != null) {
            BigDecimal valor = extrairValor(budgetEl.text());
            if (valor != null) {
                builder.orcamento(valor);
            }
        }
        if (builder.build().getOrcamento() == null) {
            Element infoEl = doc.selectFirst(".item-text.information, .information");
            String infoTexto = infoEl != null ? infoEl.text() : null;
            BigDecimal valorInfo = extrairValorPorRotulo(infoTexto, "valor");
            if (valorInfo == null) {
                valorInfo = extrairValorPorRotulo(infoTexto, "orcamento");
            }
            if (valorInfo != null) {
                builder.orcamento(valorInfo);
            }
        }
        if (builder.build().getOrcamento() == null && infoTabela.containsKey("orcamento")) {
            BigDecimal valorTabela = extrairValor(infoTabela.get("orcamento"));
            if (valorTabela != null) {
                builder.orcamento(valorTabela);
            }
        }
        if (builder.build().getValorMinimo() == null && infoTabela.containsKey("valor minimo")) {
            BigDecimal valorMinimoTabela = extrairValor(infoTabela.get("valor minimo"));
            if (valorMinimoTabela != null) {
                builder.valorMinimo(valorMinimoTabela);
            }
        }

        BigDecimal mediaPropostas = extrairMediaPropostasDoHtml(doc);
        Integer numeroPropostas = builder.build().getNumeroPropostas();
        if (mediaPropostas != null && numeroPropostas != null && numeroPropostas >= 3) {
            builder.mediaProposta(mediaPropostas);
        }
    }

    private void preencherHabilidadesEAnexos(Document doc, Projeto.ProjetoBuilder builder) {
        Elements skills = doc.select("[class*='skill'], [class*='tag'], .badge");
        if (!skills.isEmpty()) {
            String subcategorias = String.join(", ", skills.stream().map(Element::text).toList());
            builder.subcategoria(subcategorias);
        }

        Elements anexoEls = doc.select("a[href*='/download'], a.attachment, [class*='attachment'] a, "
            + "a[href$='.pdf'], a[href$='.doc'], a[href$='.docx'], a[href$='.zip']");
        List<String> anexosUrls = new ArrayList<>();
        for (Element anexo : anexoEls) {
            String href = anexo.attr("href");
            String nome = anexo.text().trim();
            if (nome.isEmpty()) {
                nome = "Anexo";
            }
            if (!href.isEmpty() && !anexosUrls.contains(nome + ": " + href)) {
                anexosUrls.add(nome + ": " + href);
            }
        }
        if (!anexosUrls.isEmpty()) {
            builder.anexosUrls(anexosUrls);
        }
    }

    private boolean isTituloInvalidoProjeto(String titulo) {
        if (titulo == null) {
            return true;
        }
        String t = titulo.trim().toLowerCase();
        if (t.isEmpty()) {
            return true;
        }
        return t.contains("503 service temporarily unavailable")
            || t.contains("502 bad gateway")
            || t.contains("429 too many requests")
            || t.contains("service temporarily unavailable")
            || t.contains("service unavailable");
    }

    private boolean isPaginaErroTemporario(Document doc) {
        if (doc == null) {
            return true;
        }
        String title = doc.title() != null ? doc.title().trim().toLowerCase() : "";
        String bodyText = doc.body() != null ? doc.body().text().trim().toLowerCase() : "";

        if (bodyText.length() > 3000) {
            bodyText = bodyText.substring(0, 3000);
        }

        return title.contains("503 service temporarily unavailable")
            || title.contains("502 bad gateway")
            || title.contains("429 too many requests")
            || title.contains("service temporarily unavailable")
            || bodyText.contains("503 service temporarily unavailable")
            || bodyText.contains("502 bad gateway")
            || bodyText.contains("429 too many requests")
            || bodyText.contains("service temporarily unavailable");
    }

    private Long extrairIdDeUrl(String url) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("-(\\d+)(?:\\?.*)?$")
            .matcher(url);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        return null;
    }

    private BigDecimal extrairValor(String texto) {
        if (texto == null) {
            return null;
        }
        String limpo = texto.replaceAll("[^\\d.,]", "")
            .replace(".", "")
            .replace(",", ".");
        try {
            if (!limpo.isEmpty()) {
                return new BigDecimal(limpo);
            }
        } catch (NumberFormatException e) {
            log.debug("Não foi possível parsear valor: {}", texto);
        }
        return null;
    }

    private Integer extrairInteiroPorRotulo(String texto, String rotulo) {
        if (texto == null || texto.isBlank() || rotulo == null || rotulo.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("(?i)" + java.util.regex.Pattern.quote(rotulo) + "\\s*:\\s*(\\d+)")
            .matcher(texto);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private BigDecimal extrairValorPorRotulo(String texto, String rotulo) {
        if (texto == null || texto.isBlank() || rotulo == null || rotulo.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("(?i)" + java.util.regex.Pattern.quote(rotulo) + "\\s*:\\s*(R\\$\\s*[\\d.,]+)")
            .matcher(texto);
        if (matcher.find()) {
            return extrairValor(matcher.group(1));
        }
        return null;
    }

    private String extrairSegmentoInformacao(String infoTexto, int indice) {
        if (infoTexto == null || infoTexto.isBlank() || indice < 0) {
            return null;
        }
        String[] partes = infoTexto.split("\\|");
        if (indice >= partes.length) {
            return null;
        }
        String valor = partes[indice].replaceAll("\\s+", " ").trim();
        return valor.isBlank() ? null : valor;
    }

    private Map<String, String> extrairTabelaInformacoes(Document doc) {
        Map<String, String> info = new HashMap<>();
        Elements linhas = doc.select(".info-adicionais table tr");
        for (Element linha : linhas) {
            Element th = linha.selectFirst("th");
            Element td = linha.selectFirst("td");
            if (th == null || td == null) {
                continue;
            }
            String chave = ScraperTextNormalizer.normalizarChave(th.text());
            String valor = td.text().replaceAll("\\s+", " ").trim();
            if (!chave.isBlank() && !valor.isBlank()) {
                info.put(chave, valor);
            }
        }
        return info;
    }

    private BigDecimal extrairMediaPropostasDoHtml(Document doc) {
        List<BigDecimal> ofertasVisiveis = doc.select("p.proposal-info").stream()
            .map(Element::text)
            .map(this::extrairOfertaDeLinhaProposta)
            .filter(java.util.Objects::nonNull)
            .filter(v -> v.compareTo(BigDecimal.ZERO) > 0)
            .collect(Collectors.toList());
        if (ofertasVisiveis.size() < 3) {
            return null;
        }
        BigDecimal soma = ofertasVisiveis.stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return soma.divide(BigDecimal.valueOf(ofertasVisiveis.size()), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal extrairOfertaDeLinhaProposta(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        if (texto.toLowerCase().contains("privado")) {
            return null;
        }
        java.util.regex.Matcher finalMatcher = java.util.regex.Pattern
            .compile("(?i)oferta\\s+final\\s*:\\s*(R\\$\\s*[\\d.,]+)")
            .matcher(texto);
        if (finalMatcher.find()) {
            return extrairValor(finalMatcher.group(1));
        }
        java.util.regex.Matcher ofertaMatcher = java.util.regex.Pattern
            .compile("(?i)oferta\\s*:\\s*(R\\$\\s*[\\d.,]+)")
            .matcher(texto);
        if (ofertaMatcher.find()) {
            return extrairValor(ofertaMatcher.group(1));
        }
        return null;
    }
}
