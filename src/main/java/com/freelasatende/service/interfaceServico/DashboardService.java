package com.freelasatende.service.interfaceServico;

import com.freelasatende.model.dto.DashboardStatsDTO;
import com.freelasatende.model.entity.Projeto;

import java.util.List;

/**
 * Interface para o servico de dashboard em tempo real.
 */
public interface DashboardService {

    /**
     * Obtem estatisticas gerais do sistema.
     */
    DashboardStatsDTO getStats();

    /**
     * Lista projetos recentes processados no dia.
     */
    List<Projeto> getProjetosRecentes(int limite);

    /**
     * Lista projetos aprovados no dia.
     */
    List<Projeto> getProjetosAprovados(int limite);

    /**
     * Lista projetos recusados no dia com motivos.
     */
    List<Projeto> getProjetosRecusados(int limite);

    /**
     * Obtem projetos processados no dia.
     */
    long countProjetosProcessadosHoje();

    /**
     * Obtem projetos aprovados no dia.
     */
    long countProjetosAprovadosHoje();

    /**
     * Obtem projetos recusados no dia.
     */
    long countProjetosRecusadosHoje();
}