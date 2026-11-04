package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.controller.DashboardController;
import com.freelasatende.model.dto.AcaoControleDTO;
import com.freelasatende.model.dto.ControleAcaoResultadoDTO;
import com.freelasatende.model.dto.ControleOverviewDTO;
import com.freelasatende.model.dto.EnsinoFocoDTO;
import com.freelasatende.model.dto.InboxAuditoriaDTO;
import com.freelasatende.model.dto.InboxConversaAuditoriaDTO;
import com.freelasatende.model.entity.InboxAuditoriaRegistro;
import com.freelasatende.model.entity.MensagemClienteRegistro;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.StatusMensagemCliente;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.ConfiguracaoRepository;
import com.freelasatende.repository.InboxAuditoriaRegistroRepository;
import com.freelasatende.repository.InboxConversaAuditoriaRegistroRepository;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.repository.MensagemClienteRegistroRepository;
import com.freelasatende.repository.ProjetoRepository;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import com.freelasatende.service.interfaceServico.AutoAtendimentoService;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.AprendizadoService;
import com.freelasatende.service.interfaceServico.ControleOperacionalService;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.service.interfaceServico.ModoEnsinamentoPolicyService;
import com.freelasatende.service.interfaceServico.QualidadeRascunhoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.freelasatende.service.impl.ControleOperacionalSummaryHelper.motivoOperacao;
import static com.freelasatende.service.impl.ControleOperacionalSummaryHelper.qualidadeOperacional;
@Service
@RequiredArgsConstructor
@Slf4j
public class ControleOperacionalServiceImpl implements ControleOperacionalService {

    private final AcaoPendenteRepository acaoPendenteRepository;
    private final ConfiguracaoRepository configuracaoRepository;
    private final MensagemClienteRegistroRepository mensagemClienteRegistroRepository;
    private final InboxAuditoriaRegistroRepository inboxAuditoriaRegistroRepository;
    private final InboxConversaAuditoriaRegistroRepository inboxConversaAuditoriaRegistroRepository;
    private final InteracaoRepository interacaoRepository;
    private final ProjetoRepository projetoRepository;
    private final AutoAtendimentoService autoAtendimentoService;
    private final AutenticacaoService autenticacaoService;
    private final GerenciadorConexoesService gerenciadorConexoesService;
    private final AvaliadorIaService avaliadorIaService;
    private final AprendizadoService aprendizadoService;
    private final MarketplaceGateway marketplaceGateway;
    private final QualidadeRascunhoService qualidadeRascunhoService;
    private final ModoEnsinamentoPolicyService modoEnsinamentoPolicyService;
    private final SystemHealthTracker healthTracker;
    private final LoginAssistidoAutoTrigger loginAssistidoAutoTrigger;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean sincronizacaoProjetosEmAndamento = new AtomicBoolean(false);
    @Value("${real.write.enabled:false}")
    private boolean realWriteEnabled;
    @Value("${real.write.simulate:false}")
    private boolean realWriteSimulateEnabled;
    @Value("${real.ia.enabled:false}")
    private boolean realIaEnabled;
    @Value("${telegram.simulate:false}")
    private boolean telegramSimulateEnabled;
    @Value("${telegram.bot-token:}")
    private String telegramBotToken;
    @Value("${telegram.chat-id:}")
    private String telegramChatId;

    @Value("${sistema.sessao-cache-segundos:30}")
    private long sessaoCacheSegundos;

    private volatile Boolean sessaoCacheValor = null;
    private volatile long sessaoCacheAteMillis = 0;
    private volatile InboxAuditoriaDTO ultimaAuditoriaInbox;

    private ControleAcaoMapper acaoMapper() {
        return new ControleAcaoMapper(
            objectMapper,
            mensagemClienteRegistroRepository,
            qualidadeRascunhoService,
            modoEnsinamentoPolicyService
        );
    }

    private ControleEnsinoFocoManager focoManager() {
        return new ControleEnsinoFocoManager(configuracaoRepository, acaoPendenteRepository, acaoMapper());
    }

    private ControleInboxAuditoriaManager auditoriaManager() {
        return new ControleInboxAuditoriaManager(
            objectMapper,
            acaoPendenteRepository,
            inboxAuditoriaRegistroRepository,
            inboxConversaAuditoriaRegistroRepository,
            marketplaceGateway,
            acaoMapper(),
            realWriteEnabled,
            realWriteSimulateEnabled,
            realIaEnabled,
            telegramSimulado(),
            iaApiKeyConfiguradaDireto(),
            motivoIa()
        );
    }

    @Override
    public ControleOverviewDTO obterOverview() {
        boolean sessaoValida = verificarSessao();
        if (!sessaoValida) {
            loginAssistidoAutoTrigger.dispararSeSessaoInvalida("dashboard-overview");
        }
        Map<String, Object> health = healthTracker.getHealthCompleto();
        int errosHoje = healthTracker.getTotalErrosHoje();
        List<AcaoControleDTO> pendencias = listarAcoesPendentes();
        List<MensagemClienteRegistro> bloqueios = mensagemClienteRegistroRepository
            .findTop20ByStatusOrderByDataDesc(StatusMensagemCliente.BLOQUEADA);
        List<MensagemClienteRegistro> envios = mensagemClienteRegistroRepository
            .findTop20ByStatusOrderByDataDesc(StatusMensagemCliente.ENVIADA);
        List<MensagemClienteRegistro> simulacoes = mensagemClienteRegistroRepository
            .findTop20ByStatusOrderByDataDesc(StatusMensagemCliente.SIMULADA);

        return ControleOverviewDTO.builder()
            .podeOperar(sessaoValida && errosHoje == 0 && (realWriteEnabled || realWriteSimulateEnabled))
            .motivoOperacao(motivoOperacao(
                sessaoValida,
                errosHoje,
                pendencias.size(),
                bloqueios.size(),
                realWriteEnabled,
                realWriteSimulateEnabled
            ))
            .escritaRealHabilitada(realWriteEnabled)
            .simulacaoEnvioHabilitada(realWriteSimulateEnabled)
            .sessaoValida(sessaoValida)
            .statusConexoes(statusConexoes())
            .health(health)
            .errosHoje(errosHoje)
            .totalPendencias(pendencias.size())
            .totalBloqueios(mensagemClienteRegistroRepository.countByStatus(StatusMensagemCliente.BLOQUEADA))
            .totalEnvios(mensagemClienteRegistroRepository.countByStatus(StatusMensagemCliente.ENVIADA))
            .totalEnviosSimulados(mensagemClienteRegistroRepository.countByStatus(StatusMensagemCliente.SIMULADA))
            .pendencias(pendencias)
            .bloqueiosRecentes(bloqueios)
            .enviosRecentes(envios)
            .simulacoesRecentes(simulacoes)
            .errosRecentes(healthTracker.getErrosRecentes(10))
            .aprendizado(aprendizado())
            .qualidadeOperacional(qualidadeOperacional(pendencias))
            .auditoriaInboxResumo(resumoAuditoriaInbox())
            .focoEnsino(obterFocoEnsino())
            .data(LocalDateTime.now())
            .build();
    }

    @Override
    public List<AcaoControleDTO> listarAcoesPendentes() {
        EnsinoFocoDTO foco = obterFocoEnsino();
        ControleAcaoMapper mapper = acaoMapper();
        return acaoPendenteRepository.findByStatusOrderByDataCriacaoDesc(StatusAcaoPendente.PENDENTE)
            .stream()
            .filter(ControleOperacionalPendenciaFilter::visivelAoOperador)
            .map(acao -> mapper.mapearAcao(acao, foco))
            .sorted((a, b) -> Boolean.compare(b.isFocoEnsinoAtual(), a.isFocoEnsinoAtual()))
            .toList();
    }

    @Override
    public Optional<AcaoControleDTO> buscarAcao(Long id) {
        EnsinoFocoDTO foco = obterFocoEnsino();
        ControleAcaoMapper mapper = acaoMapper();
        return acaoPendenteRepository.findById(id).map(acao -> mapper.mapearAcao(acao, foco));
    }

    @Override
    public ControleAcaoResultadoDTO aprovarAcao(Long id, String comentario) {
        String mensagem = autoAtendimentoService.processarFeedback(id, true, comentario);
        return resultado(id, mensagem);
    }

    @Override
    public ControleAcaoResultadoDTO recusarAcao(Long id, String comentario) {
        String mensagem = autoAtendimentoService.processarFeedback(id, false, comentario);
        return resultado(id, mensagem);
    }

    @Override
    public ControleAcaoResultadoDTO reescreverAcao(Long id, String novoTexto, String comentario) {
        String mensagem = autoAtendimentoService.reescreverAcao(id, novoTexto, comentario);
        return resultado(id, mensagem);
    }

    @Override
    public ControleAcaoResultadoDTO registrarContextoEnsino(Long id, String orientacaoUsuario) {
        String mensagem = autoAtendimentoService.registrarContextoEnsino(id, orientacaoUsuario);
        return resultado(id, mensagem);
    }

    @Override
    public ControleAcaoResultadoDTO responderDuvidaIa(Long id, String respostaUsuario, String regraConfirmada) {
        String mensagem = autoAtendimentoService.responderDuvidaIa(id, respostaUsuario, regraConfirmada);
        return resultado(id, mensagem);
    }

    @Override
    public ControleAcaoResultadoDTO sincronizarInboxParaEnsino() {
        if (realIaEnabled && !iaApiKeyConfigurada()) {
            ultimaAuditoriaInbox = registrarAuditoriaInbox(construirAuditoriaInbox());
            return ControleAcaoResultadoDTO.builder()
                .sucesso(false)
                .mensagem("IA real habilitada por real.ia.enabled=true, mas IA_API_KEY nao esta configurada. Inbox foi auditada em leitura segura; nenhuma acao foi criada.")
                .acao(null)
                .build();
        }
        long antes = acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE);
        autoAtendimentoService.sincronizarInbox(ModoSistema.ENSINAMENTO, 20);
        long depois = acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE);
        long criadas = Math.max(0, depois - antes);
        ultimaAuditoriaInbox = registrarAuditoriaInbox(construirAuditoriaInbox());
        String mensagem = "Inbox sincronizada em modo ENSINAMENTO. Pendencias novas: "
            + criadas
            + ". Conversas auditadas: "
            + ultimaAuditoriaInbox.getTotalConversas()
            + ". Mensagens lidas: "
            + ultimaAuditoriaInbox.getTotalMensagens()
            + ". Nada foi enviado ao cliente.";
        return ControleAcaoResultadoDTO.builder()
            .sucesso(true)
            .mensagem(mensagem)
            .acao(null)
            .build();
    }

    @Override
    @Transactional
    public ControleAcaoResultadoDTO resetarRodadaOperacional() {
        long acoes = acaoPendenteRepository.count();
        long mensagens = mensagemClienteRegistroRepository.count();
        long auditorias = inboxAuditoriaRegistroRepository.count();
        long conversasAuditadas = inboxConversaAuditoriaRegistroRepository.count();
        long interacoes = interacaoRepository.count();
        long projetos = projetoRepository.count();

        acaoPendenteRepository.deleteAllInBatch();
        mensagemClienteRegistroRepository.deleteAllInBatch();
        inboxConversaAuditoriaRegistroRepository.deleteAllInBatch();
        inboxAuditoriaRegistroRepository.deleteAllInBatch();
        interacaoRepository.deleteAllInBatch();
        projetoRepository.deleteAllInBatch();
        ultimaAuditoriaInbox = null;
        limparFocoEnsino();

        String mensagem = "Rodada operacional resetada. Removidos: "
            + acoes + " pendencias, "
            + mensagens + " registros de mensagem, "
            + auditorias + " auditorias, "
            + conversasAuditadas + " conversas auditadas, "
            + interacoes + " interacoes e "
            + projetos + " projetos locais.";
        DashboardController.enviarLog("INFO", mensagem);
        log.info(mensagem);

        return ControleAcaoResultadoDTO.builder()
            .sucesso(true)
            .mensagem(mensagem)
            .totalPendencias(0L)
            .totalBloqueios(0L)
            .totalEnvios(0L)
            .totalEnviosSimulados(0L)
            .build();
    }

    @Override
    public InboxAuditoriaDTO auditarInboxEnsinoReadOnly() {
        ultimaAuditoriaInbox = registrarAuditoriaInbox(construirAuditoriaInbox());
        return ultimaAuditoriaInbox;
    }

    @Override
    public InboxAuditoriaDTO obterUltimaAuditoriaInbox() {
        if (ultimaAuditoriaInbox != null) {
            return ultimaAuditoriaInbox;
        }
        Optional<InboxAuditoriaRegistro> persistida = inboxAuditoriaRegistroRepository.findFirstByOrderByDataDesc();
        if (persistida.isPresent()) {
            ultimaAuditoriaInbox = mapearAuditoriaPersistida(persistida.get());
            return ultimaAuditoriaInbox;
        }
        return auditoriaVazia();
    }

    @Override
    public List<InboxConversaAuditoriaDTO> listarConversasUltimaAuditoriaInbox() {
        return obterUltimaAuditoriaInbox().getConversas();
    }

    private InboxAuditoriaDTO registrarAuditoriaInbox(InboxAuditoriaDTO auditoria) {
        return auditoriaManager().registrarAuditoriaInbox(auditoria);
    }

    private InboxAuditoriaDTO mapearAuditoriaPersistida(InboxAuditoriaRegistro registro) {
        return auditoriaManager().mapearAuditoriaPersistida(registro);
    }

    private InboxAuditoriaDTO auditoriaVazia() {
        return auditoriaManager().auditoriaVazia();
    }

    private InboxAuditoriaDTO construirAuditoriaInbox() {
        return auditoriaManager().construirAuditoriaInbox(obterFocoEnsino());
    }

    private Map<String, Object> resumoAuditoriaInbox() {
        return auditoriaManager().resumoAuditoriaInbox(obterUltimaAuditoriaInbox());
    }

    private boolean iaApiKeyConfigurada() {
        return iaApiKeyConfiguradaDireto();
    }

    private boolean iaApiKeyConfiguradaDireto() {
        Map<String, Object> diagnostico = avaliadorIaService.obterDiagnosticoMemoriaIa();
        if (diagnostico == null) {
            return false;
        }
        return Boolean.TRUE.equals(diagnostico.get("apiKeyConfigurada"));
    }

    private String motivoIa() {
        if (!realIaEnabled) {
            return "IA real nao esta exigida; fallback seguro permitido.";
        }
        return iaApiKeyConfiguradaDireto()
            ? "IA real habilitada e IA_API_KEY configurada."
            : "IA real habilitada, mas IA_API_KEY ausente.";
    }

    private boolean telegramSimulado() {
        return telegramSimulateEnabled
            || telegramBotToken == null
            || telegramBotToken.isBlank()
            || telegramChatId == null
            || telegramChatId.isBlank();
    }

    @Override
    public ControleAcaoResultadoDTO sincronizarProjetosParaEnsino() {
        if (!sincronizacaoProjetosEmAndamento.compareAndSet(false, true)) {
            long pendentes = acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE);
            return ControleAcaoResultadoDTO.builder()
                .sucesso(false)
                .mensagem("Sincronizacao de projetos ja esta em andamento. Aguarde finalizar antes de iniciar outra.")
                .totalPendencias(pendentes)
                .totalBloqueios(mensagemClienteRegistroRepository.countByStatus(StatusMensagemCliente.BLOQUEADA))
                .totalEnvios(mensagemClienteRegistroRepository.countByStatus(StatusMensagemCliente.ENVIADA))
                .totalEnviosSimulados(mensagemClienteRegistroRepository.countByStatus(StatusMensagemCliente.SIMULADA))
                .build();
        }

        long antes = acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE);
        try {
            List<Projeto> projetos = marketplaceGateway.buscarProjetosNovos();
            healthTracker.registrarSucesso("SCRAPER");
            int processados = 0;
            for (Projeto projeto : projetos.stream().limit(10).toList()) {
                autoAtendimentoService.processarProjeto(projeto, ModoSistema.ENSINAMENTO);
                healthTracker.registrarSucesso("IA");
                processados++;
                long atual = acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE);
                if (atual > antes) {
                    break;
                }
            }
            long depois = acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE);
            long criadas = Math.max(0, depois - antes);
            String mensagem = "Projetos sincronizados em modo ENSINAMENTO. Projetos lidos: "
                + projetos.size()
                + ", processados: "
                + processados
                + ", pendencias novas: "
                + criadas
                + ". Nada foi enviado ao cliente.";
            return ControleAcaoResultadoDTO.builder()
                .sucesso(true)
                .mensagem(mensagem)
                .acao(null)
                .totalProjetosLidos(projetos.size())
                .totalProjetosProcessados(processados)
                .totalPendenciasNovas(criadas)
                .totalPendencias(depois)
                .totalBloqueios(mensagemClienteRegistroRepository.countByStatus(StatusMensagemCliente.BLOQUEADA))
                .totalEnvios(mensagemClienteRegistroRepository.countByStatus(StatusMensagemCliente.ENVIADA))
                .totalEnviosSimulados(mensagemClienteRegistroRepository.countByStatus(StatusMensagemCliente.SIMULADA))
                .build();
        } catch (Exception e) {
            healthTracker.registrarErro("SCRAPER", "sincronizar-projetos", e.getMessage());
            log.error("Erro ao sincronizar projetos para ensino: {}", e.getMessage(), e);
            long pendentes = acaoPendenteRepository.countByStatus(StatusAcaoPendente.PENDENTE);
            return ControleAcaoResultadoDTO.builder()
                .sucesso(false)
                .mensagem("Falha ao sincronizar projetos em modo ENSINAMENTO: " + e.getMessage() + ". Nada foi enviado ao cliente.")
                .totalPendencias(pendentes)
                .totalBloqueios(mensagemClienteRegistroRepository.countByStatus(StatusMensagemCliente.BLOQUEADA))
                .totalEnvios(mensagemClienteRegistroRepository.countByStatus(StatusMensagemCliente.ENVIADA))
                .totalEnviosSimulados(mensagemClienteRegistroRepository.countByStatus(StatusMensagemCliente.SIMULADA))
                .build();
        } finally {
            sincronizacaoProjetosEmAndamento.set(false);
        }
    }

    @Override
    public ControleAcaoResultadoDTO reprocessarProjetoIgnorado(Long projetoId, ModoSistema modo) {
        String mensagem = autoAtendimentoService.reprocessarProjetoIgnorado(projetoId, modo);
        return resultado(null, mensagem);
    }

    @Override
    public List<MensagemClienteRegistro> listarBloqueiosRecentes() {
        return mensagemClienteRegistroRepository.findTop20ByStatusOrderByDataDesc(StatusMensagemCliente.BLOQUEADA);
    }

    @Override
    public EnsinoFocoDTO obterFocoEnsino() {
        return focoManager().obterFocoEnsino();
    }

    @Override
    public EnsinoFocoDTO definirFocoEnsino(Long acaoId) {
        return focoManager().definirFocoEnsino(acaoId);
    }

    @Override
    public EnsinoFocoDTO limparFocoEnsino() {
        return focoManager().limparFocoEnsino();
    }

    private ControleAcaoResultadoDTO resultado(Long id, String mensagem) {
        return ControleResultadoHelper.resultado(id, mensagem, this::buscarAcao);
    }

    private boolean verificarSessao() {
        long agora = System.currentTimeMillis();
        Boolean valorCache = sessaoCacheValor;
        if (valorCache != null && agora < sessaoCacheAteMillis) {
            return valorCache;
        }
        try {
            boolean valida = marketplaceGateway.verificarLoginPadrao();
            sessaoCacheValor = valida;
            sessaoCacheAteMillis = System.currentTimeMillis() + Math.max(5, sessaoCacheSegundos) * 1000;
            return valida;
        } catch (Exception e) {
            sessaoCacheValor = false;
            sessaoCacheAteMillis = System.currentTimeMillis() + Math.max(5, sessaoCacheSegundos) * 1000;
            return false;
        }
    }

    private String statusConexoes() {
        try {
            return gerenciadorConexoesService.getStatusConexoes();
        } catch (Exception e) {
            return "Indisponivel: " + e.getMessage();
        }
    }

    private Map<String, Object> aprendizado() {
        Map<String, Object> resumo = new LinkedHashMap<>();
        try {
            resumo.put("memoriaIa", avaliadorIaService.obterDiagnosticoMemoriaIa());
        } catch (Exception e) {
            resumo.put("memoriaIa", Map.of("erro", e.getMessage()));
        }
        try {
            resumo.put("operacional", aprendizadoService.obterResumoOperacional());
        } catch (Exception e) {
            resumo.put("operacional", Map.of("erro", e.getMessage()));
        }
        return resumo;
    }

}
