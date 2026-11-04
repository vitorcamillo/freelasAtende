package com.freelasatende.service.impl;

public final class MarketplaceSessionFailure {

    private MarketplaceSessionFailure() {
    }

    public static boolean isSessao99FreelasInvalida(Throwable throwable) {
        Throwable atual = throwable;
        while (atual != null) {
            String mensagem = atual.getMessage();
            if (mensagem != null
                && !mensagem.contains("Workana")
                && (mensagem.contains("Sessao 99Freelas invalida")
                    || mensagem.contains("HTTP 401")
                    || mensagem.contains("HTTP 403"))) {
                return true;
            }
            atual = atual.getCause();
        }
        return false;
    }

    public static boolean isSessaoWorkanaInvalida(Throwable throwable) {
        Throwable atual = throwable;
        while (atual != null) {
            if (atual instanceof MarketplaceAdapterException marketplaceError
                && marketplaceError.getPlataforma() == com.freelasatende.model.enums.PlataformaFreela.WORKANA) {
                return marketplaceError.getType() == MarketplaceAdapterErrorType.FALHA_AUTENTICACAO;
            }
            String mensagem = atual.getMessage();
            if (isAcessoNegadoFormWorkana(mensagem)) {
                return false;
            }
            if (mensagem != null
                && mensagem.contains("Workana")
                && (mensagem.contains("HTTP 401")
                    || mensagem.contains("HTTP 403")
                    || mensagem.contains("Acesso Negado")
                    || mensagem.contains("Voce nao possui autorizacao")
                    || mensagem.contains("Você não possui autorização"))) {
                return true;
            }
            atual = atual.getCause();
        }
        return false;
    }

    private static boolean isAcessoNegadoFormWorkana(String mensagem) {
        if (mensagem == null || !mensagem.contains("Workana form de pergunta retornou HTTP 403")) {
            return false;
        }
        return mensagem.contains("Acesso Negado")
            || mensagem.contains("Voce nao possui autorizacao")
            || mensagem.contains("Você não possui autorização");
    }

    public static boolean isSessaoFreelancerBrInvalida(Throwable throwable) {
        Throwable atual = throwable;
        while (atual != null) {
            if (atual instanceof MarketplaceAdapterException marketplaceError
                && marketplaceError.getPlataforma() == com.freelasatende.model.enums.PlataformaFreela.FREELANCER_BR) {
                return marketplaceError.getType() == MarketplaceAdapterErrorType.FALHA_AUTENTICACAO;
            }
            atual = atual.getCause();
        }
        return false;
    }

    public static boolean isSessaoMarketplaceInvalida(Throwable throwable) {
        return isSessao99FreelasInvalida(throwable)
            || isSessaoWorkanaInvalida(throwable)
            || isSessaoFreelancerBrInvalida(throwable);
    }

    public static boolean isFalhaTemporariaRede(Throwable throwable) {
        Throwable atual = throwable;
        while (atual != null) {
            String mensagem = atual.getMessage();
            if (mensagem != null
                && (mensagem.matches(".*HTTP 5\\d\\d.*")
                    || mensagem.contains("timed out")
                    || mensagem.contains("Connection reset"))) {
                return true;
            }
            atual = atual.getCause();
        }
        return false;
    }
}
