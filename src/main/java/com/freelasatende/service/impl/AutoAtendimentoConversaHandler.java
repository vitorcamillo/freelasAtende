package com.freelasatende.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.NivelAutonomia;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.StatusProjeto;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.policy.OperationalSafetyPolicy;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.repository.ProjetoRepository;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static com.freelasatende.service.impl.AutoAtendimentoTextHelper.anexarObservacao;

@Slf4j
class AutoAtendimentoConversaHandler {

    private static final int LIMITE_MENSAGENS_HISTORICO_CONVERSA = 40;
    private static final int LIMITE_CHARS_MENSAGEM_HISTORICO = 900;

    private final MarketplaceAdapterRegistry marketplaceAdapterRegistry;
    private final AvaliadorIaService avaliadorIaService;
    private final AcaoPendenteRepository acaoPendenteRepository;
    private final InteracaoRepository interacaoRepository;
    private final ConversaContextoProjetoResolver contextoResolver;
    private final ObjectMapper objectMapper;
    private final Hooks hooks;

    AutoAtendimentoConversaHandler(
        MarketplaceAdapterRegistry marketplaceAdapterRegistry,
        AvaliadorIaService avaliadorIaService,
        AcaoPendenteRepository acaoPendenteRepository,
        InteracaoRepository interacaoRepository,
        ProjetoRepository projetoRepository,
        ObjectMapper objectMapper,
        OperationalSafetyPolicy operationalSafetyPolicy,
        Hooks hooks,
        boolean realWriteEnabled,
        boolean realWriteSimulateEnabled
    ) {
        this.marketplaceAdapterRegistry = marketplaceAdapterRegistry;
        this.avaliadorIaService = avaliadorIaService;
        this.acaoPendenteRepository = acaoPendenteRepository;
        this.interacaoRepository = interacaoRepository;
        this.contextoResolver = new ConversaContextoProjetoResolver(projetoRepository, acaoPendenteRepository, objectMapper);
        this.objectMapper = objectMapper;
        this.hooks = hooks;
    }

    void processarConversa(ConversaResumo conversa, ModoSistema modo) throws JsonProcessingException {
        PlataformaFreela plataforma = plataformaConversa(conversa);
        // idProjeto da conversa pode vir ja namespaced (Workana); idExterno() e idempotente
        // para id nativo, entao o stub casa com o Projeto salvo pelo polling.
        Projeto projeto = PlataformaProjetoHelper.normalizarProjeto(Projeto.builder()
            .plataforma(plataforma)
            .id(conversa.getIdProjeto())
            .idExterno(plataforma.idExterno(conversa.getIdProjeto()))
            .titulo(conversa.getNomeProjeto())
            .status(StatusProjeto.EM_ANALISE)
            .build());
        conversa.setPlataforma(projeto.getPlataforma());
        conversa.setIdProjeto(projeto.getId());

        MarketplaceAdapter adapter = marketplaceAdapterRegistry.para(plataforma);
        List<MensagemConversa> mensagens = adapter.listarMensagensConversa(conversa.getIdConversa());
        // Antes de qualquer filtro: o aviso de liberacao de contato chega como
        // mensagem de sistema e seria descartado por mensagemClienteAcionavel.
        ContatoLiberadoNotificador.notificar(conversa, projeto, mensagens, modo, interacaoRepository, hooks);
        MensagemConversa ultimaVisivel = ultimaMensagemVisivel(mensagens);
        Optional<AcaoPendente> pendenteNaConversa = buscarRespostaPendenteConversa(plataforma, conversa.getIdConversa());

        if (ultimaVisivel == null || ultimaVisivel.getId() == null) {
            return;
        }
        if (Boolean.TRUE.equals(ultimaVisivel.getEnviadaPorMim())) {
            pendenteNaConversa.ifPresent(acao -> encerrarRespostaPendenteDesatualizada(
                acao,
                projeto,
                "Mensagem pendente removida: a conversa ja teve resposta minha depois da mensagem do cliente."
            ));
            return;
        }
        if (!mensagemClienteAcionavel(ultimaVisivel)) {
            return;
        }

        MensagemConversa ultimaRecebida = ultimaVisivel;
        String chaveMensagem = "plataforma:" + plataforma.key()
            + "|conversa:" + conversa.getIdConversa()
            + "|mensagem:" + ultimaRecebida.getId();
        if (interacaoRepository.existsByTipoAndConteudoContaining(TipoInteracao.MENSAGEM_RECEBIDA, chaveMensagem)) {
            pendenteNaConversa
                .filter(acao -> respostaPendenteAtualizadaParaMensagem(acao, ultimaRecebida))
                .filter(acao -> !acaoTemTelegramRegistrado(acao))
                .ifPresent(hooks::notificarAcaoPendente);
            return;
        }
        // Conversas NUNCA sao recusadas. Se o cliente respondeu, sempre montamos um
        // rascunho e enviamos para o operador decidir (Refazer/Robotico). O bloqueio
        // por escopo vale apenas para projetos novos, nunca para conversas existentes.

        if (pendenteNaConversa.isPresent()) {
            if (respostaPendenteAtualizadaParaMensagem(pendenteNaConversa.get(), ultimaRecebida)) {
                return;
            }
            encerrarRespostaPendenteDesatualizada(
                pendenteNaConversa.get(),
                projeto,
                "Mensagem pendente substituida: chegou mensagem mais recente do cliente antes da aprovacao."
            );
            hooks.registrarInteracao(plataforma, projeto.getId(), projeto.getTitulo(), TipoInteracao.REAVALIACAO_SOLICITADA,
                "Nova mensagem recebida substituiu resposta pendente antiga da conversa",
                modo);
        }

        String historico = AutoAtendimentoTextHelper.formatarHistoricoConversa(
            mensagens,
            LIMITE_MENSAGENS_HISTORICO_CONVERSA,
            LIMITE_CHARS_MENSAGEM_HISTORICO
        );
        // A IA precisa enxergar o pedido inteiro (descricao, orcamento, nivel) e o que
        // ja mandamos ao cliente (proposta/abordagem), nao so a fala gatilho.
        Projeto projetoCompleto = contextoResolver.resolverProjetoCompleto(projeto, adapter);
        String enviosAnteriores = contextoResolver.formatarEnviosAnteriores(plataforma, projeto.getId());
        boolean contextoProjetoLido = ConversaContextoProjetoResolver.temContextoProjeto(projetoCompleto);
        String contextoProjeto = contextoProjetoLido
            ? AutoAtendimentoTextHelper.formatarContextoProjeto(projetoCompleto)
            : null;
        String historicoParaIa = ConversaContextoProjetoResolver.montarHistoricoParaIa(historico, enviosAnteriores);
        String resposta;
        String avisoQualidade = null;
        try {
            resposta = avaliadorIaService.sugerirRespostaConversa(projetoCompleto, historicoParaIa, ultimaRecebida.getTexto());
        } catch (AvaliadorIaRespostaConversaHelper.RespostaConversaReprovadaException e) {
            resposta = e.getResposta();
            avisoQualidade = e.getMessage();
            log.warn(
                "[{}][INBOX] Conversa {} recebeu rascunho reprovado pela qualidade; criando card para decisao humana: {}",
                plataforma.key(),
                conversa.getIdConversa(),
                avisoQualidade
            );
        } catch (RuntimeException e) {
            // A IA nao pode descartar a mensagem do cliente em silencio: mesmo sem
            // rascunho automatico, o operador SEMPRE recebe a conversa para decidir
            // (Refazer/Robotico). A mensagem nao e marcada como processada, entao a
            // proxima rodada tenta gerar o rascunho de novo ate conseguir.
            String motivo = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            resposta = "";
            avisoQualidade = "IA ainda nao gerou rascunho automatico (" + motivo
                + "). Avalie a mensagem do cliente e use Refazer ou Robotico.";
            log.warn(
                "[{}][INBOX] Conversa {} sem rascunho da IA; enviando card para decisao humana e mantendo retentativa: {}",
                plataforma.key(),
                conversa.getIdConversa(),
                motivo
            );
        }
        boolean rascunhoIndisponivel = resposta == null || resposta.isBlank();
        RespostaPayload payload = criarRespostaPayload(
            conversa,
            mensagens,
            ultimaRecebida,
            resposta,
            historico,
            contextoProjeto,
            enviosAnteriores,
            modo == ModoSistema.ENSINAMENTO,
            avisoQualidade
        );

        String resumo = montarResumoConversa(modo, resposta, avisoQualidade, conversa);
        AcaoPendente acao = criarCardConversaSempre(
            conversa,
            resumo,
            objectMapper.writeValueAsString(payload),
            resposta,
            modo,
            avisoQualidade != null
        );
        if (acao != null) {
            // Evita reenvio repetido no Telegram: so notifica se o card ainda nao foi
            // enviado numa rodada anterior (caso a IA ainda esteja tentando o rascunho).
            if (!acaoTemTelegramRegistrado(acao)) {
                hooks.notificarAcaoPendente(acao);
            }
            // Sem rascunho ainda: nao marca como processada para reprocessar a conversa
            // na proxima rodada e tentar gerar a resposta novamente ate conseguir.
            if (!rascunhoIndisponivel) {
                registrarMensagemRecebidaProcessada(plataforma, projeto, chaveMensagem, ultimaRecebida, modo);
            }
        }
    }

    private String montarResumoConversa(
        ModoSistema modo,
        String resposta,
        String avisoQualidade,
        ConversaResumo conversa
    ) {
        if (avisoQualidade != null) {
            return "⚠️ Revisar qualidade antes de enviar: " + avisoQualidade;
        }
        if (modo == ModoSistema.ENSINAMENTO) {
            return "🧠 [ENSINAMENTO] Resposta aguardando sua decisão:\n" + resposta;
        }
        return "Responder cliente na conversa " + conversa.getIdConversa();
    }

    private AcaoPendente criarCardConversaSempre(
        ConversaResumo conversa,
        String resumo,
        String payloadJson,
        String resposta,
        ModoSistema modo,
        boolean revisaoHumanaObrigatoria
    ) {
        if (revisaoHumanaObrigatoria) {
            return hooks.criarOuRecuperarAcaoConversaRevisaoHumana(conversa, resumo, payloadJson, modo);
        }
        AcaoPendente acao = hooks.criarOuRecuperarAcaoConversa(conversa, resumo, payloadJson, resposta, modo);
        if (acao == null) {
            // Guardiao bloqueou o rascunho automatico: o registro BLOQUEADA e a
            // interacao MENSAGEM_ERRO ja foram gravados. Nao criamos card de revisao
            // humana — aprovacao humana nao vence bloqueio de seguranca (regra dura),
            // entao um card so geraria ruido e a tentativa de reescrever/ensinar.
            return null;
        }
        return acao;
    }

    private void registrarMensagemRecebidaProcessada(
        PlataformaFreela plataforma,
        Projeto projeto,
        String chaveMensagem,
        MensagemConversa ultimaRecebida,
        ModoSistema modo
    ) {
        hooks.registrarInteracao(plataforma, projeto.getId(), projeto.getTitulo(), TipoInteracao.MENSAGEM_RECEBIDA,
            chaveMensagem + " | " + ultimaRecebida.getTexto(), modo);
    }

    private Optional<AcaoPendente> buscarRespostaPendenteConversa(PlataformaFreela plataforma, Long conversaId) {
        return acaoPendenteRepository
            .findFirstByPlataformaAndConversaIdAndTipoAndStatusOrderByDataCriacaoDesc(
                plataforma,
                conversaId,
                TipoAcaoPendente.RESPONDER_CONVERSA,
                StatusAcaoPendente.PENDENTE
            )
            .map(Optional::of)
            .orElse(Optional.empty());
    }

    private MensagemConversa ultimaMensagemVisivel(List<MensagemConversa> mensagens) {
        return mensagens.stream()
            .filter(this::mensagemVisivelConversa)
            .max(Comparator.comparing(msg -> msg.getDataCriacaoMillis() != null ? msg.getDataCriacaoMillis() : 0L))
            .orElse(null);
    }

    private boolean respostaPendenteAtualizadaParaMensagem(AcaoPendente acao, MensagemConversa ultimaRecebida) {
        try {
            RespostaPayload payload = objectMapper.readValue(acao.getPayloadJson(), RespostaPayload.class);
            return payload.getMensagemGatilhoId() != null
                && payload.getMensagemGatilhoId().equals(ultimaRecebida.getId());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean acaoTemTelegramRegistrado(AcaoPendente acao) {
        Long telegramMessageId = AutoAtendimentoPayloadHelper.extrairTelegramMessageIdPayloadSeguro(
            objectMapper,
            acao != null ? acao.getPayloadJson() : null
        );
        return telegramMessageId != null && telegramMessageId > 0;
    }

    private void encerrarRespostaPendenteDesatualizada(AcaoPendente acao, Projeto projeto, String motivo) {
        acao.setStatus(StatusAcaoPendente.REJEITADA);
        acao.setObservacaoUsuario(anexarObservacao(acao.getObservacaoUsuario(), motivo));
        acao.setDataAtualizacao(LocalDateTime.now());
        acaoPendenteRepository.save(acao);
        hooks.apagarMensagemTelegramDaAcao(acao);
        hooks.descartarRascunhosPendentes(acao);
        hooks.registrarInteracao(
            acao.getPlataforma(),
            projeto.getId(),
            projeto.getTitulo(),
            TipoInteracao.REAVALIACAO_SOLICITADA,
            motivo,
            acao.getModoOrigem()
        );
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

    private RespostaPayload criarRespostaPayload(
        ConversaResumo conversa,
        List<MensagemConversa> mensagens,
        MensagemConversa ultimaRecebida,
        String resposta,
        String historico,
        String contextoProjeto,
        String enviosAnteriores,
        boolean ensino,
        String avisoQualidade
    ) {
        boolean autoriaConfiavel = conversa.getIdFreelancer() != null && conversa.getIdCliente() != null;
        PlataformaFreela plataforma = plataformaConversa(conversa);
        boolean contextoProjetoLido = contextoProjeto != null && !contextoProjeto.isBlank();
        boolean enviosLidos = enviosAnteriores != null && !enviosAnteriores.isBlank();
        String fontesLidas = ConversaContextoProjetoResolver.descreverFontesLidas(contextoProjetoLido, enviosLidos);
        List<String> riscos = DecisaoContextoFormatter.riscosDecisaoConversa(autoriaConfiavel);
        if (avisoQualidade != null && !avisoQualidade.isBlank()) {
            riscos = new ArrayList<>(riscos);
            riscos.add("guardiao_qualidade: " + avisoQualidade);
        }
        if (!contextoProjetoLido) {
            riscos = new ArrayList<>(riscos);
            riscos.add("contexto_projeto: descricao do projeto nao encontrada; resposta baseada so na conversa.");
        }
        List<String> sabeFazer = new ArrayList<>(List.of(
            "ler historico recente",
            "diferenciar mensagem do cliente e minha",
            "rascunhar resposta curta"
        ));
        if (contextoProjetoLido) {
            sabeFazer.add(1, "ler descricao, orcamento e nivel do projeto");
        }
        if (enviosLidos) {
            sabeFazer.add("considerar proposta/abordagem ja enviada");
        }
        return RespostaPayload.builder()
            .plataforma(plataforma)
            .conversaId(conversa.getIdConversa())
            .projetoId(conversa.getIdProjeto())
            .projetoIdExterno(plataforma.idExterno(conversa.getIdProjeto()))
            .tituloProjeto(conversa.getNomeProjeto())
            .mensagem(resposta)
            .historicoConversa(historico)
            .contextoProjeto(contextoProjeto)
            .enviosAnteriores(enviosAnteriores)
            .mensagemGatilhoId(ultimaRecebida.getId())
            .mensagemGatilhoTexto(ultimaRecebida.getTexto())
            .mensagemGatilhoAutor("CLIENTE")
            .mensagemGatilhoNome(ConversaPayloadFormatter.nomeMensagemOuFallback(ultimaRecebida, conversa.getNomeCliente()))
            .idCliente(conversa.getIdCliente())
            .nomeCliente(conversa.getNomeCliente())
            .idFreelancer(conversa.getIdFreelancer())
            .nomeFreelancer(conversa.getNomeFreelancer())
            .ultimasMensagensMinhas(ConversaPayloadFormatter.ultimasMensagensPorAutor(mensagens, true))
            .ultimasMensagensCliente(ConversaPayloadFormatter.ultimasMensagensPorAutor(mensagens, false))
            .mensagensIgnoradasResumo(ConversaPayloadFormatter.resumirMensagensIgnoradas(mensagens))
            .autoriaConfiavel(autoriaConfiavel)
            .motivoAutoria(autoriaConfiavel
                ? "Autoria definida por idFreelancer/idCliente da conversa."
                : "Mapeamento incompleto: faltou idFreelancer ou idCliente na conversa.")
            .nivelAutonomia(autoriaConfiavel ? NivelAutonomia.RASCUNHA : NivelAutonomia.PERGUNTA_DONO)
            .entendimentoIa(ensino
                ? "Cliente enviou nova mensagem acionavel; IA leu " + fontesLidas + ", mas precisa conversar com voce antes de responder."
                : "Cliente enviou nova mensagem acionavel; IA preparou resposta usando " + fontesLidas + ".")
            .analiseIa(ensino
                ? "Li " + fontesLidas + ", separei a fala gatilho e gerei uma resposta. Antes de qualquer envio ao cliente, preciso que voce confirme o caminho."
                : avisoQualidade != null && !avisoQualidade.isBlank()
                    ? "Li " + fontesLidas + " e gerei um rascunho, mas o guardiao de qualidade pediu revisao humana: " + avisoQualidade
                    : "Li " + fontesLidas + " e preparei uma resposta com base nisso.")
            .opcoesIa(ensino
                ? List.of(
                    "Voce pode confirmar que a resposta segue o contexto.",
                    "Voce pode me ensinar outra abordagem para este tipo de cliente.",
                    "Voce pode recusar se a conversa nao deve continuar."
                )
                : List.of("Revisar resposta", "Reescrever", "Aprovar envio"))
            .atitudeSugerida(ensino
                ? "Conversar com voce antes de responder ao cliente."
                : "Manter resposta pendente ate aprovacao.")
            .conversaEnsinoObrigatoria(ensino)
            .informacoesConfirmadas(DecisaoContextoFormatter.informacoesConfirmadasConversa(conversa, ultimaRecebida, autoriaConfiavel))
            .informacoesPendentes(DecisaoContextoFormatter.informacoesPendentesConversa(autoriaConfiavel))
            .sabeFazer(sabeFazer)
            .duvidasInternas(autoriaConfiavel
                ? ConversaPayloadFormatter.duvidasInternasConversa(ultimaRecebida)
                : List.of("confirmar autoria antes de qualquer envio"))
            .perguntasParaCliente(List.of())
            .riscosDecisao(riscos)
            .proximaAcaoSugerida(ensino
                ? "Responder a IA primeiro; depois reescrever ou liberar resposta ao cliente."
                : "Revisar resposta, ensinar ajuste ou aprovar envio.")
            .perguntaParaUsuario(ensino
                ? ConversaPayloadFormatter.perguntaEnsinoConversa(ultimaRecebida, conversa)
                : "Essa resposta considera bem o historico ou devo ajustar antes?")
            .regraAprendizadoSugerida("Quando cliente responder na inbox, usar historico e pedir validacao se houver risco de contexto.")
            .build();
    }


    private PlataformaFreela plataformaConversa(ConversaResumo conversa) {
        if (conversa == null) {
            throw new IllegalArgumentException("Conversa deve ser informada para resolver plataforma");
        }
        return PlataformaProjetoHelper.plataformaOuPadrao(conversa.getPlataforma());
    }

    interface Hooks {
        AcaoPendente criarOuRecuperarAcaoConversa(
            ConversaResumo conversa,
            String resumo,
            String payloadJson,
            String textoCliente,
            ModoSistema modo
        );

        AcaoPendente criarOuRecuperarAcaoConversaRevisaoHumana(
            ConversaResumo conversa,
            String resumo,
            String payloadJson,
            ModoSistema modo
        );

        void notificarAcaoPendente(AcaoPendente acao);

        /** Alerta direto no Telegram, sem card de aprovacao: e informacao, nao acao. */
        void alertarContatoLiberado(String mensagem);

        void apagarMensagemTelegramDaAcao(AcaoPendente acao);

        void descartarRascunhosPendentes(AcaoPendente acao);

        void executarAcao(AcaoPendente acao) throws JsonProcessingException;

        void registrarInteracao(
            PlataformaFreela plataforma,
            Long projetoId,
            String tituloProjeto,
            TipoInteracao tipo,
            String conteudo,
            ModoSistema modo
        );
    }
}
