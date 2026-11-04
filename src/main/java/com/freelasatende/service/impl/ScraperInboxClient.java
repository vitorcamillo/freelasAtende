package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import com.freelasatende.util.FreelasApiUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.freelasatende.service.impl.MarketplaceSessionFailure.isSessao99FreelasInvalida;

@Slf4j
@RequiredArgsConstructor
class ScraperInboxClient {

    private final AutenticacaoService autenticacaoService;
    private final String urlBase;
    private Long pessoaLogadaId;
    private final Map<Long, Long> freelancerIdPorConversa = new ConcurrentHashMap<>();
    private final Map<Long, Long> clienteIdPorConversa = new ConcurrentHashMap<>();

    List<ConversaResumo> listarConversas(ServicoFetcher servicoFetcher, PageFetcher pageFetcher) {
        List<ConversaResumo> conversas = new ArrayList<>();
        try {
            Long idPessoa = obterPessoaLogadaId(pageFetcher);
            if (idPessoa == null) {
                log.warn("[INBOX] idPessoa é null — usuário não está logado. Retornando lista vazia.");
                return conversas;
            }
            log.debug("[INBOX] Buscando conversas da sessao autenticada");

            JsonNode root = servicoFetcher.get(
                "/services/user/carregarConversas",
                payloadListarConversas(idPessoa),
                urlBase + "/messages"
            );
            JsonNode registros = root.path("result").path("registros");
            if (registros.isArray()) {
                log.debug("[INBOX] Encontradas {} conversas na inbox", registros.size());
                for (JsonNode item : registros) {
                    conversas.add(parseConversaResumo(item));
                }
            } else {
                log.warn("[INBOX] Resposta recebida sem lista de conversas valida");
            }
        } catch (Exception e) {
            if (isSessao99FreelasInvalida(e)) {
                log.warn("[INBOX] Sessao 99Freelas invalida ao listar conversas: {}", e.getMessage());
                throw new IllegalStateException("Sessao 99Freelas invalida ao listar inbox: " + e.getMessage(), e);
            }
            throw new MarketplaceAdapterException(
                com.freelasatende.model.enums.PlataformaFreela.FREELAS_99,
                MarketplaceAdapterErrorType.FALHA_REDE,
                "99Freelas inbox indisponivel nesta rodada: " + e.getMessage(),
                e
            );
        }
        return conversas;
    }

    List<MensagemConversa> listarMensagens(
        Long conversaId,
        boolean marcarComoVisualizada,
        ServicoFetcher servicoFetcher,
        PageFetcher pageFetcher
    ) {
        List<MensagemConversa> mensagens = new ArrayList<>();
        try {
            JsonNode root = servicoFetcher.get(
                "/services/consultas/listarMensagensConversa",
                payloadListarMensagens(conversaId, marcarComoVisualizada),
                urlBase + "/messages/inbox/" + conversaId
            );

            JsonNode itens = root.path("result").path("mensagensDaConversa");
            if (itens.isArray()) {
                for (JsonNode item : itens) {
                    mensagens.add(parseMensagemConversa(item, pageFetcher));
                }
            }
        } catch (Exception e) {
            log.debug("Erro ao listar mensagens da conversa {}: {}", conversaId, e.getMessage());
        }
        return mensagens;
    }

    private Map<String, Object> payloadListarConversas(Long idPessoa) {
        Map<String, Object> data = new HashMap<>();
        data.put("diretorio", "inbox");
        data.put("idTagPessoa", null);
        data.put("idProjeto", null);
        data.put("query", "");
        data.put("limit", 20);
        data.put("buscarQtd", true);
        data.put("apenasNaoLidas", null);
        data.put("idPessoa", String.valueOf(idPessoa));
        data.put("start", 0);
        data.put("idConversa", null);
        data.put("dhCorte", 0);
        return data;
    }

    private Map<String, Object> payloadListarMensagens(Long conversaId, boolean marcarComoVisualizada) {
        Map<String, Object> data = new HashMap<>();
        data.put("idConversa", conversaId);
        data.put("reverse", true);
        data.put("visualizar", marcarComoVisualizada);
        data.put("dhCorte", 0);
        data.put("start", 0);
        data.put("limit", 100);
        return data;
    }

    private ConversaResumo parseConversaResumo(JsonNode item) {
        Long idConversa = longOuNull(item.path("idConversa"));
        Long idFreelancer = longOuNull(item.path("idFreelancer"));
        Long idCliente = longOuNull(item.path("idCliente"));
        registrarAutoresConversa(idConversa, idFreelancer, idCliente);

        return ConversaResumo.builder()
            .idConversa(idConversa)
            .idProjeto(item.path("idProjeto").asLong())
            .nomeProjeto(item.path("nomeProjeto").asText(null))
            .idCliente(idCliente)
            .nomeCliente(item.path("nomeCliente").asText(null))
            .idFreelancer(idFreelancer)
            .nomeFreelancer(item.path("nomeFreelancer").asText(null))
            .ultimaMensagem(item.path("ultimaMensagem").path("descricaoCurta").asText(null))
            .dataUltimaMensagemMillis(item.path("dhUltimaMensagemInMillis").asLong())
            .visualizada(item.path("visualizada").asBoolean())
            .readOnly(item.path("readOnly").asBoolean())
            .build();
    }

    private MensagemConversa parseMensagemConversa(JsonNode item, PageFetcher pageFetcher) throws Exception {
        Long conversaId = longOuNull(item.path("conversa").path("id"));
        Long pessoaId = longOuNull(item.path("pessoa").path("id"));
        return MensagemConversa.builder()
            .id(longOuNull(item.path("id")))
            .idConversa(conversaId)
            .idPessoa(pessoaId)
            .nomePessoa(item.path("pessoa").path("nome").asText(null))
            .texto(item.path("texto").asText(null))
            .dataCriacaoMillis(item.path("dhCriacaoInMillis").asLong())
            .enviadaPorMim(mensagemFoiEnviadaPorMim(conversaId, pessoaId, pageFetcher))
            .visualizada(item.path("dhVisualizacaoInMillis").asLong() > 0)
            .excluida(item.path("excluida").asBoolean(false))
            .sistema(item.path("sistema").asBoolean(false))
            .build();
    }

    private void registrarAutoresConversa(Long conversaId, Long idFreelancer, Long idCliente) {
        if (conversaId == null) {
            return;
        }
        if (idFreelancer != null) {
            freelancerIdPorConversa.put(conversaId, idFreelancer);
        }
        if (idCliente != null) {
            clienteIdPorConversa.put(conversaId, idCliente);
        }
    }

    private boolean mensagemFoiEnviadaPorMim(Long conversaId, Long pessoaId, PageFetcher pageFetcher) throws Exception {
        Long idFreelancer = conversaId != null ? freelancerIdPorConversa.get(conversaId) : null;
        Long idCliente = conversaId != null ? clienteIdPorConversa.get(conversaId) : null;
        return FreelasApiUtils.isOwnConversationMessage(
            pessoaId,
            obterPessoaLogadaId(pageFetcher),
            idFreelancer,
            idCliente
        );
    }

    private Long longOuNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        long valor = node.asLong(0L);
        return valor > 0L ? valor : null;
    }

    private Long obterPessoaLogadaId(PageFetcher pageFetcher) throws Exception {
        if (pessoaLogadaId != null) {
            return pessoaLogadaId;
        }

        try {
            String dashboard = pageFetcher.get(urlBase + "/dashboard");
            pessoaLogadaId = FreelasApiUtils.extractLoggedPersonId(dashboard);
            if (pessoaLogadaId != null) {
                log.debug("[AUTH] Identidade da sessao confirmada via dashboard");
                return pessoaLogadaId;
            }
        } catch (Exception e) {
            log.debug("Nao foi possivel identificar a pessoa logada via dashboard: {}", e.getMessage());
        }

        try {
            Map<String, String> cookies = autenticacaoService.obterCookies();
            String kmlicin = cookies.get("kmlicin");
            if (kmlicin != null && !kmlicin.isBlank()) {
                pessoaLogadaId = Long.parseLong(kmlicin.trim());
                log.debug("[AUTH] Identidade da sessao confirmada via cookie");
                return pessoaLogadaId;
            }
        } catch (Exception e) {
            log.warn("[AUTH] Falha ao extrair identidade da sessao via cookie");
        }

        log.warn("[AUTH] Não foi possível identificar o ID da pessoa logada por nenhum método");
        return pessoaLogadaId;
    }

    @FunctionalInterface
    interface ServicoFetcher {
        JsonNode get(String path, Map<String, Object> data, String referer) throws Exception;
    }

    @FunctionalInterface
    interface PageFetcher {
        String get(String url) throws Exception;
    }
}
