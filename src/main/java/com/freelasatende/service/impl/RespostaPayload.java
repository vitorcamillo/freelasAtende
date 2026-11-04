package com.freelasatende.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.freelasatende.model.dto.ConversaMensagemControleDTO;
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
class RespostaPayload {
    private PlataformaFreela plataforma;
    private Long conversaId;
    private Long projetoId;
    private Long projetoIdExterno;
    private String tituloProjeto;
    private String mensagem;
    private String historicoConversa;
    private String contextoProjeto;
    private String enviosAnteriores;
    private Long mensagemGatilhoId;
    private String mensagemGatilhoTexto;
    private String mensagemGatilhoAutor;
    private String mensagemGatilhoNome;
    private Long idCliente;
    private String nomeCliente;
    private Long idFreelancer;
    private String nomeFreelancer;
    private List<ConversaMensagemControleDTO> ultimasMensagensMinhas;
    private List<ConversaMensagemControleDTO> ultimasMensagensCliente;
    private List<String> mensagensIgnoradasResumo;
    private boolean autoriaConfiavel;
    private String motivoAutoria;
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
