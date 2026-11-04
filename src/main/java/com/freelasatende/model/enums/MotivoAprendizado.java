package com.freelasatende.model.enums;

import java.text.Normalizer;

/**
 * Motivos estruturados usados para transformar feedback humano em aprendizado.
 */
public enum MotivoAprendizado {
    BOM_TOM,
    ESCOPO_CLARO,
    ROBOTICO,
    GENERICO,
    RISCO_PLATAFORMA,
    DUPLICADO,
    PERGUNTAS_DEMAIS,
    FORA_DO_TOM,
    OUTRO;

    public static MotivoAprendizado fromTexto(String texto) {
        String normalizado = normalizar(texto);
        if (normalizado.isBlank()) {
            return OUTRO;
        }
        if (normalizado.contains("bom tom") || normalizado.contains("tom bom") || normalizado.contains("bom_tom")
            || normalizado.contains("natural") || normalizado.contains("boa resposta")) {
            return BOM_TOM;
        }
        if (normalizado.contains("escopo claro") || normalizado.contains("escopo_claro")
            || normalizado.contains("claro") || normalizado.contains("clara")
            || normalizado.contains("alinhado") || normalizado.contains("alinhada")) {
            return ESCOPO_CLARO;
        }
        if (normalizado.contains("robot") || normalizado.contains("sistema")
            || normalizado.contains("template") || normalizado.contains("automat")) {
            return ROBOTICO;
        }
        if (normalizado.contains("generico") || normalizado.contains("generica")) {
            return GENERICO;
        }
        if (normalizado.contains("fora do tom") || normalizado.contains("tom errado")
            || normalizado.contains("frio demais")) {
            return FORA_DO_TOM;
        }
        if (normalizado.contains("risco") || normalizado.contains("plataforma")
            || normalizado.contains("whatsapp") || normalizado.contains("telefone")
            || normalizado.contains("pagamento") || normalizado.contains("email")
            || normalizado.contains("gmail") || normalizado.contains("pix")
            || normalizado.contains("zap") || normalizado.contains("whats")
            || normalizado.contains("wpp") || normalizado.contains("fora")) {
            return RISCO_PLATAFORMA;
        }
        if (normalizado.contains("duplic")) {
            return DUPLICADO;
        }
        if (normalizado.contains("perguntas demais") || normalizado.contains("perguntou demais")
            || normalizado.contains("muitas perguntas")) {
            return PERGUNTAS_DEMAIS;
        }
        return OUTRO;
    }

    private static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        return Normalizer.normalize(texto, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase()
            .replaceAll("[^a-z0-9_]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
