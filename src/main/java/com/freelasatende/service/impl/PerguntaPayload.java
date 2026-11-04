package com.freelasatende.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.freelasatende.model.enums.NivelAutonomia;
import com.freelasatende.model.enums.PlataformaFreela;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
class PerguntaPayload {
    private PlataformaFreela plataforma;
    private Long projetoId;
    private Long projetoIdExterno;
    private String tituloProjeto;
    private String urlProjeto;
    private String mensagem;
    private String contextoProjeto;
    private List<String> duvidas;
    private NivelAutonomia nivelAutonomia;
    private String entendimentoIa;
    private String analiseIa;
    private List<String> opcoesIa;
    private String atitudeSugerida;
    private boolean conversaEnsinoObrigatoria;
    private List<String> informacoesConfirmadas;
    private List<String> informacoesPendentes;
    private List<String> sabeFazer;
    private List<String> duvidasInternas;
    private List<String> perguntasParaCliente;
    private List<String> riscosDecisao;
    private String proximaAcaoSugerida;
    private String perguntaParaUsuario;
    private String regraAprendizadoSugerida;
}
