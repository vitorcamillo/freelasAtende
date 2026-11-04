package com.freelasatende.model.entity;

import com.freelasatende.model.enums.StatusMensagemCliente;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.TipoAcaoPendente;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "mensagens_cliente_registro")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MensagemClienteRegistro {

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

    @Column(name = "fingerprint", nullable = false, length = 96)
    private String fingerprint;

    @Column(name = "texto_preview", length = 1000)
    private String textoPreview;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private StatusMensagemCliente status;

    @Column(name = "motivo_bloqueio", length = 500)
    private String motivoBloqueio;

    @Column(name = "data", nullable = false)
    private LocalDateTime data;

    @PrePersist
    @PreUpdate
    @PostLoad
    void normalizarPlataforma() {
        plataforma = PlataformaFreela.normalizar(plataforma);
    }
}
