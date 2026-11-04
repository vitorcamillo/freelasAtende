package com.freelasatende.model.dto;

import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.NivelAutonomia;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AcaoControleDTO {

    private Long id;
    @Builder.Default
    private PlataformaFreela plataforma = PlataformaFreela.padrao();
    private String plataformaLabel;
    private Long projetoId;
    private Long conversaId;
    private TipoAcaoPendente tipo;
    private StatusAcaoPendente status;
    private ModoSistema modoOrigem;
    private String tituloProjeto;
    private String resumoInterno;
    private String textoClientePreview;
    private NivelAutonomia nivelAutonomia;
    private String entendimentoIa;
    private String analiseIa;
    private List<String> opcoesIa;
    private String atitudeSugerida;
    private boolean conversaEnsinoObrigatoria;
    private boolean conversaEnsinoRespondida;
    private List<String> informacoesConfirmadas;
    private List<String> informacoesPendentes;
    private List<String> sabeFazer;
    private List<String> duvidasInternas;
    private List<String> perguntasParaCliente;
    private List<String> riscosDecisao;
    private String proximaAcaoSugerida;
    private String perguntaParaUsuario;
    private String regraAprendizadoSugerida;
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
    private String statusGuardiao;
    private String motivoBloqueio;
    private String nivelConfianca;
    private Integer scoreNaturalidade;
    private Integer scoreClareza;
    private Integer scoreRisco;
    private Integer scoreDuplicidade;
    private Integer scoreConfianca;
    private String decisaoQualidade;
    private String comentarioQualidade;
    private List<String> sinaisQualidade;
    private List<String> sinaisRisco;
    private List<RegraOperacionalDTO> checklistRegras;
    private boolean feedbackObrigatorio;
    private List<String> motivosAprendizado;
    private String ensinoStatus;
    private boolean focoEnsinoAtual;
    private LocalDateTime dataCriacao;
    private LocalDateTime dataAtualizacao;
    private String observacaoUsuario;
}
