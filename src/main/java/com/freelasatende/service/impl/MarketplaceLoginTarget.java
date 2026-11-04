package com.freelasatende.service.impl;

enum MarketplaceLoginTarget {
    FREELAS_99,
    UPWORK,
    FREELANCER,
    FREELANCER_BR;

    static MarketplaceLoginTarget fromOrigem(String origem) {
        String key = origem;
        if (origem != null) {
            int separator = origem.lastIndexOf(':');
            key = separator >= 0 ? origem.substring(separator + 1) : origem;
        }
        if ("UPWORK".equalsIgnoreCase(key)) {
            return UPWORK;
        }
        // Precisa vir antes de FREELANCER: "FREELANCER_BR" tambem casaria por prefixo.
        if ("FREELANCER_BR".equalsIgnoreCase(key)) {
            return FREELANCER_BR;
        }
        if ("FREELANCER".equalsIgnoreCase(key)) {
            return FREELANCER;
        }
        return FREELAS_99;
    }
}
