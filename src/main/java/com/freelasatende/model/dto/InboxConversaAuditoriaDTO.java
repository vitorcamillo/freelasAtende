package com.freelasatende.model.dto;

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
public class InboxConversaAuditoriaDTO {

    private Long auditoriaId;
    private LocalDateTime dataAuditoria;
    private Long conversaId;
    private Long projetoId;
    private String tituloProjeto;
    private Long clienteId;
    private String nomeCliente;
    private int totalMensagens;
    private int mensagensCliente;
    private int mensagensMinhas;
    private Long ultimaMensagemId;
    private String ultimaMensagemAutor;
    private String ultimaMensagemTexto;
    private Long ultimaMensagemMillis;
    private String status;
    private String motivo;
    private String analiseOperacional;
    private boolean acionavel;
    private boolean pendenciaExistente;
    private Long acaoPendenteId;
    private String rascunhoPendente;
    private String decisaoIa;
    private String perguntaParaUsuario;
    private List<String> duvidasInternas;
    private Integer scoreRisco;
    private String decisaoQualidade;
    private String comentarioQualidade;
    private List<String> sinaisQualidade;
    private List<String> sinais;
}
