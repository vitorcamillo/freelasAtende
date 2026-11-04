package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.freelasatende.config.FreelancerConfig;
import com.freelasatende.model.dto.ConexoesDisponiveis;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.Cliente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusProjeto;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import com.freelasatende.service.interfaceServico.MarketplaceCapabilities;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
@Slf4j
@RequiredArgsConstructor
public class FreelancerMarketplaceAdapter implements MarketplaceAdapter {

    private static final MarketplaceCapabilities CAPABILITIES = new MarketplaceCapabilities(
        true, true, true, true, false, false, false
    );

    private final FreelancerConfig config;
    private final FreelancerClient client;

    @Override
    public PlataformaFreela plataforma() {
        return PlataformaFreela.FREELANCER;
    }

    @Override
    public MarketplaceCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public List<Projeto> buscarProjetosNovos() {
        if (client.lancesBloqueados()) {
            log.info("Freelancer: conta sem lances/saldo; discovery pausado ate {}",
                Instant.ofEpochMilli(client.lancesBloqueadosAte()));
            return List.of();
        }
        List<Projeto> projetos = new ArrayList<>();
        Map<String, Integer> descartados = new TreeMap<>();
        JsonNode root = client.buscarProjetosJson();
        JsonNode result = root.path("result");
        JsonNode items = result.isObject() ? result.path("projects") : result;
        if (items.isArray()) {
            for (JsonNode item : items) {
                String motivo = client.motivoRecusa(item);
                if (motivo == null) {
                    projetos.add(parseProjeto(item));
                } else {
                    descartados.merge(motivo, 1, Integer::sum);
                }
            }
        }
        if (!descartados.isEmpty()) {
            log.info("Freelancer discovery: {} aceitos, descartados antes da IA {}", projetos.size(), descartados);
        }
        return projetos;
    }

    @Override
    public Projeto buscarDetalhesProjeto(Long id, String slug) {
        Long externo = PlataformaFreela.FREELANCER.idExterno(id);
        if (externo == null) externo = id;
        JsonNode root = client.buscarDetalhesProjetoJson(externo);
        JsonNode item = root.path("result");
        if (item.has("project")) item = item.path("project");
        return parseProjeto(item);
    }

    @Override
    public Cliente buscarCliente(Long id) {
        throw unsupported("buscar cliente");
    }

    @Override
    public boolean enviarPergunta(Long projetoId, String mensagem) {
        return false;
    }

    @Override
    public boolean enviarPergunta(Projeto projeto, String mensagem) {
        if (projeto == null || projeto.getIdExterno() == null) return false;
        mensagem = MarketplaceHttpContract.mensagemInicialFreelancer(mensagem);
        return client.enviarPergunta(projeto.getIdExterno(), mensagem, projeto.getOrcamento());
    }

    @Override
    public Proposta enviarProposta(Projeto projeto, Proposta proposta) {
        client.enviarProposta(projeto.getIdExterno(), proposta);
        proposta.setStatus("ENVIADA");
        proposta.setDataProposta(LocalDateTime.now());
        proposta.setFoiMinha(true);
        return proposta;
    }

    @Override
    public boolean cancelarProposta(Long projetoId) {
        throw unsupported("cancelar proposta");
    }

    @Override
    public List<ConversaResumo> listarConversasInbox() {
        return client.listarConversasInbox();
    }

    @Override
    public List<MensagemConversa> listarMensagensConversa(Long conversaId) {
        return client.listarMensagensConversa(conversaId);
    }

    @Override
    public List<MensagemConversa> listarMensagensConversa(Long conversaId, boolean marcarComoVisualizada) {
        return listarMensagensConversa(conversaId);
    }

    @Override
    public MensagemConversa enviarMensagemConversa(Long conversaId, String texto) {
        if (conversaId == null) {
            throw unsupported("enviar mensagem sem idExterno");
        }
        return client.enviarMensagemConversa(conversaId, texto);
    }

    @Override
    public ConexoesDisponiveis obterConexoesDisponiveis() {
        return ConexoesDisponiveis.builder()
            .total(0).renovaveis(0).naoExpiraveis(0).build();
    }

    @Override
    public boolean confirmarMfaSessao(Long sessaoId, String codigo) {
        throw unsupported("confirmar MFA");
    }

    @Override
    public boolean verificarLogin() {
        try {
            client.listarConversasInbox();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Projeto parseProjeto(JsonNode item) {
        long idExterno = item.path("id").asLong();
        if (idExterno == 0) {
            String idStr = item.path("id").asText("0");
            try {
                idExterno = Long.parseLong(idStr);
            } catch (NumberFormatException e) {
                idExterno = (long) idStr.hashCode();
            }
        }
        String titulo = item.path("title").asText("");
        String descricao = item.path("description").asText("");
        JsonNode bidBudget = item.path("budget");
        String budgetStr = bidBudget.asText("");

        String seoUrl = item.path("seo_url").asText("");
        String url = seoUrl.isBlank()
            ? config.getUrlBase() + "/projects/" + idExterno
            : config.getUrlBase() + "/projects/" + seoUrl;

        BigDecimal orcamento = null;
        if (!budgetStr.isBlank()) {
            try {
                orcamento = new BigDecimal(budgetStr);
            } catch (NumberFormatException ignored) {}
        }
        if (orcamento == null && bidBudget.isNumber()) {
            orcamento = BigDecimal.valueOf(bidBudget.asDouble());
        }
        if (orcamento == null && bidBudget.isObject()) {
            JsonNode maximum = bidBudget.path("maximum");
            JsonNode minimum = bidBudget.path("minimum");
            if (maximum.isNumber()) {
                orcamento = BigDecimal.valueOf(maximum.asDouble());
            } else if (minimum.isNumber()) {
                orcamento = BigDecimal.valueOf(minimum.asDouble());
            }
        }

        // parse time
        LocalDateTime dataCriacao = LocalDateTime.now();
        JsonNode timeNode = item.path("time_submitted");
        if (timeNode.isNumber()) {
            dataCriacao = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(timeNode.asLong()),
                java.time.ZoneOffset.UTC
            );
        }

        return Projeto.builder()
            .plataforma(PlataformaFreela.FREELANCER)
            .id(PlataformaFreela.FREELANCER.idInterno(idExterno))
            .idExterno(idExterno)
            .titulo(titulo.isBlank() ? "Projeto Freelancer" : titulo)
            .descricao(descricao)
            .orcamento(orcamento)
            .urlProjeto(url)
            .dataCriacao(dataCriacao)
            .status(StatusProjeto.NOVO)
            .foiProcessado(false)
            .build();
    }

    private MarketplaceAdapterException unsupported(String operacao) {
        return new MarketplaceAdapterException(
            PlataformaFreela.FREELANCER,
            MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL,
            "Adapter Freelancer; operacao indisponivel: " + operacao
        );
    }
}
