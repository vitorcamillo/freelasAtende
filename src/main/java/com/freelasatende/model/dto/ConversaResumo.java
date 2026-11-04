package com.freelasatende.model.dto;

import com.freelasatende.model.enums.PlataformaFreela;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resumo de uma conversa da inbox.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversaResumo {

    @Builder.Default
    private PlataformaFreela plataforma = PlataformaFreela.padrao();
    private Long idConversa;
    private Long idProjeto;
    private String nomeProjeto;
    private Long idCliente;
    private String nomeCliente;
    private Long idFreelancer;
    private String nomeFreelancer;
    private String ultimaMensagem;
    private Long dataUltimaMensagemMillis;
    private Boolean visualizada;
    private Boolean readOnly;
}
