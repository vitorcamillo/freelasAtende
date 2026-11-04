package com.freelasatende.model.dto;

import com.freelasatende.model.enums.PlataformaFreela;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Mensagem individual de uma conversa no 99Freelas.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MensagemConversa {

    @Builder.Default
    private PlataformaFreela plataforma = PlataformaFreela.padrao();
    private Long id;
    private Long idConversa;
    private Long idPessoa;
    private String nomePessoa;
    private String texto;
    private Long dataCriacaoMillis;
    private Boolean enviadaPorMim;
    private Boolean visualizada;
    private Boolean excluida;
    private Boolean sistema;
}
