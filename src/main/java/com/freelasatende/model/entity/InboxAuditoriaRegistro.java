package com.freelasatende.model.entity;

import com.freelasatende.model.enums.PlataformaFreela;
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
@Table(name = "inbox_auditorias")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboxAuditoriaRegistro {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "plataforma", length = 40)
    private PlataformaFreela plataforma = PlataformaFreela.padrao();

    @Column(name = "data", nullable = false)
    private LocalDateTime data;

    @Column(name = "leitura_sem_marcar_visualizada", nullable = false)
    private boolean leituraSemMarcarVisualizada;

    @Column(name = "escrita_real_habilitada", nullable = false)
    private boolean escritaRealHabilitada;

    @Column(name = "simulacao_envio_habilitada", nullable = false)
    private boolean simulacaoEnvioHabilitada;

    @Column(name = "telegram_simulado", nullable = false)
    private boolean telegramSimulado;

    @Column(name = "ia_real_habilitada", nullable = false)
    private boolean iaRealHabilitada;

    @Column(name = "ia_pronta", nullable = false)
    private boolean iaPronta;

    @Column(name = "motivo_ia", length = 1000)
    private String motivoIa;

    @Column(name = "total_conversas", nullable = false)
    private int totalConversas;

    @Column(name = "total_mensagens", nullable = false)
    private int totalMensagens;

    @Column(name = "total_ultima_cliente", nullable = false)
    private int totalUltimaCliente;

    @Column(name = "total_ultima_minha", nullable = false)
    private int totalUltimaMinha;

    @Column(name = "total_sem_texto_util", nullable = false)
    private int totalSemTextoUtil;

    @Column(name = "total_acionaveis", nullable = false)
    private int totalAcionaveis;

    @Column(name = "total_pendencias_existentes", nullable = false)
    private int totalPendenciasExistentes;

    @Column(name = "total_ignoradas", nullable = false)
    private int totalIgnoradas;

    @Column(name = "painel_cenarios_json", columnDefinition = "TEXT")
    private String painelCenariosJson;

    @Column(name = "relatorio_html", length = 500)
    private String relatorioHtml;

    @Column(name = "relatorio_json", length = 500)
    private String relatorioJson;

    @PrePersist
    @PreUpdate
    @PostLoad
    void normalizarPlataforma() {
        plataforma = PlataformaFreela.normalizar(plataforma);
    }
}
