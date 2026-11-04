package com.freelasatende.model.entity;

import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.TipoInteracao;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entidade para historico de interacoes.
 */
@Entity
@Table(name = "interacoes",
    indexes = {
        @Index(name = "idx_interacoes_data", columnList = "data")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Interacao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "plataforma", length = 40)
    private PlataformaFreela plataforma = PlataformaFreela.padrao();

    @Column(name = "projeto_id")
    private Long projetoId;

    @Column(name = "projeto_titulo")
    private String projetoTitulo;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo")
    private TipoInteracao tipo;

    @Column(name = "conteudo", length = 5000)
    private String conteudo;

    @Column(name = "data")
    private LocalDateTime data;

    @Enumerated(EnumType.STRING)
    @Column(name = "modo_usado")
    private ModoSistema modoUsado;

    @PrePersist
    @PreUpdate
    @PostLoad
    void normalizarPlataforma() {
        plataforma = PlataformaFreela.normalizar(plataforma);
    }
}
