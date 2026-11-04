package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.freelasatende.config.WorkanaConfig;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.enums.PlataformaFreela;
import org.jsoup.Jsoup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.freelasatende.service.impl.WorkanaClientSupport.absolutizarUrl;
import static com.freelasatende.service.impl.WorkanaClientSupport.encodePathSegment;
import static com.freelasatende.service.impl.WorkanaClientSupport.longOuNull;

final class WorkanaInboxMapper {

    private WorkanaInboxMapper() {
    }

    static List<ConversaResumo> listarConversas(
        WorkanaConfig config,
        JsonNode projetos,
        Map<Long, WorkanaConversaContext> conversas
    ) {
        List<ConversaResumo> resultado = new ArrayList<>();
        if (!projetos.isArray()) {
            return resultado;
        }
        for (JsonNode projeto : projetos) {
            Long projetoIdExterno = longOuNull(projeto.path("id"));
            Long projetoId = PlataformaFreela.WORKANA.idInterno(projetoIdExterno);
            String nomeProjeto = projeto.path("name").asText("");
            String slugProjeto = projeto.path("slug").asText("");
            for (JsonNode thread : projeto.path("threads")) {
                adicionarConversa(config, conversas, resultado, projetoIdExterno, projetoId, nomeProjeto, slugProjeto, thread);
            }
        }
        return resultado;
    }

    static List<MensagemConversa> listarMensagens(Long conversaId, JsonNode messageIndexData) {
        List<MensagemConversa> mensagens = new ArrayList<>();
        for (JsonNode msg : messageIndexData.path("messages")) {
            Long id = longOuNull(msg.path("id"));
            JsonNode author = msg.path("author");
            boolean enviadaPorMim = msg.path("classes").asText("").contains("self");
            mensagens.add(MensagemConversa.builder()
                .plataforma(PlataformaFreela.WORKANA)
                .id(id)
                .idConversa(conversaId)
                .idPessoa(longOuNull(author.path("identifier")))
                .nomePessoa(author.path("name").asText(""))
                .texto(Jsoup.parse(msg.path("content").asText("")).text())
                .dataCriacaoMillis(id)
                .enviadaPorMim(enviadaPorMim)
                .visualizada(!msg.path("isUnread").asBoolean(false))
                .excluida(false)
                .sistema(!msg.path("isByHuman").asBoolean(true))
                .build());
        }
        return mensagens;
    }

    static WorkanaConversaContext contextoAtualizado(
        WorkanaConfig config,
        WorkanaConversaContext anterior,
        Long conversaId,
        JsonNode messageIndexData
    ) {
        Long threadId = longOuNull(messageIndexData.path("thread").path("id"));
        Long id = threadId != null ? threadId : conversaId;
        JsonNode otherParty = messageIndexData.path("otherParty");
        String postUrl = absolutizarUrl(config, messageIndexData.path("postUrl").asText(anterior.postUrl()));
        return new WorkanaConversaContext(
            id,
            anterior.projetoId(),
            anterior.projetoIdExterno(),
            anterior.slugProjeto(),
            messageIndexData.path("pageTitle").asText(anterior.nomeProjeto()),
            anterior.clienteId(),
            otherParty.path("name").asText(anterior.nomeCliente()),
            anterior.freelancerId(),
            anterior.inboxUrl(),
            postUrl
        );
    }

    private static void adicionarConversa(
        WorkanaConfig config,
        Map<Long, WorkanaConversaContext> conversas,
        List<ConversaResumo> resultado,
        Long projetoIdExterno,
        Long projetoId,
        String nomeProjeto,
        String slugProjeto,
        JsonNode thread
    ) {
        Long conversaId = longOuNull(thread.path("id"));
        if (conversaId == null) {
            return;
        }
        JsonNode otherParty = thread.path("other_party");
        Long clienteId = longOuNull(otherParty.path("user_id"));
        String nomeCliente = otherParty.path("name").asText("");
        String inboxUrl = absolutizarUrl(config, thread.path("roasterUrl").asText(""));
        if (inboxUrl.isBlank()) {
            inboxUrl = absolutizarUrl(config, thread.path("url").asText(""));
        }
        String postUrl = absolutizarUrl(config, thread.path("url").asText(""));
        if (postUrl.isBlank()) {
            postUrl = config.getUrlBase() + "/messages/index/" + encodePathSegment(slugProjeto)
                + "/" + encodePathSegment(config.getWorkerSlug());
        }
        Long freelancerId = longOuNull(otherParty.path("company_id"));
        conversas.put(conversaId, new WorkanaConversaContext(
            conversaId,
            projetoId,
            projetoIdExterno,
            slugProjeto,
            nomeProjeto,
            clienteId,
            nomeCliente,
            freelancerId,
            inboxUrl,
            postUrl
        ));
        resultado.add(ConversaResumo.builder()
            .plataforma(PlataformaFreela.WORKANA)
            .idConversa(conversaId)
            .idProjeto(projetoId)
            .nomeProjeto(nomeProjeto)
            .idCliente(clienteId)
            .nomeCliente(nomeCliente)
            .idFreelancer(freelancerId)
            .nomeFreelancer(config.getWorkerSlug())
            .ultimaMensagem(thread.path("messagePreview").asText(""))
            .dataUltimaMensagemMillis(longOuNull(thread.path("last_post_created")))
            .visualizada(!thread.path("has_unread").asBoolean(false))
            .readOnly(false)
            .build());
    }
}
