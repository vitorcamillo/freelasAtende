package com.freelasatende.model.entity;

import com.freelasatende.model.enums.PlataformaFreela;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
@Table(
    name = "inbox_conversas_auditoria",
    indexes = {
        @Index(name = "idx_inbox_conversas_auditoria_auditoria", columnList = "auditoria_id"),
        @Index(name = "idx_inbox_conversas_auditoria_conversa", columnList = "conversa_id")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboxConversaAuditoriaRegistro {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "plataforma", length = 40)
    private PlataformaFreela plataforma = PlataformaFreela.padrao();

    @Column(name = "auditoria_id", nullable = false)
    private Long auditoriaId;

    @Column(name = "data_auditoria", nullable = false)
    private LocalDateTime dataAuditoria;

    @Column(name = "conversa_id")
    private Long conversaId;

    @Column(name = "projeto_id")
    private Long projetoId;

    @Column(name = "titulo_projeto")
    private String tituloProjeto;

    @Column(name = "cliente_id")
    private Long clienteId;

    @Column(name = "nome_cliente")
    private String nomeCliente;

    @Column(name = "total_mensagens", nullable = false)
    private int totalMensagens;

    @Column(name = "mensagens_cliente", nullable = false)
    private int mensagensCliente;

    @Column(name = "mensagens_minhas", nullable = false)
    private int mensagensMinhas;

    @Column(name = "ultima_mensagem_id")
    private Long ultimaMensagemId;

    @Column(name = "ultima_mensagem_autor")
    private String ultimaMensagemAutor;

    @Column(name = "ultima_mensagem_texto", columnDefinition = "TEXT")
    private String ultimaMensagemTexto;

    @Column(name = "ultima_mensagem_millis")
    private Long ultimaMensagemMillis;

    @Column(name = "status")
    private String status;

    @Column(name = "motivo", length = 1000)
    private String motivo;

    @Column(name = "analise_operacional", columnDefinition = "TEXT")
    private String analiseOperacional;

    @Column(name = "acionavel", nullable = false)
    private boolean acionavel;

    @Column(name = "pendencia_existente", nullable = false)
    private boolean pendenciaExistente;

    @Column(name = "acao_pendente_id")
    private Long acaoPendenteId;

    @Column(name = "rascunho_pendente", columnDefinition = "TEXT")
    private String rascunhoPendente;

    @Column(name = "decisao_ia")
    private String decisaoIa;

    @Column(name = "pergunta_para_usuario", columnDefinition = "TEXT")
    private String perguntaParaUsuario;

    @Column(name = "duvidas_internas_json", columnDefinition = "TEXT")
    private String duvidasInternasJson;

    @Column(name = "sinais_json", columnDefinition = "TEXT")
    private String sinaisJson;

    @Column(name = "score_risco")
    private Integer scoreRisco;

    @Column(name = "decisao_qualidade")
    private String decisaoQualidade;

    @Column(name = "comentario_qualidade", columnDefinition = "TEXT")
    private String comentarioQualidade;

    @Column(name = "sinais_qualidade_json", columnDefinition = "TEXT")
    private String sinaisQualidadeJson;

    @PrePersist
    @PreUpdate
    @PostLoad
    void normalizarPlataforma() {
        plataforma = PlataformaFreela.normalizar(plataforma);
    }
}
