package com.freelasatende.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConversaMensagemControleDTO {

    private Long id;
    private String autor;
    private String nome;
    private String texto;
    private Long dataCriacaoMillis;
    private boolean ignorada;
    private String motivoIgnorada;
}
