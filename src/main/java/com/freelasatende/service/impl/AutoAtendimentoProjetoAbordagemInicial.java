package com.freelasatende.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.StatusProjeto;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.policy.OperationalSafetyPolicy;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.ProjetoRepository;
import com.freelasatende.service.interfaceServico.GerenciadorConexoesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
class AutoAtendimentoProjetoAbordagemInicial {

    /** Recusa pelo limite diario local: recuperavel, o projeto volta a ser tentado depois do reset. */
    static final String OBSERVACAO_LIMITE_LOCAL =
        "Limite diario local de conexoes atingido; projeto volta a ser tentado depois da renovacao do contador.";


    private static final String RESUMO_ABORDAGEM_INICIAL_AUTOMATICA = "Abordagem inicial automatica | ";

    private final AcaoPendenteRepository acaoPendenteRepository;
    private final ProjetoRepository projetoRepository;
    private final OperationalSafetyPolicy operationalSafetyPolicy;
    private final MarketplaceAdapterRegistry marketplaceAdapterRegistry;
    private final GerenciadorConexoesService gerenciadorConexoes;
    private final AutoAtendimentoProjetoHandler.Hooks hooks;
    private final boolean realWriteEnabled;
    private final boolean realWriteSimulateEnabled;

    AutoAtendimentoProjetoAbordagemInicial(
        AcaoPendenteRepository acaoPendenteRepository,
        ProjetoRepository projetoRepository,
        OperationalSafetyPolicy operationalSafetyPolicy,
        MarketplaceAdapterRegistry marketplaceAdapterRegistry,
        GerenciadorConexoesService gerenciadorConexoes,
        AutoAtendimentoProjetoHandler.Hooks hooks,
        boolean realWriteEnabled,
        boolean realWriteSimulateEnabled
    ) {
        this.acaoPendenteRepository = acaoPendenteRepository;
        this.projetoRepository = projetoRepository;
        this.operationalSafetyPolicy = operationalSafetyPolicy;
        this.marketplaceAdapterRegistry = marketplaceAdapterRegistry;
        this.gerenciadorConexoes = gerenciadorConexoes;
        this.hooks = hooks;
        this.realWriteEnabled = realWriteEnabled;
        this.realWriteSimulateEnabled = realWriteSimulateEnabled;
    }

    boolean tentarEnviar(
        Projeto projeto,
        String payloadJson,
        String mensagemAbordagem,
        ModoSistema modo
    ) throws JsonProcessingException {
        boolean podeEnviarAutomatico = operationalSafetyPolicy.clientWritePathEnabled(realWriteEnabled, realWriteSimulateEnabled);
        if (!podeEnviarAutomatico) {
            return false;
        }

        PlataformaFreela plataforma = PlataformaProjetoHelper.plataformaOuPadrao(projeto);
        if (realWriteEnabled && consomeConexao(projeto) && !gerenciadorConexoes.temConexoesDisponiveis()) {
            if (!operationalSafetyPolicy.allowsAutomaticClientSend(modo, realWriteEnabled, realWriteSimulateEnabled)) {
                // Modo com aprovacao humana: sem conexao, a abordagem vira pendencia para o operador.
                log.info("Abordagem inicial do projeto {} adiada automaticamente por limite diario de conexoes",
                    projeto.getId());
                return false;
            }
            // Envio automatico liberado e outra execucao consumiu a ultima conexao durante a
            // avaliacao: mesmo adiamento da reserva recusada (projeto volta depois do reset).
            adiarPorLimiteLocal(projeto, AcaoPendente.builder()
                .plataforma(plataforma)
                .projetoId(projeto.getId())
                .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
                .modoOrigem(modo)
                .tituloProjeto(projeto.getTitulo())
                .resumo(RESUMO_ABORDAGEM_INICIAL_AUTOMATICA + mensagemAbordagem)
                .payloadJson(payloadJson)
                .dataCriacao(LocalDateTime.now())
                .build());
            return true;
        }

        List<AcaoPendente> pendentesAntigos = buscarAcoesPerguntaPorStatus(plataforma, projeto.getId(), StatusAcaoPendente.PENDENTE);
        for (AcaoPendente pendente : pendentesAntigos) {
            pendente.setStatus(StatusAcaoPendente.REJEITADA);
            pendente.setObservacaoUsuario("Mensagem pendente de abordagem inicial removida: primeira mensagem deve ser envio direto.");
            pendente.setDataAtualizacao(LocalDateTime.now());
            acaoPendenteRepository.save(pendente);
        }

        if (existeAbordagemInicialAutomaticaComErroPermanente(plataforma, projeto.getId())) {
            log.info("Abordagem inicial do projeto {} nao retentada: falha permanente anterior registrada", projeto.getId());
            projeto.setStatus(StatusProjeto.EM_ANALISE);
            projeto.setFoiProcessado(true);
            projeto.setDataProcessamento(LocalDateTime.now());
            projetoRepository.save(projeto);
            return true;
        }

        if (acaoPendenteRepository.countByPlataformaAndProjetoIdAndTipoAndStatusIn(
            plataforma,
            projeto.getId(),
            TipoAcaoPendente.ENVIAR_PERGUNTA,
            List.of(StatusAcaoPendente.APROVADA, StatusAcaoPendente.EXECUTADA)
        ) > 0) {
            log.info("Abordagem inicial do projeto {} nao reenviada: ja existe envio aprovado/executado", projeto.getId());
            projeto.setStatus(StatusProjeto.EM_ANALISE);
            projeto.setFoiProcessado(true);
            projeto.setDataProcessamento(LocalDateTime.now());
            projetoRepository.save(projeto);
            return true;
        }

        AcaoPendente acao = AcaoPendente.builder()
            .plataforma(plataforma)
            .projetoId(projeto.getId())
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .modoOrigem(modo)
            .tituloProjeto(projeto.getTitulo())
            .build();

        return executar(
            projeto,
            acao,
            payloadJson,
            mensagemAbordagem,
            modo,
            false
        );
    }

    private boolean executar(
        Projeto projeto,
        AcaoPendente acao,
        String payloadJson,
        String mensagemAbordagem,
        ModoSistema modo,
        boolean pendenciaExistente
    ) throws JsonProcessingException {
        LocalDateTime agora = LocalDateTime.now();
        if (acao.getDataCriacao() == null) {
            acao.setDataCriacao(agora);
        }
        acao.setPlataforma(PlataformaProjetoHelper.plataformaOuPadrao(projeto));
        acao.setProjetoId(projeto.getId());
        acao.setTipo(TipoAcaoPendente.ENVIAR_PERGUNTA);
        acao.setStatus(StatusAcaoPendente.APROVADA);
        acao.setModoOrigem(modo);
        acao.setTituloProjeto(projeto.getTitulo());
        acao.setResumo(RESUMO_ABORDAGEM_INICIAL_AUTOMATICA + mensagemAbordagem);
        acao.setPayloadJson(payloadJson);
        acao.setObservacaoUsuario(pendenciaExistente
            ? "Pendencia reaproveitada para envio automatico autorizado da abordagem inicial curta."
            : "Envio automatico autorizado para abordagem inicial curta.");
        acao.setDataAtualizacao(agora);
        acao = acaoPendenteRepository.save(acao);

        try {
            hooks.executarAcao(acao);
            acao.setStatus(StatusAcaoPendente.EXECUTADA);
            acao.setDataAtualizacao(LocalDateTime.now());
            acaoPendenteRepository.save(acao);

            projeto.setStatus(StatusProjeto.EM_ANALISE);
            projeto.setFoiProcessado(true);
            projeto.setDataProcessamento(LocalDateTime.now());
            projetoRepository.save(projeto);
            hooks.registrarInteracao(projeto, TipoInteracao.DUVIDA_ENVIADA,
                realWriteEnabled
                    ? (pendenciaExistente
                        ? "Abordagem inicial curta pendente enviada automaticamente"
                        : "Abordagem inicial curta enviada automaticamente")
                    : (pendenciaExistente
                        ? "Abordagem inicial curta pendente simulada automaticamente"
                        : "Abordagem inicial curta simulada automaticamente"),
                modo);
            return true;
        } catch (RuntimeException | JsonProcessingException e) {
            if (e instanceof DataIntegrityViolationException && ViolacaoProjetoUnico.ehDuplicado(e)) {
                // ACHADO-FA-CONC-02-1: outra execucao ja gravou este projeto;
                // decidido no processarProjeto, sem marcar acao/interacao de erro.
                throw (DataIntegrityViolationException) e;
            }
            if (LimiteDiarioLocalException.naCadeia(e)) {
                adiarPorLimiteLocal(projeto, acao);
                return true;
            }
            if (ReivindicacaoPerdidaException.naCadeia(e)) {
                // Outra instancia ficou com o projeto: nada foi enviado. A acao desta execucao
                // nao pode ficar como ERRO (bloquearia retentativas da abordagem).
                if (acao.getId() != null) {
                    acaoPendenteRepository.descartarAcaoDeExecucaoSemPosse(acao.getId(),
                        "Descartada: reivindicacao do projeto perdida para outra execucao.", LocalDateTime.now());
                }
                throw (RuntimeException) e;
            }
            if (falhaOperacionalAntesDoEnvio(e)) {
                registrarFalhaOperacionalSemPendencia(projeto, acao, modo, e);
                return true;
            }
            acao.setStatus(StatusAcaoPendente.ERRO);
            acao.setDataAtualizacao(LocalDateTime.now());
            acaoPendenteRepository.save(acao);
            hooks.registrarInteracao(projeto, TipoInteracao.ERRO_SISTEMA,
                "Falha ao enviar abordagem inicial automatica: " + e.getMessage(), modo);
            throw e;
        }
    }

    /**
     * Limite diario local: nada foi enviado. Registra a acao automatica em ERRO com a
     * observacao recuperavel; o projeto NAO e marcado como processado e volta a ser
     * tentado depois da renovacao do contador (ver AdiamentoPorLimiteLocal).
     */
    private void adiarPorLimiteLocal(Projeto projeto, AcaoPendente acao) {
        acao.setStatus(StatusAcaoPendente.ERRO);
        acao.setObservacaoUsuario(OBSERVACAO_LIMITE_LOCAL);
        acao.setDataAtualizacao(LocalDateTime.now());
        acaoPendenteRepository.save(acao);
        log.info("Abordagem inicial do projeto {} adiada: limite diario local de conexoes", projeto.getId());
    }

    private boolean falhaOperacionalAntesDoEnvio(Throwable e) {
        if (MarketplaceSessionFailure.isSessaoWorkanaInvalida(e)
            || MarketplaceSessionFailure.isSessao99FreelasInvalida(e)
            || MarketplaceSessionFailure.isFalhaTemporariaRede(e)) {
            return true;
        }
        Throwable atual = e;
        while (atual != null) {
            if (atual instanceof MarketplaceAdapterException marketplaceError
                && (marketplaceError.getType() == MarketplaceAdapterErrorType.FALHA_AUTENTICACAO
                    || marketplaceError.getType() == MarketplaceAdapterErrorType.FALHA_REDE
                    || marketplaceError.getType() == MarketplaceAdapterErrorType.LIMITE_CONEXOES
                    || marketplaceError.getType() == MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL
                    || marketplaceError.getType() == MarketplaceAdapterErrorType.CONVERSA_INACESSIVEL)) {
                return true;
            }
            atual = atual.getCause();
        }
        return false;
    }

    private void registrarFalhaOperacionalSemPendencia(
        Projeto projeto,
        AcaoPendente acao,
        ModoSistema modo,
        Throwable erro
    ) {
        acao.setStatus(StatusAcaoPendente.ERRO);
        acao.setObservacaoUsuario(observacaoFalhaOperacional(erro));
        acao.setDataAtualizacao(LocalDateTime.now());
        acaoPendenteRepository.save(acao);

        projeto.setStatus(StatusProjeto.EM_ANALISE);
        projeto.setFoiProcessado(true);
        projeto.setDataProcessamento(LocalDateTime.now());
        projetoRepository.save(projeto);

        Throwable raiz = causaRaiz(erro);
        String mensagemRaiz = mensagemErro(raiz);
        hooks.registrarInteracao(projeto, TipoInteracao.MENSAGEM_ERRO,
            "Abordagem inicial automatica nao enviada: falha operacional antes do envio ao cliente: " + mensagemRaiz, modo);
        log.warn(
            "Abordagem inicial automatica nao enviada antes do envio ao cliente: projeto={} plataforma={} acao={} erro={} causaRaiz={}: {}",
            projeto.getId(),
            PlataformaProjetoHelper.plataformaOuPadrao(projeto).key(),
            acao.getId(),
            erro.getClass().getSimpleName(),
            raiz.getClass().getSimpleName(),
            mensagemRaiz
        );
        log.debug("Stacktrace da falha operacional de abordagem inicial automatica do projeto {}", projeto.getId(), erro);
    }

    private String observacaoFalhaOperacional(Throwable erro) {
        if (tipoMarketplaceNaCadeia(erro) == MarketplaceAdapterErrorType.LIMITE_CONEXOES) {
            return "Envio automatico nao executado por limite semanal de conexoes do Workana. "
                + "Nao retentar automaticamente este projeto para nao gastar conexoes quando o limite renovar.";
        }
        return "Envio automatico nao executado por falha de sessao/autorizacao do marketplace. "
            + "Nao criar pendencia de aprovacao para abordagem inicial automatica.";
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

    private List<AcaoPendente> buscarAcoesPerguntaPorStatus(
        PlataformaFreela plataforma,
        Long projetoId,
        StatusAcaoPendente status
    ) {
        List<AcaoPendente> acoes = acaoPendenteRepository.findByPlataformaAndProjetoIdAndTipoAndStatusOrderByDataCriacaoDesc(
            plataforma,
            projetoId,
            TipoAcaoPendente.ENVIAR_PERGUNTA,
            status
        );
        return acoes != null ? acoes : List.of();
    }

    private boolean existeAbordagemInicialAutomaticaComErroPermanente(PlataformaFreela plataforma, Long projetoId) {
        return buscarAcoesPerguntaPorStatus(plataforma, projetoId, StatusAcaoPendente.ERRO).stream()
            .anyMatch(acao -> abordagemInicialAutomatica(acao) && !falhaRecuperavel(acao));
    }

    /**
     * Falha de sessao/autenticacao (ex.: Cloudflare no Freelancer.com.br) e
     * recuperavel: quando a sessao volta, o envio deve ser retentado. So
     * falhas permanentes (recusa do marketplace, projeto fora do ar etc.)
     * continuam travadas.
     */
    private boolean falhaRecuperavel(AcaoPendente acao) {
        String obs = acao.getObservacaoUsuario();
        return obs != null && (obs.contains("falha de sessao/autorizacao") || obs.equals(OBSERVACAO_LIMITE_LOCAL));
    }

    private boolean abordagemInicialAutomatica(AcaoPendente acao) {
        return acao != null
            && acao.getResumo() != null
            && acao.getResumo().startsWith(RESUMO_ABORDAGEM_INICIAL_AUTOMATICA);
    }

    private Throwable causaRaiz(Throwable erro) {
        Throwable atual = erro;
        Throwable raiz = erro;
        while (atual != null) {
            raiz = atual;
            atual = atual.getCause();
        }
        return raiz != null ? raiz : erro;
    }

    private String mensagemErro(Throwable erro) {
        if (erro == null) {
            return "erro sem detalhe";
        }
        String mensagem = erro.getMessage();
        if (mensagem == null || mensagem.isBlank()) {
            return erro.getClass().getSimpleName();
        }
        return mensagem;
    }

    private boolean consomeConexao(Projeto projeto) {
        return marketplaceAdapterRegistry.para(PlataformaProjetoHelper.plataformaOuPadrao(projeto))
            .capabilities()
            .conexoes();
    }
}
