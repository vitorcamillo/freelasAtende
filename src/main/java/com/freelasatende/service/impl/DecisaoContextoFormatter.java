package com.freelasatende.service.impl;

import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.Projeto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class DecisaoContextoFormatter {

    private DecisaoContextoFormatter() {
    }

    static List<String> riscosDecisaoProjeto(Projeto projeto, String origemValor) {
        List<String> riscos = new ArrayList<>();
        if (projeto == null) {
            riscos.add("projeto sem dados suficientes");
            return riscos;
        }
        if (projeto.getDescricao() == null || projeto.getDescricao().isBlank()) {
            riscos.add("descricao do projeto curta ou ausente");
        }
        if (projeto.getOrcamento() == null && projeto.getMediaProposta() == null && projeto.getValorMinimo() == null) {
            riscos.add("valor ainda nao esta claro na plataforma");
        }
        if (origemValor != null && origemValor.toLowerCase().contains("nao informado")) {
            riscos.add("origem do valor precisa de confirmacao humana");
        }
        if (riscos.isEmpty()) {
            riscos.add("baixo risco aparente, mas envio continua pendente");
        }
        return riscos;
    }

    static List<String> informacoesConfirmadasProjeto(Projeto projeto, String origemValor) {
        List<String> info = new ArrayList<>();
        if (projeto == null) {
            return List.of("Nenhum dado confiavel do projeto foi carregado.");
        }
        if (projeto.getTitulo() != null && !projeto.getTitulo().isBlank()) {
            info.add("Titulo: " + projeto.getTitulo());
        }
        if (projeto.getCategoria() != null && !projeto.getCategoria().isBlank()) {
            info.add("Categoria: " + projeto.getCategoria());
        }
        if (projeto.getDescricao() != null && !projeto.getDescricao().isBlank()) {
            info.add("Descricao lida na plataforma.");
        }
        if (projeto.getOrcamento() != null) {
            info.add("Orcamento informado: " + formatarMoeda(projeto.getOrcamento()));
        }
        if (projeto.getMediaProposta() != null) {
            info.add("Media de propostas visivel: " + formatarMoeda(projeto.getMediaProposta()));
        }
        if (projeto.getNumeroPropostas() != null) {
            info.add("Total de propostas visivel: " + projeto.getNumeroPropostas());
        }
        if (origemValor != null && !origemValor.isBlank()) {
            info.add("Origem do valor: " + origemValor);
        }
        if (info.isEmpty()) {
            info.add("Projeto identificado, mas com poucos campos visiveis.");
        }
        return info;
    }

    static List<String> informacoesPendentesProjeto(Projeto projeto, List<String> duvidas, String origemValor) {
        LinkedHashSet<String> pendentes = new LinkedHashSet<>();
        if (duvidas != null) {
            duvidas.stream()
                .filter(duvida -> duvida != null && !duvida.isBlank())
                .map(String::trim)
                .forEach(pendentes::add);
        }
        if (projeto == null) {
            pendentes.add("Confirmar dados reais do projeto antes de escrever ao cliente.");
        } else {
            if (projeto.getDescricao() == null || projeto.getDescricao().isBlank()) {
                pendentes.add("Confirmar escopo principal do projeto.");
            }
            if (projeto.getOrcamento() == null && projeto.getMediaProposta() == null && projeto.getValorMinimo() == null) {
                pendentes.add("Confirmar base real para valor antes de proposta.");
            }
        }
        if (origemValor != null && origemValor.toLowerCase().contains("sem base real")) {
            pendentes.add("Valor precisa de confirmacao humana.");
        }
        if (pendentes.isEmpty()) {
            pendentes.add("Sem pendencia critica detectada; envio continua dependente de aprovacao.");
        }
        return List.copyOf(pendentes);
    }

    static List<String> informacoesConfirmadasConversa(
        ConversaResumo conversa,
        MensagemConversa ultimaRecebida,
        boolean autoriaConfiavel
    ) {
        List<String> info = new ArrayList<>();
        if (conversa.getNomeProjeto() != null && !conversa.getNomeProjeto().isBlank()) {
            info.add("Projeto: " + conversa.getNomeProjeto());
        }
        if (conversa.getIdConversa() != null) {
            info.add("Conversa: " + conversa.getIdConversa());
        }
        if (ultimaRecebida.getId() != null) {
            info.add("Mensagem do cliente identificada: " + ultimaRecebida.getId());
        }
        info.add(autoriaConfiavel
            ? "Autoria validada por idFreelancer/idCliente."
            : "Autoria ainda incompleta.");
        return info;
    }

    static List<String> informacoesPendentesConversa(boolean autoriaConfiavel) {
        if (!autoriaConfiavel) {
            return List.of("Confirmar autoria da conversa antes de enviar.");
        }
        return List.of("Validar se a resposta nao repete mensagem anterior e responde o ponto certo.");
    }

    static List<String> riscosDecisaoConversa(boolean autoriaConfiavel) {
        List<String> riscos = new ArrayList<>();
        if (!autoriaConfiavel) {
            riscos.add("autoria da conversa incompleta");
        }
        riscos.add("resposta pode ficar fora de contexto se historico anterior nao foi suficiente");
        return riscos;
    }

    private static String formatarMoeda(BigDecimal valor) {
        if (valor == null) {
            return "Não informado pela plataforma";
        }
        return "R$ " + valor.setScale(2, RoundingMode.HALF_UP).toPlainString().replace(".", ",");
    }
}
