package com.freelasatende.service.impl;

import com.freelasatende.model.entity.Cliente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.entity.Proposta;
import com.freelasatende.service.interfaceServico.MarketplaceAdapter;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

import static com.freelasatende.service.impl.AutoAtendimentoTextHelper.extrairSlug;

@Slf4j
class AutoAtendimentoProjetoSupport {

    private AutoAtendimentoProjetoSupport() {
    }

    static Projeto enriquecerProjeto(MarketplaceAdapter adapter, Projeto projeto) {
        PlataformaProjetoHelper.normalizarProjeto(projeto);
        Long idExterno = PlataformaProjetoHelper.idExternoOuId(projeto);
        String slug = extrairSlug(projeto.getUrlProjeto(), idExterno);
        Projeto detalhado;
        try {
            detalhado = adapter.buscarDetalhesProjeto(idExterno, slug);
        } catch (MarketplaceAdapterException e) {
            if (e.getType() == MarketplaceAdapterErrorType.OPERACAO_INDISPONIVEL
                || e.getType() == MarketplaceAdapterErrorType.CONVERSA_INACESSIVEL) {
                log.debug("Adapter {} sem enriquecimento de projeto; usando dados de discovery",
                    adapter.plataforma());
                return projeto;
            }
            throw e;
        }
        if (detalhado == null) {
            return projeto;
        }

        detalhado.setPlataforma(projeto.getPlataforma());
        detalhado.setIdExterno(idExterno);
        PlataformaProjetoHelper.normalizarProjeto(detalhado);

        if (detalhado.getTitulo() == null) {
            detalhado.setTitulo(projeto.getTitulo());
        }
        if (detalhado.getDescricao() == null) {
            detalhado.setDescricao(projeto.getDescricao());
        }
        if (detalhado.getOrcamento() == null) {
            detalhado.setOrcamento(projeto.getOrcamento());
        }
        if (detalhado.getMediaProposta() == null) {
            detalhado.setMediaProposta(projeto.getMediaProposta());
        }
        if (detalhado.getValorMinimo() == null) {
            detalhado.setValorMinimo(projeto.getValorMinimo());
        }
        if (detalhado.getCategoria() == null) {
            detalhado.setCategoria(projeto.getCategoria());
        }
        if (detalhado.getSubcategoria() == null) {
            detalhado.setSubcategoria(projeto.getSubcategoria());
        }
        if (detalhado.getNivelExperiencia() == null) {
            detalhado.setNivelExperiencia(projeto.getNivelExperiencia());
        }
        if (detalhado.getNumeroPropostas() == null) {
            detalhado.setNumeroPropostas(projeto.getNumeroPropostas());
        }
        if (detalhado.getInteressados() == null) {
            detalhado.setInteressados(projeto.getInteressados());
        }
        if (detalhado.getDescricaoCurta() == null) {
            detalhado.setDescricaoCurta(projeto.getDescricaoCurta());
        }
        if (detalhado.getNomeCliente() == null) {
            detalhado.setNomeCliente(projeto.getNomeCliente());
        }
        if (detalhado.getClienteId() == null) {
            detalhado.setClienteId(projeto.getClienteId());
        }
        if (detalhado.getUrlProjeto() == null) {
            detalhado.setUrlProjeto(projeto.getUrlProjeto());
        }
        return detalhado;
    }

    static boolean propostaTemValorReal(Projeto projeto, Proposta proposta) {
        boolean temBaseReal = projeto != null
            && ((projeto.getOrcamento() != null && projeto.getOrcamento().compareTo(BigDecimal.ZERO) > 0)
                || (projeto.getMediaProposta() != null && projeto.getMediaProposta().compareTo(BigDecimal.ZERO) > 0));
        return proposta != null
            && proposta.getValorProposto() != null
            && proposta.getValorProposto().compareTo(BigDecimal.ZERO) > 0
            && temBaseReal;
    }

    static boolean temMargemParaGastarConexao(MarketplaceAdapter adapter, int minConexoesReserva) {
        try {
            if (!adapter.capabilities().conexoes()) {
                return true;
            }
            Integer total = adapter.obterConexoesDisponiveis().getTotal();
            if (total == null) {
                return false;
            }
            return total > minConexoesReserva;
        } catch (Exception e) {
            log.warn("Não foi possível validar reserva de conexões: {}", e.getMessage());
            return false;
        }
    }

    static boolean clienteAprovado(Cliente cliente, boolean bloquearClienteSemAvaliacao, double minMediaCliente) {
        if (cliente == null) {
            return true;
        }
        Double nota = cliente.getMediaNota();
        if (nota == null) {
            return !bloquearClienteSemAvaliacao;
        }
        if (nota <= 0.0d) {
            return !bloquearClienteSemAvaliacao;
        }
        return nota >= minMediaCliente;
    }
}
