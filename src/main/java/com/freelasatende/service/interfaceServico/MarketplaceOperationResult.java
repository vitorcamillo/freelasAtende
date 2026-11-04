package com.freelasatende.service.interfaceServico;

import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.service.impl.MarketplaceAdapterErrorType;

/**
 * Resultado normalizado para operações de adapter que precisam ser exibidas ao operador.
 */
public record MarketplaceOperationResult<T>(
    PlataformaFreela plataforma,
    boolean sucesso,
    T valor,
    MarketplaceAdapterErrorType erro,
    String mensagem
) {

    public static <T> MarketplaceOperationResult<T> sucesso(PlataformaFreela plataforma, T valor) {
        return new MarketplaceOperationResult<>(
            PlataformaFreela.normalizar(plataforma),
            true,
            valor,
            null,
            null
        );
    }

    public static <T> MarketplaceOperationResult<T> erro(
        PlataformaFreela plataforma,
        MarketplaceAdapterErrorType erro,
        String mensagem
    ) {
        return new MarketplaceOperationResult<>(
            PlataformaFreela.normalizar(plataforma),
            false,
            null,
            erro,
            mensagem
        );
    }
}
