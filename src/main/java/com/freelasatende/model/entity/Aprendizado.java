package com.freelasatende.model.entity;

import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.MotivoAprendizado;
import com.freelasatende.model.enums.StatusRegraAprendida;
import com.freelasatende.model.enums.TipoAcaoPendente;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entidade que armazena aprendizados do sistema.
 * Usado para melhorar decisões futuras.
 */
@Entity
@Table(name = "aprendizados")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Aprendizado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "data", nullable = false)
    private LocalDateTime data;

    @Column(name = "projeto_id")
    private Long projetoId;

    @Column(name = "titulo_projeto")
    private String tituloProjeto;

    @Column(name = "foi_aprovado")
    private Boolean foiAprovado;

    @Column(columnDefinition = "TEXT")
    private String motivo;

    @Enumerated(EnumType.STRING)
    @Column(name = "modo_usado")
    private ModoSistema modoUsado;

    @Column(name = "feedback_usuario", columnDefinition = "TEXT")
    private String feedbackUsuario;

    @Column(name = "regras_aprendidas", columnDefinition = "TEXT")
    private String regrasAprendidas;

    @Column(name = "categoria_projeto")
    private String categoriaProjeto;

    @Enumerated(EnumType.STRING)
    @Column(name = "motivo_aprendizado")
    private MotivoAprendizado motivoAprendizado;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_acao")
    private TipoAcaoPendente tipoAcao;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_regra")
    private StatusRegraAprendida statusRegra;

    @Column(name = "texto_original", columnDefinition = "TEXT")
    private String textoOriginal;

    @Column(name = "texto_reescrito", columnDefinition = "TEXT")
    private String textoReescrito;

    @Column(name = "origem_feedback")
    private String origemFeedback;
}
