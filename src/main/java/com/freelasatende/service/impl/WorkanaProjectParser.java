package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.freelasatende.config.WorkanaConfig;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusProjeto;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

@Component
@RequiredArgsConstructor
class WorkanaProjectParser {

    private final WorkanaConfig config;

    List<Projeto> parseProjetos(JsonNode root) {
        List<Projeto> projetos = new ArrayList<>();
        JsonNode itens = root.path("results").path("results");
        if (!itens.isArray()) {
            throw new MarketplaceAdapterException(
                PlataformaFreela.WORKANA,
                MarketplaceAdapterErrorType.FALHA_PARSE,
                "Resposta Workana sem results.results"
            );
        }

        for (JsonNode item : itens) {
            if (projetos.size() >= config.getMaxProjetos()) {
                break;
            }
            Projeto projeto = parseProjeto(item);
            if (projeto != null) {
                projetos.add(projeto);
            }
        }
        return projetos;
    }

    private Projeto parseProjeto(JsonNode item) {
        String slug = texto(item, "slug");
        if (slug.isBlank()) {
            return null;
        }

        String titulo = limparHtml(texto(item, "title"));
        String descricao = limparHtml(texto(item, "description"));
        String descricaoCurta = limparHtml(texto(item, "shortDescription"));
        List<String> skills = skills(item.path("skills"));

        Long idExterno = idEstavel(slug);
        List<BigDecimal> valores = valores(texto(item, "budget"));
        BigDecimal valorMinimo = valores.isEmpty() ? null : valores.get(0);
        BigDecimal orcamento = valores.isEmpty() ? null : valores.get(valores.size() - 1);
        String descricaoCompleta = montarDescricao(descricao, skills, texto(item, "budget"));

        return Projeto.builder()
            .plataforma(PlataformaFreela.WORKANA)
            .id(PlataformaFreela.WORKANA.idInterno(idExterno))
            .idExterno(idExterno)
            .titulo(titulo.isBlank() ? slug : titulo)
            .descricao(descricaoCompleta)
            .descricaoCurta(!descricaoCurta.isBlank() ? descricaoCurta : abreviar(descricao))
            .categoria("Programacao")
            .subcategoria(skills.isEmpty() ? "Workana" : String.join(", ", skills))
            .orcamento(orcamento)
            .valorMinimo(valorMinimo)
            .numeroPropostas(inteiro(texto(item, "totalBids")))
            .urlProjeto(config.getUrlBase() + "/job/" + slug)
            .nomeCliente(texto(item, "authorName"))
            .dataCriacao(LocalDateTime.now())
            .status(StatusProjeto.NOVO)
            .foiProcessado(false)
            .build();
    }

    private String montarDescricao(String descricao, List<String> skills, String budget) {
        StringBuilder texto = new StringBuilder();
        if (!skills.isEmpty()) {
            texto.append("Skills Workana: ").append(String.join(", ", skills)).append("\n\n");
        }
        if (budget != null && !budget.isBlank()) {
            texto.append("Orcamento Workana: ").append(budget).append("\n\n");
        }
        texto.append(descricao == null ? "" : descricao);
        return texto.toString().trim();
    }

    private List<String> skills(JsonNode node) {
        List<String> skills = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode skill : node) {
                String texto = texto(skill, "anchorText");
                if (!texto.isBlank()) {
                    skills.add(texto);
                }
            }
        }
        return skills;
    }

    private List<BigDecimal> valores(String texto) {
        List<BigDecimal> valores = new ArrayList<>();
        if (texto == null) {
            return valores;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("(\\d{1,3}(?:\\.\\d{3})*(?:,\\d{2})?|\\d+)")
            .matcher(texto);
        while (matcher.find()) {
            valores.add(new BigDecimal(matcher.group(1).replace(".", "").replace(",", ".")));
        }
        return valores;
    }

    private Integer inteiro(String texto) {
        if (texto == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(texto);
        return matcher.find() ? Integer.valueOf(matcher.group()) : null;
    }

    private String texto(JsonNode node, String campo) {
        JsonNode valor = node.path(campo);
        return valor.isMissingNode() || valor.isNull() ? "" : valor.asText("");
    }

    private String limparHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return Jsoup.parse(html).text().trim();
    }

    private String abreviar(String texto) {
        if (texto == null) {
            return "";
        }
        String limpo = texto.trim();
        return limpo.length() <= 180 ? limpo : limpo.substring(0, 177) + "...";
    }

    private Long idEstavel(String slug) {
        CRC32 crc = new CRC32();
        crc.update(slug.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long valor = crc.getValue();
        return valor == 0 ? 1L : valor;
    }
}
