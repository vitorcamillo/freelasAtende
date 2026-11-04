package com.freelasatende.model.entity;

import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusProjeto;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entidade que representa um projeto no 99Freelas.
 */
@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(
    name = "projetos",
    indexes = {
        @Index(name = "idx_projetos_plataforma_id_externo", columnList = "plataforma,id_externo")
    },
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_projetos_plataforma_id_externo",
            columnNames = {"plataforma", "id_externo"}
        )
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Projeto {

    @Id
    private Long id;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "plataforma", length = 40)
    private PlataformaFreela plataforma = PlataformaFreela.padrao();

    @Column(name = "id_externo")
    private Long idExterno;

    @Column(nullable = false)
    private String titulo;

    @Column(columnDefinition = "TEXT")
    private String descricao;

    private String descricaoCurta;

    private String categoria;

    @Column(columnDefinition = "TEXT")
    private String subcategoria;

    private BigDecimal orcamento;

    @Column(name = "valor_minimo")
    private BigDecimal valorMinimo;

    @Column(name = "numero_propostas")
    private Integer numeroPropostas;

    @Column(name = "media_proposta")
    private BigDecimal mediaProposta;

    @Column(name = "nivel_experiencia")
    private String nivelExperiencia;

    @Column(name = "interessados")
    private Integer interessados;

    @Column(name = "url_projeto")
    private String urlProjeto;

    @Column(name = "nome_cliente")
    private String nomeCliente;

    @Column(name = "cliente_id")
    private Long clienteId;

    @Column(name = "data_criacao")
    private LocalDateTime dataCriacao;

    @Enumerated(EnumType.STRING)
    private StatusProjeto status;

    @Column(name = "foi_processado")
    private Boolean foiProcessado;

    @Column(name = "data_processamento")
    private LocalDateTime dataProcessamento;

    @Column(name = "motivo_recusa", length = 1000)
    private String motivoRecusa;

    @Builder.Default
    @Transient
    private java.util.List<String> anexosUrls = new java.util.ArrayList<>();

    @PrePersist
    @PreUpdate
    @PostLoad
    void normalizarPlataforma() {
        plataforma = PlataformaFreela.normalizar(plataforma);
        if (idExterno == null) {
            idExterno = plataforma.idExterno(id);
        }
        if (id == null) {
            id = plataforma.idInterno(idExterno);
        } else if (idExterno != null) {
            id = plataforma.idInterno(idExterno);
        }
    }
}
