package com.freelasatende.service.impl;

import com.freelasatende.model.enums.PlataformaFreela;

public class MarketplaceAdapterException extends RuntimeException {

    private final PlataformaFreela plataforma;
    private final MarketplaceAdapterErrorType type;

    public MarketplaceAdapterException(
        PlataformaFreela plataforma,
        MarketplaceAdapterErrorType type,
        String message
    ) {
        super(message);
        this.plataforma = plataforma;
        this.type = type;
    }

    public MarketplaceAdapterException(
        PlataformaFreela plataforma,
        MarketplaceAdapterErrorType type,
        String message,
        Throwable cause
    ) {
        super(message, cause);
        this.plataforma = plataforma;
        this.type = type;
    }

    public PlataformaFreela getPlataforma() {
        return plataforma;
    }

    public MarketplaceAdapterErrorType getType() {
        return type;
    }
}
