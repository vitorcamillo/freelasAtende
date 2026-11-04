package com.freelasatende.model.dto;

import com.freelasatende.model.enums.TipoAcaoPendente;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnsinoFocoDTO {

    private boolean ativo;
    private Long acaoId;
    private Long projetoId;
    private Long conversaId;
    private TipoAcaoPendente tipo;
    private String tituloProjeto;
    private String resumoInterno;
    private String textoClientePreview;
    private LocalDateTime desde;
    private String status;
    private String mensagem;
}
