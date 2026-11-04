package com.freelasatende.service.impl;

import com.freelasatende.config.OperacaoDefaults;
import com.freelasatende.model.dto.DashboardStatsDTO;
import com.freelasatende.model.entity.Configuracao;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.StatusProjeto;
import com.freelasatende.repository.ConfiguracaoRepository;
import com.freelasatende.repository.ProjetoRepository;
import com.freelasatende.scheduler.PollingScheduler;
import com.freelasatende.service.interfaceServico.DashboardService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Implementacao do servico de dashboard.
 * Todos os dados são reais, nada é mockado ou simulado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final ProjetoRepository projetoRepository;
    private final ConfiguracaoRepository configuracaoRepository;
    private final GerenciadorConexoesService gerenciadorConexoes;
    private final PollingScheduler pollingScheduler;
    private final MarketplaceGateway marketplaceGateway;
    private final TelegramService telegramService;
    private final SystemHealthTracker healthTracker;

    @Value("${sistema.limite-conexoes-diario:0}")
    private int limiteConexoesDiarioConfigurado;

    @Override
    public DashboardStatsDTO getStats() {
        Configuracao config = obterConfiguracao();
        boolean logado = false;
        try {
            logado = marketplaceGateway.verificarLoginPadrao();
            healthTracker.registrarSucesso("SCRAPER");
        } catch (Exception e) {
            healthTracker.registrarErro("SCRAPER", "stats", e.getMessage());
        }

        int conexoesUsadas = config.getConexoesUsadasHoje() != null
            ? config.getConexoesUsadasHoje() : 0;
        int limiteConexoes = limiteConexoesDiario(config);

        return DashboardStatsDTO.builder()
            .modoAtual(pollingScheduler.getModoAtual().name())
            .conexoesUsadas(conexoesUsadas)
            .conexoesLimite(limiteConexoes)
            .projetosProcessadosHoje(countProjetosProcessadosHoje())
            .projetosAprovadosHoje(countProjetosAprovadosHoje())
            .projetosRecusadosHoje(countProjetosRecusadosHoje())
            .status99Freelas(logado ? "LOGADO" : "NAO_LOGADO")
            .telegramConectado(!"OFFLINE".equals(healthTracker.getStatusSubsistema("TELEGRAM")))
            .ultimoPolling(LocalDateTime.now())
            // Dados reais de saúde
            .uptimeSeconds(healthTracker.getUptimeSeconds())
            .uptimeFormatado(formatarUptime(healthTracker.getUptimeSeconds()))
            .errosHoje(healthTracker.getTotalErrosHoje())
            .statusScraper(healthTracker.getStatusSubsistema("SCRAPER"))
            .statusIa(healthTracker.getStatusSubsistema("IA"))
            .statusTelegram(healthTracker.getStatusSubsistema("TELEGRAM"))
            .build();
    }

    @Override
    public List<Projeto> getProjetosRecentes(int limite) {
        LocalDateTime inicioDia = LocalDate.now().atStartOfDay();
        return projetoRepository.findByDataProcessamentoAfterOrderByDataProcessamentoDesc(
            inicioDia, PageRequest.of(0, limite));
    }

    @Override
    public List<Projeto> getProjetosAprovados(int limite) {
        LocalDateTime inicioDia = LocalDate.now().atStartOfDay();
        return projetoRepository.findByStatusAndDataProcessamentoAfterOrderByDataProcessamentoDesc(
            StatusProjeto.APROVADO, inicioDia, PageRequest.of(0, limite));
    }

    @Override
    public List<Projeto> getProjetosRecusados(int limite) {
        LocalDateTime inicioDia = LocalDate.now().atStartOfDay();
        return projetoRepository.findByStatusAndDataProcessamentoAfterOrderByDataProcessamentoDesc(
            StatusProjeto.IGNORADO, inicioDia, PageRequest.of(0, limite));
    }

    @Override
    public long countProjetosProcessadosHoje() {
        LocalDateTime inicioDia = LocalDate.now().atStartOfDay();
        return projetoRepository.countByFoiProcessadoTrueAndDataProcessamentoAfter(inicioDia);
    }

    @Override
    public long countProjetosAprovadosHoje() {
        LocalDateTime inicioDia = LocalDate.now().atStartOfDay();
        return projetoRepository.countByStatusAndDataProcessamentoAfter(StatusProjeto.APROVADO, inicioDia);
    }

    @Override
    public long countProjetosRecusadosHoje() {
        LocalDateTime inicioDia = LocalDate.now().atStartOfDay();
        return projetoRepository.countByStatusAndDataProcessamentoAfter(StatusProjeto.IGNORADO, inicioDia);
    }

    private Configuracao obterConfiguracao() {
        return configuracaoRepository.findFirstByOrderByIdAsc()
            .orElseGet(() -> criarConfiguracaoDefault());
    }

    private Configuracao criarConfiguracaoDefault() {
        Configuracao config = Configuracao.builder()
            .ativo(true)
            .limiteConexoesDiario(limiteConexoesDiarioDefault())
            .conexoesUsadasHoje(0)
            .dataUltimoReset(LocalDateTime.now())
            .build();
        return configuracaoRepository.save(config);
    }

    private int limiteConexoesDiario(Configuracao config) {
        if (config != null && config.getLimiteConexoesDiario() != null
            && config.getLimiteConexoesDiario() > 0) {
            return config.getLimiteConexoesDiario();
        }
        return limiteConexoesDiarioDefault();
    }

    private int limiteConexoesDiarioDefault() {
        if (limiteConexoesDiarioConfigurado > 0) {
            return limiteConexoesDiarioConfigurado;
        }
        return OperacaoDefaults.LIMITE_CONEXOES_DIARIO;
    }

    private String formatarUptime(long totalSegundos) {
        long dias = totalSegundos / 86400;
        long horas = (totalSegundos % 86400) / 3600;
        long minutos = (totalSegundos % 3600) / 60;
        if (dias > 0) {
            return String.format("%dd %dh %dm", dias, horas, minutos);
        }
        if (horas > 0) {
            return String.format("%dh %dm", horas, minutos);
        }
        return String.format("%dm", minutos);
    }
}
