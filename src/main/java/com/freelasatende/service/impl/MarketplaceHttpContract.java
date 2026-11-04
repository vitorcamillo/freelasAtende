package com.freelasatende.service.impl;

import com.freelasatende.util.AtendimentoTextos;

final class MarketplaceHttpContract {

    private MarketplaceHttpContract() {
    }

    static String mensagemInicialWorkana(String mensagem) {
        if (!AtendimentoTextos.isAbordagemInicial(mensagem)) {
            return mensagem;
        }
        return "Ola, vi seu projeto e ainda esta em aberto? Se não foi atendindo posso lhe ajudar?";
    }

    static String mensagemInicialFreelancer(String mensagem) {
        if (!AtendimentoTextos.isAbordagemInicial(mensagem)) {
            return mensagem;
        }
        return "Ola! Tenho interesse no seu projeto e posso entrega-lo com qualidade. Ainda esta aberto?";
    }

    static String freelancerThreadsUrl(String baseUrl) {
        return baseUrl + "/api/messages/0.1/threads?limit=50"
            + "&last_message=true&unread_count=true&user_details=true&context_details=true";
    }

    static String freelancerMessagesUrl(String baseUrl, long threadId) {
        return baseUrl + "/api/messages/0.1/messages?threads%5B%5D=" + threadId
            + "&user_details=true&limit=100";
    }

    static String freelancerThreadMessageUrl(String baseUrl, long threadId) {
        return baseUrl + "/api/messages/0.1/threads/" + threadId + "/messages/";
    }

    static int freelancerMilestonePercentage() {
        return 100;
    }

    static String resumirResposta(String body) {
        if (body == null || body.isBlank()) {
            return "corpo vazio";
        }
        String resumo = body.replaceAll("\\s+", " ").trim();
        return resumo.length() <= 300 ? resumo : resumo.substring(0, 300) + "...";
    }
}
