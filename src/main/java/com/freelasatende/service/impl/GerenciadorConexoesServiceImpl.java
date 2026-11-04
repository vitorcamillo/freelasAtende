package com.freelasatende.service.impl;

import com.freelasatende.config.OperacaoDefaults;
import com.freelasatende.model.entity.Configuracao;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.repository.ConfiguracaoRepository;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Implementacao do gerenciador de conexoes e limites do 99Freelas.
 * Controla o uso diario de conexoes e renouvellement de sessao.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GerenciadorConexoesServiceImpl implements GerenciadorConexoesService {

    private final ConfiguracaoRepository configuracaoRepository;

    @Value("${sistema.limite-conexoes-diario:0}")
    private int limiteConexoesDiarioConfigurado;

    @Value("${sistema.hora-renovacao-sessao:-1}")
    private int horaRenovacaoSessaoConfigurada;

    @Override
    public boolean temConexoesDisponiveis() {
        verificarResetDiario();

        Configuracao config = obterConfiguracao();
        int usadas = usadasHoje(config);

        int limiteConexoesDiario = limiteConexoesDiario(config);
        boolean disponivel = usadas < limiteConexoesDiario;

        if (!disponivel) {
            log.warn("Limite de conexoes diarias atingido: {}/{}", usadas, limiteConexoesDiario);
        }

        return disponivel;
    }

    @Override
    public void registrarUsoConexao() {
        verificarResetDiario();
        Configuracao config = obterConfiguracao();

        int atual = config.getConexoesUsadasHoje() != null
            ? config.getConexoesUsadasHoje() : 0;

        // Incremento atomico no banco: concorrentes nao perdem incremento
        // (leitura-modificacao-escrita via save perdia escritas).
        int atualizacoes = configuracaoRepository.incrementarConexoesUsadasHoje(config.getId());
        if (atualizacoes == 0) {
            // Configuracao sumiu entre a leitura e o incremento (ex.: limpeza
            // concorrente): recria para o uso desta conexao nao ficar sem contagem.
            config = criarConfiguracaoDefault();
            configuracaoRepository.incrementarConexoesUsadasHoje(config.getId());
        }

        int limiteConexoesDiario = limiteConexoesDiario(config);
        log.debug("Conexao registrada: {}/{}", atual + 1, limiteConexoesDiario);
    }

    @Override
    public boolean reservarConexao() {
        verificarResetDiario();
        Configuracao config = obterConfiguracao();
        int limite = limiteConexoesDiario(config);
        LocalDateTime agora = LocalDateTime.now();
        int reservas = configuracaoRepository.reservarConexaoAtomica(
            config.getId(), limite, agora, agora.toLocalDate().atStartOfDay());
        if (reservas == 0 && configuracaoRepository.findById(config.getId()).isEmpty()) {
            // Configuracao sumiu entre a leitura e a reserva (limpeza concorrente):
            // recria com o dia zerado e tenta de novo — a recusa nao veio do limite.
            config = criarConfiguracaoDefault();
            limite = limiteConexoesDiario(config);
            reservas = configuracaoRepository.reservarConexaoAtomica(
                config.getId(), limite, agora, agora.toLocalDate().atStartOfDay());
        }
        if (reservas == 0) {
            // ACHADO-FA-CONC-01-2: update condicional devolveu 0 linhas —
            // limite diario alcancado; recusa de negocio sem enviar.
            log.warn("Limite de conexoes diarias atingido: {}/{}", usadasHoje(config), limite);
            return false;
        }
        return true;
    }

    @Override
    public void devolverConexao(LocalDateTime reservadaEm) {
        Configuracao config = obterConfiguracao();
        int devolucoes = configuracaoRepository.devolverConexao(config.getId(), reservadaEm);
        if (devolucoes == 0) {
            // Houve reset diario depois da reserva: ela nao pertence ao contador de hoje.
            log.debug("Conexao reservada em {} nao devolvida: contador ja foi zerado depois", reservadaEm);
            return;
        }
        log.debug("Conexao devolvida apos falha de envio");
    }

    @Override
    public boolean precisaRenovarSessao() {
        Configuracao config = obterConfiguracao();
        LocalDateTime ultimaRenovacao = config.getDataUltimaRenovacao();

        if (ultimaRenovacao == null) {
            return true;
        }

        LocalDateTime agora = LocalDateTime.now();
        LocalDateTime janelaRenovacao = LocalDateTime.of(
            agora.toLocalDate(),
            LocalTime.of(horaRenovacaoSessao(), 0)
        );
        if (agora.isBefore(janelaRenovacao)) {
            janelaRenovacao = janelaRenovacao.minusDays(1);
        }

        return ultimaRenovacao.isBefore(janelaRenovacao);
    }

    @Override
    public void marcarSessaoRenovada() {
        Configuracao config = obterConfiguracao();
        configuracaoRepository.marcarSessaoRenovada(config.getId(), LocalDateTime.now());

        log.info("Sessao marcada como renovada em {}", LocalDateTime.now());
    }

    @Override
    public void verificarResetDiario() {
        Configuracao config = obterConfiguracao();
        LocalDateTime ultimoReset = config.getDataUltimoReset();
        LocalDate hoje = LocalDate.now();

        if (ultimoReset == null || !hoje.equals(ultimoReset.toLocalDate())) {
            LocalDateTime agora = LocalDateTime.now();
            int resetados = configuracaoRepository.resetarContadorDiarioSeVencido(
                config.getId(), agora, hoje.atStartOfDay());
            // Sem setters na entidade: com Open EntityManager in View ela segue gerenciada e
            // um flush posterior regravaria o contador por cima das reservas atomicas.
            if (resetados > 0) {
                log.info("Reset diario de conexoes realizado em {}", agora);
            }
        }
    }

    @Override
    public String getStatusConexoes() {
        verificarResetDiario();
        Configuracao config = obterConfiguracao();
        int usadas = usadasHoje(config);
        int limiteConexoesDiario = limiteConexoesDiario(config);

        return String.format("Conexoes abertas: %d/%d | Limite diario: %d | Sessao renovavel: %s",
            usadas,
            limiteConexoesDiario,
            limiteConexoesDiario - usadas,
            precisaRenovarSessao() ? "SIM" : "NAO"
        );
    }

    /**
     * Contador lido do banco por projecao: os updates atomicos (reserva, reset, devolucao)
     * nao atualizam a entidade que o Open EntityManager in View mantem gerenciada.
     */
    private int usadasHoje(Configuracao config) {
        return configuracaoRepository.conexoesUsadasHojeAtual(config.getId()).orElse(0);
    }

    private Configuracao obterConfiguracao() {
        return configuracaoRepository.findFirstByOrderByIdAsc()
            .orElseGet(() -> criarConfiguracaoDefault());
    }

    private Configuracao criarConfiguracaoDefault() {
        Configuracao config = Configuracao.builder()
            .modoSistema(ModoSistema.REGULAR)
            .pollingIntervaloSegundos(30)
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

    private int horaRenovacaoSessao() {
        if (horaRenovacaoSessaoConfigurada >= 0 && horaRenovacaoSessaoConfigurada <= 23) {
            return horaRenovacaoSessaoConfigurada;
        }
        return OperacaoDefaults.HORA_RENOVACAO_SESSAO;
    }
}
