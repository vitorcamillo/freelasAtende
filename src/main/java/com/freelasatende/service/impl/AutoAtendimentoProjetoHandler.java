package com.freelasatende.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.NivelAutonomia;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusProjeto;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.policy.OperationalSafetyPolicy;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.ProjetoRepository;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import com.freelasatende.util.AtendimentoTextos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;

import static com.freelasatende.service.impl.AutoAtendimentoTextHelper.descreverOrigemValor;
import static com.freelasatende.service.impl.AutoAtendimentoTextHelper.formatarContextoProjeto;
import static com.freelasatende.service.impl.AutoAtendimentoTextHelper.formatarMoeda;
import static com.freelasatende.service.impl.AutoAtendimentoTextHelper.primeiraDuvida;

@Slf4j
class AutoAtendimentoProjetoHandler {

    private final ObjectMapper objectMapper;
    private final AcaoPendenteRepository acaoPendenteRepository;
    private final ProjetoRepository projetoRepository;
    private final Hooks hooks;
    private final AutoAtendimentoProjetoAbordagemInicial abordagemInicial;
    private final OperationalSafetyPolicy operationalSafetyPolicy;
    private final boolean realWriteEnabled;
    private final boolean realWriteSimulateEnabled;

    AutoAtendimentoProjetoHandler(
        ObjectMapper objectMapper,
        AcaoPendenteRepository acaoPendenteRepository,
        ProjetoRepository projetoRepository,
        OperationalSafetyPolicy operationalSafetyPolicy,
        MarketplaceAdapterRegistry marketplaceAdapterRegistry,
        GerenciadorConexoesService gerenciadorConexoes,
        Hooks hooks,
        boolean realWriteEnabled,
        boolean realWriteSimulateEnabled
    ) {
        this.objectMapper = objectMapper;
        this.acaoPendenteRepository = acaoPendenteRepository;
        this.projetoRepository = projetoRepository;
        this.hooks = hooks;
        this.operationalSafetyPolicy = operationalSafetyPolicy;
        this.realWriteEnabled = realWriteEnabled;
        this.realWriteSimulateEnabled = realWriteSimulateEnabled;
        this.abordagemInicial = new AutoAtendimentoProjetoAbordagemInicial(
            acaoPendenteRepository,
            projetoRepository,
            operationalSafetyPolicy,
            marketplaceAdapterRegistry,
            gerenciadorConexoes,
            hooks,
            realWriteEnabled,
            realWriteSimulateEnabled
        );
    }

    void tratarProposta(Projeto projeto, Proposta proposta, ModoSistema modo) throws JsonProcessingException {
        boolean temMargem = hooks.temMargemParaGastarConexao(projeto);
        String origemValor = descreverOrigemValor(projeto, proposta);
        String resumo = "Aprovar envio de proposta em " + formatarMoeda(proposta.getValorProposto())
            + " | Origem: " + origemValor;
        if (!temMargem) {
            resumo += " | alerta: reserva minima de conexoes atingida";
        }

        AcaoPendente acao = hooks.criarOuRecuperarAcaoProjeto(
            projeto,
            TipoAcaoPendente.ENVIAR_PROPOSTA,
            resumo,
            objectMapper.writeValueAsString(PropostaPayload.builder()
                .plataforma(PlataformaProjetoHelper.plataformaOuPadrao(projeto))
                .projetoId(projeto.getId())
                .projetoIdExterno(PlataformaProjetoHelper.idExternoOuId(projeto))
                .tituloProjeto(projeto.getTitulo())
                .urlProjeto(projeto.getUrlProjeto())
                .valorProposto(proposta.getValorProposto())
                .valorMinimo(proposta.getValorMinimo())
                .mediaMercado(proposta.getMediaMercado())
                .origemValor(origemValor)
                .contextoProjeto(formatarContextoProjeto(projeto))
                .mensagem(proposta.getMensagem())
                .nivelAutonomia(NivelAutonomia.RASCUNHA)
                .entendimentoIa("Projeto dentro do escopo conhecido; IA preparou proposta, mas depende da sua aprovacao.")
                .informacoesConfirmadas(DecisaoContextoFormatter.informacoesConfirmadasProjeto(projeto, origemValor))
                .informacoesPendentes(DecisaoContextoFormatter.informacoesPendentesProjeto(projeto, List.of(), origemValor))
                .sabeFazer(List.of("montar proposta inicial", "explicar entendimento do escopo", "manter conversa dentro da plataforma"))
                .duvidasInternas(List.of("validar se o valor sugerido combina com sua estrategia para este cliente"))
                .perguntasParaCliente(List.of())
                .riscosDecisao(DecisaoContextoFormatter.riscosDecisaoProjeto(projeto, origemValor))
                .proximaAcaoSugerida("Aprovar proposta, ajustar valor/texto ou ensinar uma regra antes de enviar.")
                .perguntaParaUsuario("Pode seguir com este valor e este texto ou devo ajustar algo antes?")
                .regraAprendizadoSugerida("Quando escopo e valor estiverem claros, gerar proposta curta e manter pendente para aprovacao.")
                .build()),
            proposta.getMensagem(),
            modo
        );

        if (acao == null) {
            return;
        }

        if (operationalSafetyPolicy.clientWritePathEnabled(realWriteEnabled, realWriteSimulateEnabled)) {
            try {
                hooks.executarAcao(acao);
            } catch (RuntimeException | JsonProcessingException e) {
                if (e instanceof DataIntegrityViolationException && ViolacaoProjetoUnico.ehDuplicado(e)) {
                    // ACHADO-FA-CONC-02-1: outra execucao ja gravou este projeto;
                    // decidido no processarProjeto, sem marcar acao/interacao de erro.
                    throw (DataIntegrityViolationException) e;
                }
                if (ReivindicacaoPerdidaException.naCadeia(e)) {
                    // Outra instancia ficou com o projeto: nada foi enviado. Descarta so a acao
                    // desta execucao, sem marcar o projeto (o token e da vencedora).
                    if (acao.getId() != null) {
                        acaoPendenteRepository.descartarAcaoDeExecucaoSemPosse(acao.getId(),
                            "Descartada: reivindicacao do projeto perdida para outra execucao.", LocalDateTime.now());
                    }
                    throw (RuntimeException) e;
                }
                registrarFalhaPropostaSemRetentativa(projeto, acao, modo, e);
                return;
            }
            acao.setStatus(com.freelasatende.model.enums.StatusAcaoPendente.EXECUTADA);
            acao.setDataAtualizacao(LocalDateTime.now());
            acaoPendenteRepository.save(acao);
            projeto.setStatus(StatusProjeto.EM_ANALISE);
            projeto.setFoiProcessado(true);
            projeto.setDataProcessamento(LocalDateTime.now());
            projetoRepository.save(projeto);
            hooks.registrarInteracao(projeto, TipoInteracao.PROJETO_APROVADO,
                realWriteEnabled
                    ? "Candidatura inicial curta enviada automaticamente"
                    : "Candidatura inicial curta simulada automaticamente",
                modo);
            return;
        }

        projeto.setStatus(StatusProjeto.EM_ANALISE);
        if (!temMargem) {
            hooks.registrarInteracao(projeto, TipoInteracao.LIMITE_ATINGIDO,
                "Proposta pendente de aprovacao com alerta de reserva minima de conexoes", modo);
        } else {
            hooks.registrarInteracao(projeto, TipoInteracao.PROJETO_APROVADO,
                "Proposta gerada e pendente de aprovacao humana", modo);
        }
        hooks.notificarAcaoPendente(acao);
    }

    private void registrarFalhaPropostaSemRetentativa(
        Projeto projeto,
        AcaoPendente acao,
        ModoSistema modo,
        Exception erro
    ) {
        MarketplaceAdapterErrorType tipo = tipoMarketplaceNaCadeia(erro);
        acao.setStatus(com.freelasatende.model.enums.StatusAcaoPendente.ERRO);
        acao.setObservacaoUsuario(
            "Proposta automatica nao enviada: " + mensagemErro(erro)
                + ". Nao retentar automaticamente; revisar a acao antes de novo envio."
        );
        acao.setDataAtualizacao(LocalDateTime.now());
        acaoPendenteRepository.save(acao);

        projeto.setStatus(tipo == MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL
            ? StatusProjeto.IGNORADO
            : StatusProjeto.EM_ANALISE);
        projeto.setMotivoRecusa(tipo == MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL
            ? mensagemErro(erro)
            : null);
        projeto.setFoiProcessado(true);
        projeto.setDataProcessamento(LocalDateTime.now());
        projetoRepository.save(projeto);
        hooks.registrarInteracao(
            projeto,
            TipoInteracao.MENSAGEM_ERRO,
            "Proposta automatica nao enviada e bloqueada contra retentativa: " + mensagemErro(erro),
            modo
        );
        log.warn("Proposta automatica nao enviada; projeto={} plataforma={} tipo={} erro={}",
            projeto.getId(), PlataformaProjetoHelper.plataformaOuPadrao(projeto), tipo, mensagemErro(erro));
        log.debug("Stacktrace da falha de proposta automatica do projeto {}", projeto.getId(), erro);
    }

    private MarketplaceAdapterErrorType tipoMarketplaceNaCadeia(Throwable erro) {
        Throwable atual = erro;
        while (atual != null) {
            if (atual instanceof MarketplaceAdapterException marketplaceError) {
                return marketplaceError.getType();
            }
            atual = atual.getCause();
        }
        return MarketplaceAdapterErrorType.FALHA_DESCONHECIDA;
    }

    private String mensagemErro(Throwable erro) {
        Throwable atual = erro;
        Throwable raiz = erro;
        while (atual != null) {
            raiz = atual;
            atual = atual.getCause();
        }
        String mensagem = raiz != null ? raiz.getMessage() : null;
        return mensagem == null || mensagem.isBlank()
            ? (raiz != null ? raiz.getClass().getSimpleName() : "erro sem detalhe")
            : mensagem;
    }

    void criarAbordagemInicialProjeto(Projeto projeto, ModoSistema modo) throws JsonProcessingException {
        String mensagemAbordagem = AtendimentoTextos.abordagemInicial();
        PerguntaPayload payload = montarPayloadAbordagemInicial(projeto, mensagemAbordagem);
        String payloadJson = objectMapper.writeValueAsString(payload);

        if (abordagemInicial.tentarEnviar(projeto, payloadJson, mensagemAbordagem, modo)) {
            return;
        }

        // ACHADO-FA-CONC-02-1: se outra thread/execucao ja criou a pendencia
        // deste projeto, esta execucao termina sem efeito — sem nova interacao
        // de atendimento nem nova notificacao. (Rodando sob o monitor do
        // servico, a checagem e a criacao sao atomicas na JVM.)
        boolean pendenciaJaRegistrada = existeAcaoPendenteDoProjeto(projeto);
        AcaoPendente acao = hooks.criarOuRecuperarAcaoProjeto(
            projeto,
            TipoAcaoPendente.ENVIAR_PERGUNTA,
            "Pergunta inicial pendente | envio direto indisponivel",
            payloadJson,
            mensagemAbordagem,
            modo
        );
        if (acao == null || pendenciaJaRegistrada) {
            return;
        }

        projeto.setStatus(StatusProjeto.EM_ANALISE);
        projeto.setFoiProcessado(true);
        projeto.setDataProcessamento(LocalDateTime.now());
        projetoRepository.save(projeto);
        hooks.registrarInteracao(projeto, TipoInteracao.PROJETO_APROVADO,
            "Abordagem inicial aguardando aprovacao humana porque envio direto nao esta disponivel", modo);
        hooks.notificarAcaoPendente(acao);
    }

    private boolean existeAcaoPendenteDoProjeto(Projeto projeto) {
        return acaoPendenteRepository.findFirstByPlataformaAndProjetoIdAndStatusOrderByDataCriacaoDesc(
            PlataformaProjetoHelper.plataformaOuPadrao(projeto),
            projeto.getId(),
            com.freelasatende.model.enums.StatusAcaoPendente.PENDENTE
        ).isPresent();
    }

    void tratarDuvidas(Projeto projeto, List<String> duvidas, ModoSistema modo) throws JsonProcessingException {
        String pergunta = montarPerguntaParaCliente(duvidas);

        AcaoPendente acao = hooks.criarOuRecuperarAcaoProjeto(
            projeto,
            TipoAcaoPendente.ENVIAR_PERGUNTA,
            "Pergunta pendente | dúvida principal: " + primeiraDuvida(duvidas),
            objectMapper.writeValueAsString(PerguntaPayload.builder()
                .plataforma(PlataformaProjetoHelper.plataformaOuPadrao(projeto))
                .projetoId(projeto.getId())
                .projetoIdExterno(PlataformaProjetoHelper.idExternoOuId(projeto))
                .tituloProjeto(projeto.getTitulo())
                .mensagem(pergunta)
                .contextoProjeto(formatarContextoProjeto(projeto))
                .nivelAutonomia(NivelAutonomia.PERGUNTA_CLIENTE)
                .entendimentoIa("Projeto parece possivel, mas faltam dados antes de proposta segura.")
                .informacoesConfirmadas(DecisaoContextoFormatter.informacoesConfirmadasProjeto(projeto, null))
                .informacoesPendentes(DecisaoContextoFormatter.informacoesPendentesProjeto(projeto, duvidas, null))
                .sabeFazer(List.of("identificar dados faltantes", "montar pergunta curta ao cliente", "evitar promessa sem contexto"))
                .duvidasInternas(List.of())
                .perguntasParaCliente(duvidas)
                .riscosDecisao(List.of("proposta sem esses dados pode ficar generica ou errada"))
                .proximaAcaoSugerida("Enviar pergunta ao cliente se voce aprovar.")
                .perguntaParaUsuario("Essa pergunta resolve a duvida ou quer ajustar o que sera perguntado?")
                .regraAprendizadoSugerida("Quando faltar dado essencial, perguntar ao cliente antes de propor.")
                .build()),
            pergunta,
            modo
        );
        if (acao == null) {
            return;
        }
        projeto.setStatus(StatusProjeto.EM_ANALISE);
        hooks.registrarInteracao(projeto, TipoInteracao.DUVIDA_ENVIADA,
            "Dúvidas identificadas; aguardando aprovação para contato", modo);
        hooks.notificarAcaoPendente(acao);
    }

    void criarAcaoPendenteAprendizado(Projeto projeto, List<String> duvidas, Proposta proposta, ModoSistema modo) {
        try {
            AcaoPendente acao;
            if (duvidas != null && !duvidas.isEmpty()) {
                String pergunta = montarPerguntaParaCliente(duvidas);
                acao = hooks.criarOuRecuperarAcaoProjeto(
                    projeto,
                    TipoAcaoPendente.ENVIAR_PERGUNTA,
                    "ENSINAMENTO | dúvida principal: " + primeiraDuvida(duvidas),
                    objectMapper.writeValueAsString(PerguntaPayload.builder()
                        .plataforma(PlataformaProjetoHelper.plataformaOuPadrao(projeto))
                        .projetoId(projeto.getId())
                        .projetoIdExterno(PlataformaProjetoHelper.idExternoOuId(projeto))
                        .tituloProjeto(projeto.getTitulo())
                        .mensagem(pergunta)
                        .contextoProjeto(formatarContextoProjeto(projeto))
                        .duvidas(duvidas)
                        .nivelAutonomia(NivelAutonomia.PERGUNTA_CLIENTE)
                        .entendimentoIa("Modo ensinamento: IA encontrou duvidas que impedem proposta confiavel.")
                        .analiseIa("Li o projeto, identifiquei dados faltantes e ainda nao tenho seguranca para falar com o cliente sem sua orientacao.")
                        .opcoesIa(List.of(
                            "Voce pode me ensinar a regra para este caso e eu reescrevo a abordagem.",
                            "Voce pode confirmar que esta duvida deve ir ao cliente.",
                            "Voce pode recusar se este tipo de projeto nao deve entrar no fluxo."
                        ))
                        .atitudeSugerida("Conversar com voce antes de transformar a duvida em pergunta ao cliente.")
                        .conversaEnsinoObrigatoria(true)
                        .informacoesConfirmadas(DecisaoContextoFormatter.informacoesConfirmadasProjeto(projeto, null))
                        .informacoesPendentes(DecisaoContextoFormatter.informacoesPendentesProjeto(projeto, duvidas, null))
                        .sabeFazer(List.of("separar duvida essencial", "rascunhar pergunta ao cliente", "aguardar ensino humano"))
                        .duvidasInternas(List.of("confirmar com voce se esta e a pergunta certa para este tipo de projeto"))
                        .perguntasParaCliente(duvidas)
                        .riscosDecisao(List.of("seguir sem resposta pode gerar proposta fora do escopo"))
                        .proximaAcaoSugerida("Responder a IA primeiro; depois reescrever ou liberar pergunta ao cliente.")
                        .perguntaParaUsuario("Quer que eu pergunte isso ao cliente ou existe uma regra melhor para este caso?")
                        .regraAprendizadoSugerida("Em modo ensinamento, duvida essencial deve virar pergunta validada antes de envio.")
                        .build()),
                    pergunta,
                    modo
                );
            } else {
                String origemValor = descreverOrigemValor(projeto, proposta);
                String resumo = "🧠 [ENSINAMENTO] Proposta aguardando sua decisão:\n" +
                    "Valor: " + formatarMoeda(proposta != null ? proposta.getValorProposto() : null) + "\n" +
                    "Origem do valor: " + origemValor + "\n" +
                    "Mensagem: " + (proposta != null && proposta.getMensagem() != null ? proposta.getMensagem() : "Não informado pela plataforma");
                acao = hooks.criarOuRecuperarAcaoProjeto(
                    projeto,
                    TipoAcaoPendente.ENVIAR_PROPOSTA,
                    resumo,
                    objectMapper.writeValueAsString(PropostaPayload.builder()
                        .plataforma(PlataformaProjetoHelper.plataformaOuPadrao(projeto))
                        .projetoId(projeto.getId())
                        .projetoIdExterno(PlataformaProjetoHelper.idExternoOuId(projeto))
                        .tituloProjeto(projeto.getTitulo())
                        .urlProjeto(projeto.getUrlProjeto())
                        .valorProposto(proposta != null ? proposta.getValorProposto() : null)
                        .valorMinimo(proposta != null ? proposta.getValorMinimo() : null)
                        .mediaMercado(proposta != null ? proposta.getMediaMercado() : null)
                        .origemValor(origemValor)
                        .contextoProjeto(formatarContextoProjeto(projeto))
                        .mensagem(proposta != null ? proposta.getMensagem() : null)
                        .nivelAutonomia(NivelAutonomia.RASCUNHA)
                        .entendimentoIa("Modo ensinamento: IA acha que ja ha base para proposta, mas precisa da sua validacao.")
                        .analiseIa("Li o escopo e montei um caminho possivel, mas em modo ensinamento preciso confirmar com voce antes de qualquer contato.")
                        .opcoesIa(List.of(
                            "Voce pode aprovar o caminho depois de responder esta conversa de ensino.",
                            "Voce pode ajustar valor, escopo ou tom antes de qualquer envio.",
                            "Voce pode ensinar uma regra para eu reconhecer casos parecidos."
                        ))
                        .atitudeSugerida("Validar com voce se este tipo de projeto deve virar proposta ou pergunta antes.")
                        .conversaEnsinoObrigatoria(true)
                        .informacoesConfirmadas(DecisaoContextoFormatter.informacoesConfirmadasProjeto(projeto, origemValor))
                        .informacoesPendentes(DecisaoContextoFormatter.informacoesPendentesProjeto(projeto, List.of(), origemValor))
                        .sabeFazer(List.of("montar proposta inicial", "usar escopo e valor disponiveis", "manter envio travado"))
                        .duvidasInternas(List.of("confirmar se esta proposta combina com sua forma de trabalhar"))
                        .perguntasParaCliente(List.of())
                        .riscosDecisao(DecisaoContextoFormatter.riscosDecisaoProjeto(projeto, origemValor))
                        .proximaAcaoSugerida("Conversar comigo primeiro; depois aprovar, reescrever ou recusar.")
                        .perguntaParaUsuario("Esse e o tipo de projeto que posso tratar assim quando aparecer de novo?")
                        .regraAprendizadoSugerida("Se escopo e valor forem suficientes, gerar proposta e manter pendente ate ganhar confianca.")
                        .build()),
                    proposta != null ? proposta.getMensagem() : null,
                    modo
                );
            }
            if (acao == null) {
                return;
            }
            projeto.setStatus(StatusProjeto.EM_ANALISE);
            projeto.setFoiProcessado(true);
            projeto.setDataProcessamento(LocalDateTime.now());
            projetoRepository.save(projeto);
            hooks.registrarInteracao(projeto, TipoInteracao.PROJETO_APROVADO,
                "🧠 Modo ENSINAMENTO: aguardando sua decisão para envio", modo);
            hooks.notificarAcaoPendente(acao);
        } catch (Exception e) {
            log.error("Erro ao criar ação pendente para aprendizado: {}", e.getMessage());
        }
    }

    private PerguntaPayload montarPayloadAbordagemInicial(Projeto projeto, String mensagemAbordagem) {
        return PerguntaPayload.builder()
            .plataforma(PlataformaProjetoHelper.plataformaOuPadrao(projeto))
            .projetoId(projeto.getId())
            .projetoIdExterno(PlataformaProjetoHelper.idExternoOuId(projeto))
            .tituloProjeto(projeto.getTitulo())
            .urlProjeto(projeto.getUrlProjeto())
            .mensagem(mensagemAbordagem)
            .contextoProjeto(formatarContextoProjeto(projeto))
            .nivelAutonomia(NivelAutonomia.PERGUNTA_CLIENTE)
            .entendimentoIa("Projeto parece atendivel; primeira abordagem confirma se ainda esta aberto antes de proposta.")
            .informacoesConfirmadas(DecisaoContextoFormatter.informacoesConfirmadasProjeto(projeto, null))
            .informacoesPendentes(List.of("Aguardar resposta do cliente antes de montar escopo, plano ou proposta."))
            .sabeFazer(List.of("abrir conversa curta", "evitar proposta longa em projeto frio", "aguardar resposta antes de vender"))
            .duvidasInternas(List.of())
            .perguntasParaCliente(List.of(mensagemAbordagem))
            .riscosDecisao(List.of("proposta completa antes da resposta pode soar automatica ou chegar tarde demais"))
            .proximaAcaoSugerida("Enviar automaticamente a abordagem inicial curta.")
            .perguntaParaUsuario(null)
            .regraAprendizadoSugerida("Em projeto novo atendivel, abrir com '"
                + mensagemAbordagem + "' antes de proposta completa.")
            .build();
    }

    private String montarPerguntaParaCliente(List<String> duvidas) {
        return PerguntaClienteFormatter.montarPerguntaParaCliente(duvidas);
    }

    interface Hooks {
        AcaoPendente criarOuRecuperarAcaoProjeto(
            Projeto projeto,
            TipoAcaoPendente tipo,
            String resumo,
            String payloadJson,
            String textoCliente,
            ModoSistema modo
        );

        void registrarInteracao(Projeto projeto, TipoInteracao tipo, String conteudo, ModoSistema modo);

        void notificarAcaoPendente(AcaoPendente acao);

        boolean validarERegistrarRascunho(
            PlataformaFreela plataforma,
            Long projetoId,
            Long conversaId,
            TipoAcaoPendente tipo,
            String mensagem,
            ModoSistema modo,
            String tituloProjeto
        );

        void executarAcao(AcaoPendente acao) throws JsonProcessingException;

        boolean temMargemParaGastarConexao(Projeto projeto);
    }
}
