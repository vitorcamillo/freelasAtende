package com.freelasatende.service.impl;

import com.freelasatende.config.UpworkConfig;
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
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class UpworkMarketplaceAdapter implements MarketplaceAdapter {

    private static final Pattern VALOR_MONETARIO = Pattern.compile("\\d[\\d,.]*");

    /**
     * Upwork nao expoe operacao publica/interna para abrir uma conversa nova
     * ANTES de aplicar (o endpoint REST antigo "/api/messages/v3/conversations"
     * foi descontinuado e retorna 404; nao ha mutacao GraphQL equivalente
     * documentada). O fluxo real do site e: a sala de mensagens nasce junto
     * com a candidatura (proposta). Por isso "perguntas" fica desativado aqui
     * — o processador de projetos cai direto para enviarProposta quando
     * perguntas=false e propostas=true (ver AutoAtendimentoProjetoProcessor).
     * Duvidas pos-candidatura continuam via enviarMensagemConversa, que usa a
     * API interna de mensageiro (/api/v3/rooms/rooms/.../stories) sobre uma
     * sala ja existente e funciona normalmente.
     */
    private static final MarketplaceCapabilities CAPABILITIES = new MarketplaceCapabilities(
        true, true, false, true, false, false, false
    );

    private final UpworkConfig config;
    private final UpworkClient client;
    private final UpworkProposalClient proposalClient;

    @Override
    public PlataformaFreela plataforma() { return PlataformaFreela.UPWORK; }

    @Override
    public MarketplaceCapabilities capabilities() { return CAPABILITIES; }

    @Override
    public List<Projeto> buscarProjetosNovos() {
        String html = client.buscarProjetosHtml();
        Map<Long, Projeto> projetos = new LinkedHashMap<>();
        for (Element card : Jsoup.parse(html, config.getUrlBase())
            .select("[data-test=job-tile-list] > section[data-ev-opening_uid]")) {
            Projeto projeto = parseProjetoHtml(card);
            if (projeto != null) {
                projetos.put(projeto.getIdExterno(), projeto);
            }
        }
        for (Projeto projeto : UpworkNuxtJobParser.parse(html, config.getUrlBase())) {
            projetos.putIfAbsent(projeto.getIdExterno(), projeto);
        }
        return new ArrayList<>(projetos.values());
    }

    @Override
    public Projeto buscarDetalhesProjeto(Long id, String slug) {
        return proposalClient.buscarDetalhes(id, slug);
    }

    @Override
    public Cliente buscarCliente(Long id) { throw unsupported("buscar cliente"); }

    @Override
    public boolean enviarPergunta(Long projetoId, String mensagem) { return false; }

    @Override
    public boolean enviarPergunta(Projeto projeto, String mensagem) {
        // Upwork nao suporta abrir conversa nova antes de aplicar (ver nota em
        // CAPABILITIES acima). Qualquer chamada aqui indica acao pendente
        // antiga criada antes do fix ou uso indevido do fluxo; falha explicita
        // em vez de bater no endpoint REST descontinuado (404).
        throw unsupported("enviar pergunta pre-candidatura no Upwork "
            + "(fluxo nao suportado pela plataforma; use enviarProposta)");
    }

    @Override
    public Proposta enviarProposta(Projeto projeto, Proposta proposta) {
        proposalClient.enviar(projeto, proposta);
        proposta.setStatus("ENVIADA");
        proposta.setDataProposta(LocalDateTime.now());
        proposta.setFoiMinha(true);
        return proposta;
    }

    @Override
    public boolean cancelarProposta(Long projetoId) { throw unsupported("cancelar proposta"); }

    @Override
    public List<ConversaResumo> listarConversasInbox() {
        try { return client.listarConversasInbox(); }
        catch (Exception e) { return List.of(); }
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
        return client.enviarMensagemConversa(conversaId, texto);
    }

    @Override
    public ConexoesDisponiveis obterConexoesDisponiveis() {
        return ConexoesDisponiveis.builder().total(0).renovaveis(0).naoExpiraveis(0).build();
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
            // O catch mudo daqui escondeu por dias o motivo real da sessao Upwork
            // reprovar mesmo com login bem-sucedido e 35 cookies salvos: o log so
            // dizia "Sessao Upwork nao autenticada". A causa (cookie de organizacao
            // ausente, token do mensageiro recusado, HTTP 401/403 da API) precisa
            // aparecer, senao nao ha como consertar sem adivinhar.
            log.warn("Sessao Upwork reprovada na verificacao: {}", e.getMessage());
            return false;
        }
    }

    private Projeto parseProjetoHtml(Element card) {
        String ciphertext = card.attr("data-ev-opening_uid").trim();
        Element link = card.selectFirst("a[href^=/jobs/]");
        if (ciphertext.isBlank() || link == null || link.text().isBlank()) {
            return null;
        }
        long idExterno = Integer.toUnsignedLong(ciphertext.hashCode());
        Element descricao = card.selectFirst("[data-test=job-description-text]");
        Element valor = card.selectFirst("[data-test=formatted-amount]");

        return Projeto.builder()
            .plataforma(PlataformaFreela.UPWORK)
            .id(PlataformaFreela.UPWORK.idInterno(idExterno))
            .idExterno(idExterno)
            .titulo(link.text().trim())
            .descricao(descricao == null ? "" : descricao.text().trim())
            .orcamento(valor == null ? null : maiorValor(valor.text()))
            .urlProjeto(link.absUrl("href"))
            .dataCriacao(LocalDateTime.now())
            .status(StatusProjeto.NOVO)
            .foiProcessado(false)
            .build();
    }

    private BigDecimal maiorValor(String texto) {
        BigDecimal maior = null;
        Matcher matcher = VALOR_MONETARIO.matcher(texto == null ? "" : texto);
        while (matcher.find()) {
            try {
                BigDecimal valor = new BigDecimal(matcher.group().replace(",", ""));
                if (maior == null || valor.compareTo(maior) > 0) {
                    maior = valor;
                }
            } catch (NumberFormatException ignored) {
                // Mantem o card mesmo quando o site muda apenas a formatacao do valor.
            }
        }
        return maior;
    }

    private MarketplaceAdapterException unsupported(String operacao) {
        return new MarketplaceAdapterException(
            PlataformaFreela.UPWORK, MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL,
            "Adapter Upwork; operacao indisponivel: " + operacao
        );
    }
}
