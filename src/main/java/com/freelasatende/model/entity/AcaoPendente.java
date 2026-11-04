package com.freelasatende.model.entity;

import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Acao aguardando confirmacao do usuario ou execucao posterior.
 */
@Entity
@Table(name = "acoes_pendentes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AcaoPendente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "plataforma", length = 40)
    private PlataformaFreela plataforma = PlataformaFreela.padrao();

    @Column(name = "projeto_id")
    private Long projetoId;

    @Column(name = "conversa_id")
    private Long conversaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false)
    private TipoAcaoPendente tipo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private StatusAcaoPendente status;

    @Enumerated(EnumType.STRING)
    @Column(name = "modo_origem")
    private ModoSistema modoOrigem;

    @Column(name = "titulo_projeto")
    private String tituloProjeto;

    @Column(name = "resumo", columnDefinition = "TEXT")
    private String resumo;

    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "data_criacao", nullable = false)
    private LocalDateTime dataCriacao;

    @Column(name = "data_atualizacao")
    private LocalDateTime dataAtualizacao;

    @Column(name = "observacao_usuario", columnDefinition = "TEXT")
    private String observacaoUsuario;

    @PrePersist
    @PreUpdate
    @PostLoad
    void normalizarPlataforma() {
        plataforma = PlataformaFreela.normalizar(plataforma);
    }
}
