package com.freelasatende.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entidade que representa um cliente no 99Freelas.
 */
@Entity
@Table(name = "clientes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Cliente {

    @Id
    private Long id;

    @Column(nullable = false)
    private String nome;

    private String urlFoto;

    @Column(name = "media_nota")
    private Double mediaNota;

    @Column(name = "projetos_concluidos")
    private Integer projetosConcluidos;

    @Column(name = "projetos_publicados")
    private Integer projetosPublicados;

    @Column(name = "projetos_em_andamento")
    private Integer projetosEmAndamento;

    @Column(name = "projetos_cancelados")
    private Integer projetosCancelados;

    private Integer recomendacoes;

    @Column(name = "identidade_verificada")
    private Boolean identidadeVerificada;

    private String localizacao;
}
