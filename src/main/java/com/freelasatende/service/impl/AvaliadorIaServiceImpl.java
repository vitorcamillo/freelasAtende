package com.freelasatende.service.impl;

import com.freelasatende.config.IaConfig;
import com.freelasatende.model.dto.AvaliacaoResultado;
import com.freelasatende.model.dto.QualidadeRascunhoDTO;
import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.HistoricoService;
import com.freelasatende.service.interfaceServico.IaProvider;
import com.freelasatende.service.interfaceServico.QualidadeRascunhoService;
import com.freelasatende.service.interfaceServico.UrlFetcherService;
import com.freelasatende.util.IaSafetyPolicy;
import com.freelasatende.util.RetryUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class AvaliadorIaServiceImpl implements AvaliadorIaService {

    private final IaConfig iaConfig;
    private final IaProvider iaProvider;
    private final ObjectMapper objectMapper;
    private final HistoricoService historicoService;
    private final IaSafetyPolicy iaSafetyPolicy;
    private final UrlFetcherService urlFetcherService;
    private final QualidadeRascunhoService qualidadeRascunhoService;
    private final ManualVozLoader manualVozLoader;

    private static final String ARQUIVO_CONHECIMENTO = "/data/conhecimentos.json";
    private static final int LIMITE_RESPOSTA_CONVERSA = 700;
    private static final int MAX_TENTATIVAS_REESCRITA_VALIDA = 3;

    @Value("${ia.memoria-arquivo:data/memoria-contexto.json}")
    private String arquivoMemoria;

    private final Map<Long, Boolean> cacheAvaliacoes = new ConcurrentHashMap<>();
    private final Object memoriaLock = new Object();
    private final AtomicBoolean otimizacaoMemoriaEmAndamento = new AtomicBoolean(false);

    @Override
    public boolean somosCapazesDeAtender(Projeto projeto) {
        return avaliarProjeto(projeto).isCapaz();
    }

    @Override
    public AvaliacaoResultado reavaliarProjeto(Projeto projeto) {
        if (projeto != null && projeto.getId() != null) {
            cacheAvaliacoes.remove(projeto.getId());
        }
        return avaliarProjeto(projeto);
    }

    @Override
    public AvaliacaoResultado avaliarProjeto(Projeto projeto) {
        if (projeto == null || projeto.getId() == null) {
            log.warn("Projeto nulo recebido para avaliação");
            return AvaliacaoResultado.builder().capaz(false).motivoRecusa("Projeto inválido").build();
        }

        try {
            if (cacheAvaliacoes.containsKey(projeto.getId())) {
                log.debug("Projeto {} encontrado no cache: {}", projeto.getId(),
                    cacheAvaliacoes.get(projeto.getId()));
                boolean cached = cacheAvaliacoes.get(projeto.getId());
                AvaliadorIaHistoricoHelper.emCache(historicoService, projeto, cached);
                return AvaliacaoResultado.builder().capaz(cached).build();
            }

            Optional<String> bloqueioEscopo = EscopoAtendimentoGuard.motivoBloqueio(projeto);
            if (bloqueioEscopo.isPresent()) {
                return AvaliadorIaHistoricoHelper.recusaLocal(historicoService, cacheAvaliacoes, projeto, bloqueioEscopo.get());
            }

            String contexto = carregarContexto(AvaliadorIaMemoriaHelper.ContextoUso.TRIAGEM);
            String contextoUrls = urlFetcherService.buscarContextoDeUrls(projeto.getDescricao());
            String referenciasTexto = contextoUrls.isBlank() ? "" : "\n\nReferências encontradas na descrição:" + contextoUrls;

            String prompt = AvaliadorIaPromptFactory.avaliarProjeto(
                projeto,
                carregarConhecimento(),
                contexto,
                referenciasTexto
            );

            String resposta = RetryUtils.comRetryChecked(
                () -> iaProvider.enviar(prompt, 1500),
                iaConfig.getMaxRetries(),
                "avaliar-projeto-" + projeto.getId()
            );

            if (resposta == null || resposta.isBlank()) {
                log.warn("IA retornou resposta vazia ao avaliar projeto {}", projeto.getId());
                return AvaliacaoResultado.builder().capaz(false).motivoRecusa("IA retornou resposta vazia").build();
            }

            // Resposta sem decisao utilizavel (SIM/NAO) nao pode virar recusa:
            // repete com o proximo modelo da cadeia de fallback. Medido em
            // producao: o modelo free as vezes responde analise livre sem o
            // veredito e o projeto era RECUSADO por "fora do formato" sem
            // consultar o fallback, perdendo a negociacao.
            String respostaOriginal = resposta;
            resposta = AvaliadorIaAvaliacaoHelper.respostaComFallbackDeFormato(resposta, prompt, iaProvider);
            if (!resposta.equals(respostaOriginal)) {
                log.info("Projeto {} avaliado por modelo alternativo da cadeia apos formato invalido", projeto.getId());
            }

            AvaliadorIaAvaliacaoHelper.AvaliacaoInterpretada interpretada =
                AvaliadorIaAvaliacaoHelper.interpretarRespostaAvaliacao(projeto, resposta);
            boolean capaz = interpretada.capaz();
            String motivo = interpretada.motivoRecusa();
            String motivoInteresse = interpretada.motivoInteresse();
            String respostaAuditada = resposta.trim();
            if (interpretada.observacao() != null) {
                respostaAuditada += " | Decisao local: " + (capaz ? "SIM" : "NAO")
                    + " - " + interpretada.observacao();
            }

            cacheAvaliacoes.put(projeto.getId(), capaz);

            String conteudoLog = "IA (" + iaProvider.getNome() + "/" + iaProvider.getModelo()
                + ") avaliou: " + resposta.trim();
            if (motivo != null) {
                conteudoLog += " | Motivo: " + motivo;
            }
            if (motivoInteresse != null) {
                conteudoLog += " | Motivo interesse: " + motivoInteresse;
            }
            if (interpretada.observacao() != null) {
                conteudoLog += " | Normalizacao: " + interpretada.observacao();
            }

            historicoService.registrarInteracao(
                Interacao.builder()
                    .projetoId(projeto.getId())
                    .projetoTitulo(projeto.getTitulo())
                    .tipo(capaz ? TipoInteracao.PROJETO_APROVADO : TipoInteracao.PROJETO_AVALIADO)
                    .conteudo(conteudoLog)
                    .data(LocalDateTime.now())
                    .build()
            );

            atualizarMemoria(projeto, capaz, respostaAuditada);

            if (capaz) {
                log.info("Projeto {} avaliado por {} ({}): APROVADO", projeto.getId(), iaProvider.getNome(), iaProvider.getModelo());
            } else {
                log.info("Projeto {} avaliado por {} ({}): RECUSADO - {}", projeto.getId(), iaProvider.getNome(), iaProvider.getModelo(), motivo != null ? motivo : "sem motivo especificado");
            }

            return AvaliacaoResultado.builder()
                .capaz(capaz)
                .motivoRecusa(motivo)
                .motivoInteresse(motivoInteresse)
                .build();

        } catch (IaProvider.IaProviderException e) {
            return AvaliadorIaHistoricoHelper.iaIndisponivel(historicoService, iaProvider, projeto, e);
        } catch (Exception e) {
            log.error("Erro ao avaliar projeto {}: {}", projeto.getId(), e.getMessage());
            throw new IllegalStateException(
                "Falha tecnica ao avaliar projeto " + projeto.getId() + ": " + e.getMessage(),
                e
            );
        }
    }

    @Override
    public List<String> gerarDuvidas(Projeto projeto) {
        try {
            if (deveUsarFallbackPorApiKeyAusente()) {
                throw new IllegalStateException("IA indisponivel: API key ausente para gerar duvidas.");
            }

            String contextoUrls = urlFetcherService.buscarContextoDeUrls(projeto.getDescricao());
            String referenciasTexto = contextoUrls.isBlank() ? "" : "\n\nReferências encontradas na descrição:" + contextoUrls;

            String prompt = AvaliadorIaPromptFactory.gerarDuvidas(projeto, referenciasTexto, carregarManualVoz());

            String resposta = RetryUtils.comRetryChecked(
                () -> iaProvider.enviar(prompt, 1500),
                iaConfig.getMaxRetries(),
                "gerar-duvidas-" + projeto.getId()
            );

            if (resposta == null || resposta.isBlank()) {
                return List.of();
            }
            return AvaliadorIaRuntimeHelper.extrairDuvidas(resposta);

        } catch (RuntimeException e) {
            log.error("Erro ao gerar dúvidas: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Erro ao gerar dúvidas: {}", e.getMessage());
            throw new IllegalStateException("IA nao gerou duvidas validas: " + e.getMessage(), e);
        }
    }

    @Override
    public Proposta sugerirProposta(Projeto projeto) {
        return AvaliadorIaRuntimeHelper.sugerirProposta(projeto);
    }

    @Override
    public String gerarTextoProposta(Projeto projeto, Proposta proposta) {
        try {
            BigDecimal valorProposto = proposta != null ? proposta.getValorProposto() : null;
            if (valorProposto == null) {
                throw new IllegalStateException("IA nao pode gerar proposta sem valor proposto.");
            }
            String valorStr = AvaliadorIaPromptFactory.formatarValor(valorProposto);

            if (deveUsarFallbackPorApiKeyAusente()) {
                throw new IllegalStateException("IA indisponivel: API key ausente para gerar texto de proposta.");
            }

            String contextoAprendido = carregarContexto(AvaliadorIaMemoriaHelper.ContextoUso.PROPOSTA);
            String prompt = AvaliadorIaPromptFactory.gerarTextoProposta(
                projeto,
                valorStr,
                carregarManualVoz(),
                contextoAprendido
            );

            return AvaliadorIaPropostaHelper.gerarTextoValido(
                iaProvider,
                iaConfig,
                iaSafetyPolicy,
                qualidadeRascunhoService,
                projeto,
                prompt
            );
        } catch (RuntimeException e) {
            log.error("Erro ao gerar texto de proposta para projeto {}: {}", projeto != null ? projeto.getId() : null, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Erro ao gerar texto de proposta para projeto {}: {}", projeto != null ? projeto.getId() : null, e.getMessage());
            throw new IllegalStateException("IA nao gerou texto de proposta valido: " + e.getMessage(), e);
        }
    }

    @Override
    public String sugerirRespostaConversa(Projeto projeto, String historicoConversa, String ultimaMensagemCliente) {
        if (deveUsarFallbackPorApiKeyAusente()) {
            throw new IllegalStateException("IA indisponivel: API key ausente para responder conversa.");
        }

        try {
            String contextoAprendido = carregarContexto(AvaliadorIaMemoriaHelper.ContextoUso.CONVERSA);
            String prompt = AvaliadorIaPromptFactory.sugerirRespostaConversa(
                projeto,
                historicoConversa,
                contextoAprendido,
                ultimaMensagemCliente,
                carregarManualVoz()
            );

            return AvaliadorIaRespostaConversaHelper.gerarRespostaValida(
                iaProvider,
                iaConfig,
                iaSafetyPolicy,
                qualidadeRascunhoService,
                projeto,
                prompt
            );
        } catch (RuntimeException e) {
            if (falhaEsperadaRespostaConversa(e)) {
                log.warn("Resposta de conversa descartada pela IA: {}", e.getMessage());
            } else {
                log.error("Erro ao sugerir resposta de conversa: {}", e.getMessage());
            }
            throw e;
        } catch (Exception e) {
            log.error("Erro ao sugerir resposta de conversa: {}", e.getMessage());
            throw new IllegalStateException("IA nao gerou resposta de conversa valida: " + e.getMessage(), e);
        }
    }

    private boolean falhaEsperadaRespostaConversa(RuntimeException e) {
        return AvaliadorIaRespostaConversaHelper.falhaEsperada(e);
    }

    @Override
    public String reescreverRascunhoComOrientacao(String contexto, String rascunhoAtual, String orientacaoUsuario) {
        String rascunho = rascunhoAtual != null ? rascunhoAtual.trim() : "";
        if (orientacaoUsuario == null || orientacaoUsuario.isBlank()) {
            throw new IllegalArgumentException("Reescrita exige orientacao humana.");
        }

        if (deveUsarFallbackPorApiKeyAusente()) {
            throw new IllegalStateException("IA indisponivel: API key ausente para reescrever rascunho.");
        }

        try {
            String contextoAprendido = carregarContexto(AvaliadorIaMemoriaHelper.ContextoUso.CONVERSA);
            String manualVoz = carregarManualVoz();
            RuntimeException ultimaFalha = null;
            for (int tentativa = 1; tentativa <= MAX_TENTATIVAS_REESCRITA_VALIDA; tentativa++) {
                String prompt = AvaliadorIaPromptFactory.reescreverRascunhoComOrientacao(
                    contexto,
                    contextoAprendido,
                    rascunho,
                    orientarNovaTentativa(orientacaoUsuario, ultimaFalha),
                    manualVoz
                );

                String resposta = RetryUtils.comRetryChecked(
                    () -> iaProvider.enviar(prompt, 2500),
                    iaConfig.getMaxRetries(),
                    "reescrever-rascunho-orientacao-" + tentativa
                );

                try {
                    String saneada = validarReescritaGerada(rascunho, resposta, orientacaoUsuario);
                    return saneada.trim();
                } catch (RuntimeException e) {
                    ultimaFalha = e;
                    if (tentativa < MAX_TENTATIVAS_REESCRITA_VALIDA) {
                        log.warn("Reescrita descartada na tentativa {}/{}: {}",
                            tentativa, MAX_TENTATIVAS_REESCRITA_VALIDA, e.getMessage());
                    }
                }
            }
            throw ultimaFalha != null
                ? ultimaFalha
                : new IllegalStateException("IA nao gerou reescrita valida.");
        } catch (RuntimeException e) {
            log.warn("Nao foi possivel reescrever rascunho com orientacao: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("Nao foi possivel reescrever rascunho com orientacao: {}", e.getMessage());
            throw new IllegalStateException("IA nao gerou reescrita valida: " + e.getMessage(), e);
        }
    }

    private String validarReescritaGerada(String rascunho, String resposta, String orientacaoUsuario) {
        if (resposta == null || resposta.isBlank()) {
            throw new IllegalStateException("IA retornou reescrita vazia.");
        }

        String saneada = iaSafetyPolicy.sanitize(resposta, LIMITE_RESPOSTA_CONVERSA);
        if (saneada == null || saneada.isBlank()) {
            throw new IllegalStateException("IA gerou reescrita bloqueada pela politica de seguranca.");
        }
        if ((!rascunho.isBlank() && saneada.trim().equals(rascunho))
            || AvaliadorIaRuntimeHelper.ehRespostaNegativaCurta(saneada)
            || qualidadeNaoAprova(saneada)) {
            throw new IllegalStateException("IA gerou reescrita reprovada pela qualidade.");
        }
        AvaliadorIaReescritaHelper.validarReescrita(rascunho, saneada, orientacaoUsuario);
        return saneada;
    }

    private String orientarNovaTentativa(String orientacaoUsuario, RuntimeException falhaAnterior) {
        if (falhaAnterior == null) {
            return orientacaoUsuario;
        }
        return orientacaoUsuario + "\n\n"
            + "A tentativa anterior foi descartada: " + falhaAnterior.getMessage() + "\n"
            + "Gere outra mensagem com estrutura realmente diferente, sem fechar escopo cedo e com pergunta concreta de qualificação.";
    }

    private boolean qualidadeNaoAprova(String texto) {
        QualidadeRascunhoDTO qualidade = qualidadeRascunhoService.avaliar(texto);
        return !"APROVAR".equalsIgnoreCase(qualidade.getDecisaoRecomendada());
    }

    private boolean deveUsarFallbackPorApiKeyAusente() {
        return AvaliadorIaRuntimeHelper.deveUsarFallbackPorApiKeyAusente(iaConfig);
    }

    @Override
    public QualidadeRascunhoDTO auditarRascunhoCliente(String contexto, String texto) {
        QualidadeRascunhoDTO base = qualidadeRascunhoService.avaliar(texto);
        if (base.bloqueante()) {
            return base;
        }
        if (deveUsarFallbackPorApiKeyAusente()) {
            return base;
        }

        try {
            String prompt = AvaliadorIaPromptFactory.auditarRascunhoCliente(contexto, texto);

            String resposta = RetryUtils.comRetryChecked(
                () -> iaProvider.enviar(prompt, 1200),
                iaConfig.getMaxRetries(),
                "auditar-rascunho-cliente"
            );

            QualidadeRascunhoDTO auditoria = AvaliadorIaAuditoriaHelper.parseAuditoriaRascunho(
                objectMapper,
                resposta,
                base
            );
            return AvaliadorIaAuditoriaHelper.combinarAuditoria(base, auditoria);
        } catch (Exception e) {
            log.warn("Auditoria IA indisponivel; usando qualidade deterministica: {}", e.getMessage());
            return base;
        }
    }

    public void limparCache() {
        cacheAvaliacoes.clear();
        log.info("Cache de avaliações limpo");
    }

    private String carregarConhecimento() {
        return AvaliadorIaMemoriaHelper.carregarConhecimento(getClass(), ARQUIVO_CONHECIMENTO);
    }

    private String carregarContexto(AvaliadorIaMemoriaHelper.ContextoUso uso) {
        return AvaliadorIaMemoriaHelper.carregarContexto(arquivoMemoria, memoriaLock, objectMapper, uso);
    }

    private String carregarManualVoz() {
        return manualVozLoader.carregar();
    }

    private void atualizarMemoria(Projeto projeto, boolean capaz, String resposta) {
        boolean deveOtimizar = AvaliadorIaMemoriaHelper.atualizarMemoria(
            projeto,
            capaz,
            resposta,
            arquivoMemoria,
            memoriaLock,
            objectMapper,
            iaProvider
        );
        if (deveOtimizar) {
            agendarOtimizacaoMemoria();
        }
    }

    private void agendarOtimizacaoMemoria() {
        if (!otimizacaoMemoriaEmAndamento.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                this.otimizarMemoriaContexto();
            } catch (Exception e) {
                log.error("Erro na otimização de memória assíncrona: {}", e.getMessage());
            } finally {
                otimizacaoMemoriaEmAndamento.set(false);
            }
        }, "Thread-Otimiza-Memoria");
        thread.setDaemon(true);
        thread.start();
    }

    public void otimizarMemoriaContexto() {
        AvaliadorIaMemoriaHelper.otimizarMemoriaContexto(
            iaConfig,
            iaProvider,
            arquivoMemoria,
            memoriaLock,
            objectMapper
        );
    }

    @Override
    public void salvarRegrasConsolidadas(String novasRegras) {
        AvaliadorIaMemoriaHelper.salvarRegrasConsolidadas(
            novasRegras,
            arquivoMemoria,
            memoriaLock,
            objectMapper
        );
    }

    @Override
    public Map<String, Object> obterDiagnosticoMemoriaIa() {
        return AvaliadorIaMemoriaHelper.obterDiagnosticoMemoriaIa(
            iaConfig,
            iaProvider,
            cacheAvaliacoes,
            arquivoMemoria,
            memoriaLock,
            objectMapper
        );
    }

}
