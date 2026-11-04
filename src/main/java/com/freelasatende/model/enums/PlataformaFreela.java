package com.freelasatende.model.enums;

import java.util.Locale;

public enum PlataformaFreela {
    FREELAS_99("99FREELAS", "99Freelas", 0),
    TESTE("TESTE", "Teste", 1),
    WORKANA("WORKANA", "Workana", 2),
    UPWORK("UPWORK", "Upwork", 3),
    FREELANCER("FREELANCER", "Freelancer.com", 4),
    FREELANCER_BR("FREELANCER_BR", "Freelancer.com.br", 5);

    private static final long ID_OFFSET = 1_000_000_000_000L;

    private final String key;
    private final String label;
    private final long namespaceId;

    PlataformaFreela(String key, String label, long namespaceId) {
        this.key = key;
        this.label = label;
        this.namespaceId = namespaceId;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public static PlataformaFreela padrao() {
        return FREELAS_99;
    }

    public static PlataformaFreela normalizar(PlataformaFreela plataforma) {
        return plataforma != null ? plataforma : padrao();
    }

    public static PlataformaFreela fromKey(String valor) {
        if (valor == null || valor.isBlank()) {
            return padrao();
        }
        String normalizado = valor.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        for (PlataformaFreela plataforma : values()) {
            if (plataforma.name().equals(normalizado) || plataforma.key.equalsIgnoreCase(valor.trim())) {
                return plataforma;
            }
        }
        throw new IllegalArgumentException("Marketplace desconhecido: " + valor);
    }

    public Long idInterno(Long idExterno) {
        if (idExterno == null) {
            return null;
        }
        if (this == FREELAS_99) {
            return idExterno;
        }
        return (namespaceId * ID_OFFSET) + idExterno;
    }

    public Long idExterno(Long idInterno) {
        if (idInterno == null) {
            return null;
        }
        if (this == FREELAS_99) {
            return idInterno;
        }
        long base = namespaceId * ID_OFFSET;
        return idInterno >= base ? idInterno - base : idInterno;
    }
}
