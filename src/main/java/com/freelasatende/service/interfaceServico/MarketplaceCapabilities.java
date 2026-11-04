package com.freelasatende.service.interfaceServico;

/**
 * Capacidades operacionais expostas por uma plataforma de freelas.
 */
public record MarketplaceCapabilities(
    boolean discovery,
    boolean inbox,
    boolean perguntas,
    boolean propostas,
    boolean cancelamento,
    boolean conexoes,
    boolean mfa
) {

    public static MarketplaceCapabilities completa() {
        return new MarketplaceCapabilities(true, true, true, true, true, true, true);
    }
}
