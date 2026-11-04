package com.freelasatende.model.entity;

import com.freelasatende.model.enums.ModoSistema;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entidade para configuração do sistema.
 */
@Entity
@Table(name = "configuracoes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Configuracao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "modo_sistema")
    @Enumerated(EnumType.STRING)
    private ModoSistema modoSistema;

    @Column(name = "polling_intervalo_segundos")
    private Integer pollingIntervaloSegundos;

    @Column(name = "valor_limite_livre")
    private Double valorLimiteLivre;

    @Column(name = "categorias_interesse")
    private String categoriasInteresse;

    @Column(name = "niveis_interesse")
    private String niveisInteresse;

    @Column(name = "foi_ativado")
    private Boolean ativo;

    @Column(name = "data_ultima_renovacao")
    private LocalDateTime dataUltimaRenovacao;

    @Column(name = "limite_conexoes_diario")
    private Integer limiteConexoesDiario;

    @Column(name = "conexoes_usadas_hoje")
    private Integer conexoesUsadasHoje;

    @Column(name = "data_ultimo_reset")
    private LocalDateTime dataUltimoReset;

    @Column(name = "ensino_foco_ativo")
    private Boolean ensinoFocoAtivo;

    @Column(name = "ensino_foco_acao_id")
    private Long ensinoFocoAcaoId;

    @Column(name = "ensino_foco_projeto_id")
    private Long ensinoFocoProjetoId;

    @Column(name = "ensino_foco_conversa_id")
    private Long ensinoFocoConversaId;

    @Column(name = "ensino_foco_tipo")
    private String ensinoFocoTipo;

    @Column(name = "ensino_foco_titulo")
    private String ensinoFocoTitulo;

    @Column(name = "ensino_foco_texto_preview", length = 1200)
    private String ensinoFocoTextoPreview;

    @Column(name = "ensino_foco_data")
    private LocalDateTime ensinoFocoData;
}
