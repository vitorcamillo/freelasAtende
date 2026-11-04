package com.freelasatende.service.impl;

import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.dto.QualidadeRascunhoDTO;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusProjeto;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.policy.OperationalSafetyPolicy;
import com.freelasatende.repository.ProjetoRepository;
import com.freelasatende.service.interfaceServico.HistoricoService;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import com.freelasatende.service.interfaceServico.MensagemClienteGuardService;
import com.freelasatende.service.interfaceServico.QualidadeRascunhoService;
import com.freelasatende.service.interfaceServico.TelegramService;
import com.freelasatende.util.IaSafetyPolicy;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

import static com.freelasatende.service.impl.AutoAtendimentoTextHelper.previewLogEnvio;

@Slf4j
class AutoAtendimentoActionHooks implements AutoAtendimentoActionExecutor.Hooks {

    private final MarketplaceAdapterRegistry marketplaceAdapterRegistry;
    private final TelegramService telegramService;
    private final HistoricoService historicoService;
    private final ProjetoRepository projetoRepository;
    private final IaSafetyPolicy iaSafetyPolicy;
    private final OperationalSafetyPolicy operationalSafetyPolicy;
    private final MensagemClienteGuardService mensagemClienteGuardService;
    private final QualidadeRascunhoService qualidadeRascunhoService;
    private final TelegramMessageFormatter telegramMessageFormatter;
    private final boolean realWriteEnabled;
    private final boolean realWriteSimulateEnabled;

    AutoAtendimentoActionHooks(
        MarketplaceAdapterRegistry marketplaceAdapterRegistry,
        TelegramService telegramService,
        HistoricoService historicoService,
        ProjetoRepository projetoRepository,
        IaSafetyPolicy iaSafetyPolicy,
        OperationalSafetyPolicy operationalSafetyPolicy,
        MensagemClienteGuardService mensagemClienteGuardService,
        QualidadeRascunhoService qualidadeRascunhoService,
        TelegramMessageFormatter telegramMessageFormatter,
        boolean realWriteEnabled,
        boolean realWriteSimulateEnabled
    ) {
        this.marketplaceAdapterRegistry = marketplaceAdapterRegistry;
        this.telegramService = telegramService;
        this.historicoService = historicoService;
        this.projetoRepository = projetoRepository;
        this.iaSafetyPolicy = iaSafetyPolicy;
        this.operationalSafetyPolicy = operationalSafetyPolicy;
        this.mensagemClienteGuardService = mensagemClienteGuardService;
        this.qualidadeRascunhoService = qualidadeRascunhoService;
        this.telegramMessageFormatter = telegramMessageFormatter;
        this.realWriteEnabled = realWriteEnabled;
        this.realWriteSimulateEnabled = realWriteSimulateEnabled;
    }

    @Override
    public void validarConteudoSeguroParaEnvio(
        PlataformaFreela plataforma,
        Long projetoId,
        Long conversaId,
        TipoAcaoPendente tipo,
        String mensagem,
        String contexto
    ) {
        if (mensagem == null || mensagem.isBlank()) {
            throw new IllegalStateException("Conteúdo inválido para envio: " + contexto + " vazio");
        }
        if (iaSafetyPolicy.containsUnsafeContent(mensagem)) {
            throw new IllegalStateException("Conteúdo bloqueado pela política de segurança: " + contexto);
        }

        MensagemClienteGuardService.ContextoMensagemCliente contextoMensagem =
            new MensagemClienteGuardService.ContextoMensagemCliente(plataforma, projetoId, conversaId, tipo, true);
        MensagemClienteGuardService.ValidacaoRascunho validacao =
            mensagemClienteGuardService.validarRascunho(contextoMensagem, mensagem);
        if (!validacao.permitido()) {
            throw new IllegalStateException("Conteúdo bloqueado pelo guardião de saída: " + validacao.motivo());
        }
        QualidadeRascunhoDTO qualidade = qualidadeRascunhoService.avaliar(
            mensagem,
            validacao.resultado().name(),
            validacao.motivo()
        );
        if (qualidade.bloqueante()) {
            throw new IllegalStateException("Conteúdo bloqueado pela qualidade operacional: " + qualidade.getComentario());
        }
    }

    @Override
    public void validarRespostaConversaAindaAtual(RespostaPayload payload) {
        if (payload.getConversaId() == null) {
            throw new IllegalStateException("Resposta de conversa sem idConversa");
        }
        MarketplaceAdapter adapter = marketplaceAdapterRegistry.para(payload.getPlataforma());
        List<MensagemConversa> mensagens = adapter.listarMensagensConversa(payload.getConversaId());
        MensagemConversa ultimaVisivel = ultimaMensagemVisivel(mensagens);
        if (ultimaVisivel == null || ultimaVisivel.getId() == null) {
            throw new IllegalStateException("Nao consegui confirmar a ultima mensagem da conversa antes de enviar");
        }
        if (Boolean.TRUE.equals(ultimaVisivel.getEnviadaPorMim())) {
            throw new IllegalStateException("Resposta desatualizada: voce ja respondeu nessa conversa depois da mensagem do cliente");
        }
        if (!mensagemClienteAcionavel(ultimaVisivel)) {
            throw new IllegalStateException("Resposta desatualizada: ultima mensagem da conversa nao e acionavel");
        }
        if (payload.getMensagemGatilhoId() == null || !payload.getMensagemGatilhoId().equals(ultimaVisivel.getId())) {
            throw new IllegalStateException("Resposta desatualizada: existe mensagem mais recente do cliente; sincronize antes de responder");
        }
    }

    @Override
    public boolean deveSimularEnvioReal() {
        return operationalSafetyPolicy.shouldSimulateClientWrite(realWriteEnabled, realWriteSimulateEnabled);
    }

    @Override
    public void registrarEnvioSimuladoCliente(
        AcaoPendente acao,
        PlataformaFreela plataforma,
        Long projetoId,
        Long conversaId,
        TipoAcaoPendente tipo,
        String mensagem,
        String tituloProjeto
    ) {
        MensagemClienteGuardService.ContextoMensagemCliente contexto =
            new MensagemClienteGuardService.ContextoMensagemCliente(plataforma, projetoId, conversaId, tipo, true);
        mensagemClienteGuardService.descartarRascunhosPendentes(contexto);
        mensagemClienteGuardService.registrarEnvioSimulado(contexto, mensagem);
        registrarInteracao(
            plataforma,
            projetoId,
            tituloProjeto,
            TipoInteracao.ENVIO_SIMULADO,
            operationalSafetyPolicy.simulatedClientSendLogMessage(),
            acao.getModoOrigem()
        );
        log.info(
            "ENVIO_SIMULADO_CLIENTE actionId={} tipo={} projetoId={} conversaId={} modo={} preview=\"{}\"",
            acao.getId(),
            tipo,
            projetoId,
            conversaId,
            acao.getModoOrigem(),
            previewLogEnvio(mensagem)
        );
    }

    @Override
    public void marcarProjetoComoAprovado(
        PlataformaFreela plataforma,
        Long projetoId,
        String tituloProjeto,
        ModoSistema modo
    ) {
        if (projetoId == null) {
            return;
        }
        try {
            Projeto projeto = projetoRepository.findById(projetoId)
                .orElseGet(() -> Projeto.builder()
                    .plataforma(plataforma)
                    .id(projetoId)
                    .idExterno(PlataformaProjetoHelper.plataformaOuPadrao(plataforma).idExterno(projetoId))
                    .titulo(tituloProjeto != null ? tituloProjeto : "Projeto")
                    .build());
            projeto.setStatus(StatusProjeto.APROVADO);
            projeto.setFoiProcessado(true);
            projeto.setDataProcessamento(LocalDateTime.now());
            projetoRepository.save(projeto);
            registrarInteracao(projeto, TipoInteracao.PROJETO_APROVADO,
                "Projeto marcado como aprovado após acao executada", modo);
        } catch (Exception e) {
            log.warn("Nao foi possivel marcar projeto {} como aprovado: {}", projetoId, e.getMessage());
        }
    }

    @Override
    public void registrarInteracao(Projeto projeto, TipoInteracao tipo, String conteudo, ModoSistema modo) {
        registrarInteracao(PlataformaProjetoHelper.plataformaOuPadrao(projeto), projeto.getId(), projeto.getTitulo(), tipo, conteudo, modo);
    }

    @Override
    public void registrarInteracao(
        PlataformaFreela plataforma,
        Long projetoId,
        String tituloProjeto,
        TipoInteracao tipo,
        String conteudo,
        ModoSistema modo
    ) {
        historicoService.registrarInteracao(Interacao.builder()
            .plataforma(PlataformaProjetoHelper.plataformaOuPadrao(plataforma))
            .projetoId(projetoId)
            .projetoTitulo(tituloProjeto)
            .tipo(tipo)
            .conteudo(conteudo)
            .modoUsado(modo)
            .build());
    }

    @Override
    public void registrarEnvioCliente(
        PlataformaFreela plataforma,
        Long projetoId,
        Long conversaId,
        TipoAcaoPendente tipo,
        String mensagem
    ) {
        MensagemClienteGuardService.ContextoMensagemCliente contexto =
            new MensagemClienteGuardService.ContextoMensagemCliente(plataforma, projetoId, conversaId, tipo, true);
        mensagemClienteGuardService.descartarRascunhosPendentes(contexto);
        mensagemClienteGuardService.registrarEnvio(contexto, mensagem);
    }

    private MensagemConversa ultimaMensagemVisivel(List<MensagemConversa> mensagens) {
        return mensagens.stream()
            .filter(this::mensagemVisivelConversa)
            .max(Comparator.comparing(msg -> msg.getDataCriacaoMillis() != null ? msg.getDataCriacaoMillis() : 0L))
            .orElse(null);
    }

    private boolean mensagemClienteAcionavel(MensagemConversa mensagem) {
        if (mensagem == null) {
            return false;
        }
        if (Boolean.TRUE.equals(mensagem.getEnviadaPorMim())
            || Boolean.TRUE.equals(mensagem.getExcluida())
            || Boolean.TRUE.equals(mensagem.getSistema())) {
            return false;
        }
        return mensagem.getTexto() != null && !mensagem.getTexto().isBlank();
    }

    private boolean mensagemVisivelConversa(MensagemConversa mensagem) {
        if (mensagem == null) {
            return false;
        }
        if (Boolean.TRUE.equals(mensagem.getExcluida()) || Boolean.TRUE.equals(mensagem.getSistema())) {
            return false;
        }
        return mensagem.getTexto() != null && !mensagem.getTexto().isBlank();
    }

    @Override
    public void confirmarDonoAntesDoEnvio(Long projetoId) {
        LocalDateTime token = ReservaDeProjeto.tokenAtual(projetoId);
        if (token == null) {
            return;
        }
        LocalDateTime novo = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        // Renova a reserva por mais um prazo cheio: o envio a seguir leva segundos, entao
        // nenhuma outra instancia consegue toma-la antes dele.
        // Zero linhas = sem posse valida (tomada por outra instancia ou projeto removido).
        if (projetoRepository.renovarReivindicacao(projetoId, token, novo) == 0) {
            throw new ReivindicacaoPerdidaException(projetoId);
        }
        ReservaDeProjeto.atualizarToken(novo);
    }
}
