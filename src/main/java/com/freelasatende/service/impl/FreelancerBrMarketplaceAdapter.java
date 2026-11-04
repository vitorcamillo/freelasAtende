package com.freelasatende.service.impl;

import com.freelasatende.model.dto.ConexoesDisponiveis;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.Cliente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import com.freelasatende.service.interfaceServico.MarketplaceCapabilities;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adapter do Freelancer.com.br (marketplace BR, scraping de HTML).
 *
 * <p>Sessao cookie-based capturada manualmente (Cloudflare Managed Challenge).
 * Habilitado: descoberta, interesse/proposta e inbox autenticada. A renovacao
 * da sessao continua manual por causa do Cloudflare Managed Challenge.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class FreelancerBrMarketplaceAdapter implements MarketplaceAdapter {

    private static final MarketplaceCapabilities CAPABILITIES = new MarketplaceCapabilities(
        true,   // discovery
        true,   // inbox
        true,   // primeiro contato via Estou interessado
        true,   // proposta/interesse
        false,  // cancelamento (bloqueado)
        false,  // conexoes
        false   // mfa
    );

    private final FreelancerBrClient client;
    private final FreelancerBrProjetoParser parser;
    private final FreelancerBrBrowser browser;
    private final Map<Long, String> urlsProjetos = new ConcurrentHashMap<>();

    @Override
    public PlataformaFreela plataforma() {
        return PlataformaFreela.FREELANCER_BR;
    }

    @Override
    public MarketplaceCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public List<Projeto> buscarProjetosNovos() {
        // O Freelancer.com.br tem CATALOGO PROPRIO: e um site Laravel
        // independente, e nao uma tradução do freelancer.com. Os projetos daqui
        // nao existem na API global (ids alfanumericos proprios), por isso o
        // discovery le a listagem do proprio site.
        //
        // /projetos e a unica rota sob Cloudflare Managed Challenge, entao a
        // listagem passa pelo Chrome real; envio e inbox seguem por HTTP puro.
        String html = browser.baixarListagemProjetos();
        List<FreelancerBrClient.ProjetoBr> encontrados = client.extrairProjetosDaListagem(html);
        List<Projeto> projetos = new java.util.ArrayList<>();
        log.info("FreelancerBR discovery: htmlBytes={}, cards={}",
            html == null ? 0 : html.length(), encontrados.size());
        for (FreelancerBrClient.ProjetoBr bruto : encontrados) {
            Projeto projeto = parser.parsearProjetoDaListagem(
                bruto.idComposto(), bruto.url(), bruto.titulo(), bruto.orcamento(), bruto.categoria());
            if (projeto == null) {
                continue;
            }
            urlsProjetos.put(projeto.getIdExterno(), projeto.getUrlProjeto());
            // Guarda o id composto: e ele que o endpoint de interesse exige.
            client.registrarComposto(projeto.getIdExterno(), bruto.idComposto());
            projetos.add(projeto);
        }
        log.info("FreelancerBR discovery: projetosValidos={}", projetos.size());
        return projetos;
    }

    @Override
    public Projeto buscarDetalhesProjeto(Long id, String slug) {
        // O detalhe do projeto mora sob /projetos, unica rota sob Cloudflare
        // Managed Challenge. O discovery ja traz titulo, categoria, orcamento e
        // link, que e o suficiente para avaliar e enviar o interesse — entao nao
        // ha uma segunda requisicao aqui (a API global nao serve: e outra
        // plataforma, com outro catalogo).
        throw unsupported("detalhes do projeto (dados ja vem do discovery)");
    }

    @Override
    public Cliente buscarCliente(Long id) {
        throw unsupported("buscar cliente");
    }

    @Override
    public boolean enviarPergunta(Long projetoId, String mensagem) {
        throw unsupported("enviar pergunta sem contexto do projeto");
    }

    @Override
    public boolean enviarPergunta(Projeto projeto, String mensagem) {
        // Caminho unico e correto: "Estou interessado" nativo do .com.br.
        // A API global do freelancer.com NAO serve aqui — sao plataformas
        // distintas, e o id deste projeto nao existe la (o bid falhava com 403
        // SKILLS_REQUIREMENT_NOT_MET / ESCROWCOM_ACCOUNT_UNLINKED).
        return client.enviarInteresseNativo(client.compostoDe(projeto.getIdExterno()), mensagem);
    }

    @Override
    public Proposta enviarProposta(Projeto projeto, Proposta proposta) {
        client.enviarInteresseNativo(client.compostoDe(projeto.getIdExterno()), proposta.getMensagem());
        proposta.setStatus("ENVIADA");
        proposta.setDataProposta(java.time.LocalDateTime.now());
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
        return client.enviarMensagemConversa(conversaId, texto);
    }

    @Override
    public ConexoesDisponiveis obterConexoesDisponiveis() {
        return ConexoesDisponiveis.builder()
            .total(0)
            .renovaveis(0)
            .naoExpiraveis(0)
            .build();
    }

    @Override
    public boolean confirmarMfaSessao(Long sessaoId, String codigo) {
        throw unsupported("confirmar MFA");
    }

    @Override
    public boolean verificarLogin() {
        return client.temSessaoValida();
    }

    private MarketplaceAdapterException unsupported(String operacao) {
        return new MarketplaceAdapterException(
            PlataformaFreela.FREELANCER_BR,
            MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL,
            "Adapter Freelancer.com.br; operacao indisponivel: " + operacao);
    }

}
