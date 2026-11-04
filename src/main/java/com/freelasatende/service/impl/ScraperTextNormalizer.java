package com.freelasatende.service.impl;

import java.text.Normalizer;

final class ScraperTextNormalizer {

    private ScraperTextNormalizer() {
    }

    static String normalizarChave(String texto) {
        if (texto == null) {
            return "";
        }
        return Normalizer.normalize(texto, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase()
            .replace(":", "")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
