package com.freelasatende.service.impl;

import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import com.freelasatende.service.interfaceServico.ScraperService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class MarketplaceAdapterRegistry {

    private final Map<PlataformaFreela, MarketplaceAdapter> adapters;
    private final Set<PlataformaFreela> habilitadas;
    private final boolean todasHabilitadas;

    @Autowired
    public MarketplaceAdapterRegistry(
        List<MarketplaceAdapter> adapters,
        @Value("${marketplaces.enabled:99FREELAS}") String enabled
    ) {
        this.adapters = new EnumMap<>(PlataformaFreela.class);
        for (MarketplaceAdapter adapter : adapters) {
            if (adapter != null) {
                if (adapter.plataforma() == null) {
                    throw new IllegalArgumentException("Adapter deve declarar marketplace explicitamente");
                }
                this.adapters.put(adapter.plataforma(), adapter);
            }
        }
        String valor = enabled == null ? "" : enabled.trim();
        this.todasHabilitadas = valor.isBlank()
            || "*".equals(valor)
            || "all".equalsIgnoreCase(valor)
            || "todas".equalsIgnoreCase(valor);
        this.habilitadas = this.todasHabilitadas
            ? Set.of()
            : List.of(valor.split(",")).stream()
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(PlataformaFreela::fromKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    public MarketplaceAdapterRegistry(List<MarketplaceAdapter> adapters) {
        this(adapters, "*");
    }

    public static MarketplaceAdapterRegistry compat(ScraperService scraperService) {
        return new MarketplaceAdapterRegistry(List.of(new Freelas99MarketplaceAdapter(scraperService)));
    }

    public MarketplaceAdapter para(PlataformaFreela plataforma) {
        if (plataforma == null) {
            throw new IllegalArgumentException("Marketplace deve ser informado explicitamente");
        }
        PlataformaFreela normalizada = plataforma;
        MarketplaceAdapter adapter = adapters.get(normalizada);
        if (adapter == null) {
            throw new MarketplaceAdapterException(
                normalizada,
                MarketplaceAdapterErrorType.PLATAFORMA_NAO_SUPORTADA,
                "Plataforma sem adapter configurado: " + normalizada.key()
            );
        }
        return adapter;
    }

    public List<MarketplaceAdapter> ativos() {
        return adapters.entrySet().stream()
            .filter(entry -> todasHabilitadas || habilitadas.contains(entry.getKey()))
            .map(Map.Entry::getValue)
            .toList();
    }
}
