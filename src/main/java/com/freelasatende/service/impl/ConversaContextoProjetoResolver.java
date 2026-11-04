package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.ProjetoRepository;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Reune o contexto que a IA precisa para responder uma conversa: o pedido completo
 * (descricao, orcamento, categoria) e o que ja enviamos ao cliente neste projeto
 * (proposta/abordagem), para a resposta nao se apoiar so na fala gatilho.
 */
@Slf4j
final class ConversaContextoProjetoResolver {

    private static final int LIMITE_CHARS_ENVIO_ANTERIOR = 1200;

    private final ProjetoRepository projetoRepository;
    private final AcaoPendenteRepository acaoPendenteRepository;
    private final ObjectMapper objectMapper;

    ConversaContextoProjetoResolver(
        ProjetoRepository projetoRepository,
        AcaoPendenteRepository acaoPendenteRepository,
        ObjectMapper objectMapper
    ) {
        this.projetoRepository = projetoRepository;
        this.acaoPendenteRepository = acaoPendenteRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolve o projeto com descricao/orcamento/categoria: primeiro o banco (ja descoberto
     * pelo polling), depois a plataforma, e por ultimo o stub montado da conversa.
     */
    Projeto resolverProjetoCompleto(Projeto stub, MarketplaceAdapter adapter) {
        Projeto completo = null;
        try {
            if (projetoRepository != null && stub.getId() != null) {
                completo = projetoRepository.findById(stub.getId()).orElse(null);
                if (!temContextoProjeto(completo) && stub.getIdExterno() != null) {
                    completo = projetoRepository
                        .findByPlataformaAndIdExterno(stub.getPlataforma(), stub.getIdExterno())
                        .orElse(completo);
                }
            }
        } catch (RuntimeException e) {
            log.warn("[{}][INBOX] Falha ao carregar projeto {} do banco para contexto da conversa: {}",
                stub.getPlataforma().key(), stub.getId(), e.getMessage());
        }
        if (!temContextoProjeto(completo) && adapter != null && stub.getIdExterno() != null) {
            try {
                Projeto detalhado = adapter.buscarDetalhesProjeto(stub.getIdExterno(), null);
                if (temContextoProjeto(detalhado)) {
                    completo = PlataformaProjetoHelper.normalizarProjeto(detalhado);
                }
            } catch (RuntimeException e) {
                log.debug("[{}][INBOX] Plataforma nao devolveu detalhes do projeto {} para contexto da conversa: {}",
                    stub.getPlataforma().key(), stub.getIdExterno(), e.getMessage());
            }
        }
        if (completo == null) {
            return stub;
        }
        if (completo.getPlataforma() == null) {
            completo.setPlataforma(stub.getPlataforma());
        }
        if (completo.getId() == null) {
            completo.setId(stub.getId());
        }
        if (completo.getIdExterno() == null) {
            completo.setIdExterno(stub.getIdExterno());
        }
        if (completo.getTitulo() == null || completo.getTitulo().isBlank()) {
            completo.setTitulo(stub.getTitulo());
        }
        return completo;
    }

    static boolean temContextoProjeto(Projeto projeto) {
        if (projeto == null) {
            return false;
        }
        return (projeto.getDescricao() != null && !projeto.getDescricao().isBlank())
            || (projeto.getDescricaoCurta() != null && !projeto.getDescricaoCurta().isBlank())
            || (projeto.getCategoria() != null && !projeto.getCategoria().isBlank())
            || projeto.getOrcamento() != null;
    }

    /**
     * Proposta e abordagem inicial ja executadas neste projeto, para a IA nao repetir
     * o que o cliente ja leu e continuar de onde a conversa parou.
     */
    String formatarEnviosAnteriores(PlataformaFreela plataforma, Long projetoId) {
        if (projetoId == null) {
            return null;
        }
        List<String> blocos = new ArrayList<>();
        try {
            for (AcaoPendente acao : acoesExecutadas(plataforma, projetoId, TipoAcaoPendente.ENVIAR_PROPOSTA)) {
                String mensagem = mensagemDoPayload(acao, PropostaPayload.class);
                if (mensagem != null) {
                    blocos.add("Proposta que eu ja enviei ao cliente: "
                        + AutoAtendimentoTextHelper.limitarTextoParaContexto(mensagem, LIMITE_CHARS_ENVIO_ANTERIOR));
                    break;
                }
            }
            for (AcaoPendente acao : acoesExecutadas(plataforma, projetoId, TipoAcaoPendente.ENVIAR_PERGUNTA)) {
                String mensagem = mensagemDoPayload(acao, PerguntaPayload.class);
                if (mensagem != null) {
                    blocos.add("Abordagem/pergunta que eu ja enviei ao cliente: "
                        + AutoAtendimentoTextHelper.limitarTextoParaContexto(mensagem, LIMITE_CHARS_ENVIO_ANTERIOR));
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn("[{}][INBOX] Falha ao carregar envios anteriores do projeto {}: {}",
                plataforma.key(), projetoId, e.getMessage());
        }
        return blocos.isEmpty() ? null : String.join("\n", blocos);
    }

    private List<AcaoPendente> acoesExecutadas(PlataformaFreela plataforma, Long projetoId, TipoAcaoPendente tipo) {
        List<AcaoPendente> acoes = acaoPendenteRepository.findByPlataformaAndProjetoIdAndTipoAndStatusOrderByDataCriacaoDesc(
            plataforma,
            projetoId,
            tipo,
            StatusAcaoPendente.EXECUTADA
        );
        return acoes != null ? acoes : List.of();
    }

    private String mensagemDoPayload(AcaoPendente acao, Class<?> tipoPayload) {
        if (acao == null || acao.getPayloadJson() == null || acao.getPayloadJson().isBlank()) {
            return null;
        }
        try {
            Object payload = objectMapper.readValue(acao.getPayloadJson(), tipoPayload);
            String mensagem = payload instanceof PropostaPayload proposta
                ? proposta.getMensagem()
                : payload instanceof PerguntaPayload pergunta ? pergunta.getMensagem() : null;
            return mensagem != null && !mensagem.isBlank() ? mensagem : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Historico que a IA recebe: envios anteriores primeiro, depois a conversa em si. */
    static String montarHistoricoParaIa(String historico, String enviosAnteriores) {
        if (enviosAnteriores == null || enviosAnteriores.isBlank()) {
            return historico;
        }
        return "[o que eu ja enviei neste projeto antes desta conversa]\n"
            + enviosAnteriores
            + "\n\n[mensagens da conversa]\n"
            + historico;
    }

    static String descreverFontesLidas(boolean contextoProjetoLido, boolean enviosLidos) {
        StringBuilder sb = new StringBuilder("a conversa");
        if (contextoProjetoLido) {
            sb.append(", a descricao do projeto");
        }
        if (enviosLidos) {
            sb.append(" e o que eu ja tinha enviado");
        }
        return sb.toString();
    }
}
