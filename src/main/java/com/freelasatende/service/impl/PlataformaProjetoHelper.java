package com.freelasatende.service.impl;

import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.PlataformaFreela;

final class PlataformaProjetoHelper {

    private PlataformaProjetoHelper() {
    }

    static PlataformaFreela plataformaOuPadrao(PlataformaFreela plataforma) {
        if (plataforma == null) {
            throw new IllegalArgumentException("Projeto deve informar plataforma explicitamente");
        }
        return plataforma;
    }

    static PlataformaFreela plataformaOuPadrao(Projeto projeto) {
        if (projeto == null) {
            throw new IllegalArgumentException("Projeto deve ser informado para resolver plataforma");
        }
        return plataformaOuPadrao(projeto.getPlataforma());
    }

    static Long idExternoOuId(Projeto projeto) {
        if (projeto == null) {
            return null;
        }
        if (projeto.getIdExterno() != null) {
            return projeto.getIdExterno();
        }
        return plataformaOuPadrao(projeto).idExterno(projeto.getId());
    }

    static Long idInterno(PlataformaFreela plataforma, Long idExterno) {
        return plataformaOuPadrao(plataforma).idInterno(idExterno);
    }

    static Projeto normalizarProjeto(Projeto projeto) {
        if (projeto == null) {
            return null;
        }
        PlataformaFreela plataforma = plataformaOuPadrao(projeto);
        Long idExterno = projeto.getIdExterno() != null ? projeto.getIdExterno() : plataforma.idExterno(projeto.getId());
        projeto.setPlataforma(plataforma);
        projeto.setIdExterno(idExterno);
        projeto.setId(idInterno(plataforma, idExterno));
        return projeto;
    }
}
