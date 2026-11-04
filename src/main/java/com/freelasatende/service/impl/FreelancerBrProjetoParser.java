package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusProjeto;
import com.freelasatende.config.FreelancerBrConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser de projetos do Freelancer.com.br a partir do HTML da listagem.
 *
 * <p>Os projetos aparecem como links no padrao
 * {@code /projetos/{categoria}/{sub}/{titulo-slug}-{ID_ALFANUM}}. Como o
 * {@code idExterno} do sistema e numerico e o ID do site e alfanumerico
 * (ex.: {@code 3BOPS371}), derivamos um id numerico estavel do slug do
 * projeto (determinístico, nao colide entre projetos distintos na pratica).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class FreelancerBrProjetoParser {

    private final FreelancerBrConfig config;

    // Link de projeto: termina em -<ID alfanumerico com pelo menos 6 chars>
    private static final Pattern PROJETO_HREF = Pattern.compile("/projetos/[^\"'\\s]+-([0-9A-Za-z]{6,})/?$");

    List<Projeto> parsearProjetos(String html) {
        return parsearProjetosHtml(html);
    }

    /**
     * Monta um projeto a partir de um card da listagem do .com.br.
     *
     * <p>O id externo e derivado do id COMPOSTO (estavel por projeto), porque a
     * plataforma nao expoe id numerico — e e o composto que o endpoint de
     * interesse exige no envio.</p>
     */
    Projeto parsearProjetoDaListagem(String idComposto, String url, String titulo,
                                     String orcamento, String categoria) {
        if (idComposto == null || idComposto.isBlank()) {
            return null;
        }
        String nome = limpar(titulo == null ? "" : titulo);
        if (nome.isBlank()) {
            nome = tituloDoSlug(url == null ? "" : url);
        }
        if (nome.isBlank()) {
            return null;
        }
        Projeto projeto = new Projeto();
        projeto.setPlataforma(PlataformaFreela.FREELANCER_BR);
        projeto.setIdExterno(idNumericoEstavel(idComposto));
        projeto.setTitulo(truncar(nome, 255));
        projeto.setUrlProjeto(url == null ? "" : url);
        projeto.setCategoria(categoria == null ? "" : limpar(categoria));
        projeto.setOrcamento(extrairOrcamentoTexto(orcamento));
        projeto.setStatus(StatusProjeto.NOVO);
        projeto.setDataCriacao(LocalDateTime.now());
        projeto.setFoiProcessado(false);
        return projeto;
    }

    /** Converte "R$  500 - R$  2.000" no maior valor da faixa. */
    BigDecimal extrairOrcamentoTexto(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        Matcher m = Pattern.compile("([0-9][0-9.]*(?:,[0-9]{1,2})?)").matcher(texto);
        BigDecimal maior = null;
        while (m.find()) {
            String bruto = m.group(1).replace(".", "").replace(',', '.');
            try {
                BigDecimal valor = new BigDecimal(bruto);
                if (maior == null || valor.compareTo(maior) > 0) {
                    maior = valor;
                }
            } catch (NumberFormatException ignore) {
                // trecho nao numerico; segue
            }
        }
        return maior;
    }

    private List<Projeto> parsearProjetosHtml(String html) {
        List<Projeto> projetos = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return projetos;
        }
        try {
            Document doc = Jsoup.parse(html);
            Elements links = doc.select("a[href*=/projetos/]");
            // Deduplica por caminho do projeto (o mesmo projeto pode ter varios links).
            Map<String, Element> unicos = new LinkedHashMap<>();
            for (Element link : links) {
                String href = link.attr("href");
                String path = normalizarPath(href);
                if (path == null) {
                    continue;
                }
                Matcher m = PROJETO_HREF.matcher(path);
                if (!m.find()) {
                    continue;
                }
                // Mantem o primeiro link com titulo nao vazio.
                Element existente = unicos.get(path);
                if (existente == null || existente.text().isBlank()) {
                    unicos.put(path, link);
                }
            }
            for (Map.Entry<String, Element> entry : unicos.entrySet()) {
                Projeto p = montarProjeto(entry.getKey(), entry.getValue());
                if (p != null) {
                    projetos.add(p);
                }
            }
        } catch (Exception e) {
            log.error("Erro ao parsear projetos Freelancer.com.br: {}", e.getMessage());
        }
        return projetos;
    }

    /**
     * Constroi projetos a partir da resposta da API JSON global do Freelancer
     * (mesmo backend do .com.br). E o caminho de discovery ativo: publico, sem
     * cookie e fora do Cloudflare. Os links apontam para o dominio .com.br.
     */
    List<Projeto> parsearProjetosJson(JsonNode root) {
        List<Projeto> projetos = new ArrayList<>();
        if (root == null) {
            return projetos;
        }
        JsonNode result = root.path("result");
        JsonNode items = result.path("projects");
        if (!items.isArray()) {
            items = result.isArray() ? result : items;
        }
        if (!items.isArray()) {
            return projetos;
        }
        for (JsonNode item : items) {
            Projeto p = montarProjetoJson(item);
            if (p != null) {
                projetos.add(p);
            }
        }
        return projetos;
    }

    /**
     * Detalhes de um projeto a partir da resposta JSON da API (campo
     * {@code result} com um unico projeto). Reusa o mesmo mapeamento do
     * discovery. Sem cookie e fora do Cloudflare.
     */
    Projeto parsearDetalhesJson(JsonNode root, Long idExterno) {
        if (root == null) {
            return null;
        }
        JsonNode item = root.path("result");
        if (item.has("project")) {
            item = item.path("project");
        }
        if (item.isMissingNode() || item.isNull() || !item.has("id")) {
            return null;
        }
        Projeto p = montarProjetoJson(item);
        if (p != null && idExterno != null) {
            p.setIdExterno(idExterno);
        }
        return p;
    }

    private Projeto montarProjetoJson(JsonNode item) {
        long idExterno = item.path("id").asLong(0L);
        if (idExterno <= 0) {
            return null;
        }
        String titulo = limpar(item.path("title").asText(""));
        if (titulo.isBlank()) {
            titulo = "Projeto Freelancer.com.br";
        }
        String descricao = limpar(item.path("description").asText(""));
        String seoUrl = item.path("seo_url").asText("");
        String url = seoUrl.isBlank()
            ? config.getUrlBase() + "/projects/" + idExterno
            : config.getUrlBase() + "/projects/" + seoUrl;

        Projeto projeto = new Projeto();
        projeto.setPlataforma(PlataformaFreela.FREELANCER_BR);
        projeto.setIdExterno(idExterno);
        projeto.setTitulo(truncar(titulo, 255));
        projeto.setDescricao(descricao);
        projeto.setOrcamento(extrairOrcamento(item.path("budget")));
        projeto.setUrlProjeto(url);
        projeto.setDataCriacao(extrairData(item.path("time_submitted")));
        projeto.setStatus(StatusProjeto.NOVO);
        projeto.setFoiProcessado(false);
        return projeto;
    }

    private BigDecimal extrairOrcamento(JsonNode budget) {
        if (budget == null || budget.isMissingNode() || budget.isNull()) {
            return null;
        }
        JsonNode maximum = budget.path("maximum");
        JsonNode minimum = budget.path("minimum");
        if (maximum.isNumber()) {
            return BigDecimal.valueOf(maximum.asDouble());
        }
        if (minimum.isNumber()) {
            return BigDecimal.valueOf(minimum.asDouble());
        }
        return null;
    }

    private LocalDateTime extrairData(JsonNode timeSubmitted) {
        if (timeSubmitted != null && timeSubmitted.isNumber()) {
            return LocalDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(timeSubmitted.asLong()), ZoneOffset.UTC);
        }
        return LocalDateTime.now();
    }

    Projeto parsearDetalhes(String html, Long idExterno, String url) {
        Document doc = Jsoup.parse(html == null ? "" : html, config.getUrlBase());
        Element h1 = doc.selectFirst("h1");
        Element descricao = doc.selectFirst("h2:matchesOwn((?i)Descri[cç][aã]o) + p, .project-description, [data-name=description]");
        if (h1 == null) return null;
        Projeto projeto = new Projeto();
        projeto.setPlataforma(PlataformaFreela.FREELANCER_BR);
        projeto.setIdExterno(idExterno);
        projeto.setTitulo(truncar(limpar(h1.text()), 255));
        projeto.setDescricao(descricao == null ? "" : limpar(descricao.text()));
        projeto.setUrlProjeto(url);
        projeto.setStatus(StatusProjeto.NOVO);
        projeto.setDataCriacao(LocalDateTime.now());
        projeto.setFoiProcessado(false);
        return projeto;
    }

    private Projeto montarProjeto(String path, Element link) {
        Matcher m = PROJETO_HREF.matcher(path);
        if (!m.find()) {
            return null;
        }
        String idAlfanumerico = m.group(1);
        String titulo = limpar(link.text());
        if (titulo.isBlank()) {
            titulo = tituloDoSlug(path);
        }
        if (titulo.isBlank()) {
            return null;
        }
        Projeto projeto = new Projeto();
        projeto.setPlataforma(PlataformaFreela.FREELANCER_BR);
        projeto.setIdExterno(idNumericoEstavel(idAlfanumerico));
        projeto.setTitulo(truncar(titulo, 255));
        projeto.setUrlProjeto(config.getUrlBase() + path);
        projeto.setCategoria(categoriaDoPath(path));
        projeto.setStatus(StatusProjeto.NOVO);
        projeto.setDataCriacao(LocalDateTime.now());
        projeto.setFoiProcessado(false);
        return projeto;
    }

    private String normalizarPath(String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        String path = href;
        if (path.startsWith("http")) {
            int idx = path.indexOf('/', path.indexOf("//") + 2);
            path = idx >= 0 ? path.substring(idx) : "/";
        }
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int hashIdx = path.indexOf('#');
        if (hashIdx >= 0) {
            path = path.substring(0, hashIdx);
        }
        return path;
    }

    private String categoriaDoPath(String path) {
        // /projetos/{categoria}/{sub}/{slug}
        String[] partes = path.split("/");
        if (partes.length >= 3) {
            return decodeSlug(partes[2]);
        }
        return "";
    }

    private String tituloDoSlug(String path) {
        String[] partes = path.split("/");
        if (partes.length == 0) {
            return "";
        }
        String slug = partes[partes.length - 1];
        slug = slug.replaceAll("-[0-9A-Za-z]{6,}$", "");
        return decodeSlug(slug);
    }

    private String decodeSlug(String slug) {
        try {
            String s = java.net.URLDecoder.decode(slug, java.nio.charset.StandardCharsets.UTF_8);
            s = s.replace('-', ' ').trim();
            if (s.isEmpty()) {
                return "";
            }
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        } catch (Exception e) {
            return slug.replace('-', ' ');
        }
    }

    /**
     * Deriva um id numerico positivo estavel de um id alfanumerico do site.
     * Usa um hash de 63 bits (determinístico entre execucoes).
     */
    static long idNumericoEstavel(String idAlfanumerico) {
        long h = 1125899906842597L; // primo
        for (int i = 0; i < idAlfanumerico.length(); i++) {
            h = 31 * h + idAlfanumerico.charAt(i);
        }
        return h & 0x7fffffffffffffffL;
    }

    private String limpar(String texto) {
        return texto == null ? "" : texto.replaceAll("\\s+", " ").trim();
    }

    private String truncar(String texto, int max) {
        if (texto == null) {
            return "";
        }
        return texto.length() <= max ? texto : texto.substring(0, max);
    }
}
