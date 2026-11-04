package com.freelasatende.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.freelasatende.model.enums.NivelAutonomia;
import com.freelasatende.model.enums.PlataformaFreela;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
class PropostaPayload {
    private PlataformaFreela plataforma;
    private Long projetoId;
    private Long projetoIdExterno;
    private String tituloProjeto;
    private String urlProjeto;
    private BigDecimal valorProposto;
    private BigDecimal valorMinimo;
    private BigDecimal mediaMercado;
    private String origemValor;
    private String contextoProjeto;
    private String mensagem;
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
