package com.freelasatende.service.impl;

import com.freelasatende.model.dto.AvaliacaoResultado;
import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.service.interfaceServico.HistoricoService;
import com.freelasatende.service.interfaceServico.IaProvider;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
final class AvaliadorIaHistoricoHelper {

    private AvaliadorIaHistoricoHelper() {
    }

    static AvaliacaoResultado recusaLocal(
        HistoricoService historicoService,
        Map<Long, Boolean> cache,
        Projeto projeto,
        String motivo
    ) {
        cache.put(projeto.getId(), false);
        historicoService.registrarInteracao(
            Interacao.builder()
                .projetoId(projeto.getId())
                .projetoTitulo(projeto.getTitulo())
                .tipo(TipoInteracao.PROJETO_AVALIADO)
                .conteudo("Avaliacao local recusou antes da IA: " + motivo)
                .data(LocalDateTime.now())
                .build()
        );
        log.info("Projeto {} recusado por regra local de escopo: {}", projeto.getId(), motivo);
        return AvaliacaoResultado.builder()
            .capaz(false)
            .motivoRecusa(motivo)
            .build();
    }

    static AvaliacaoResultado iaIndisponivel(
        HistoricoService historicoService,
        IaProvider iaProvider,
        Projeto projeto,
        IaProvider.IaProviderException e
    ) {
        String detalhe = detalheCurto(e);
        String motivo = "IA temporariamente indisponivel para avaliar projeto: " + detalhe;
        historicoService.registrarInteracao(
            Interacao.builder()
                .projetoId(projeto.getId())
                .projetoTitulo(projeto.getTitulo())
                .tipo(TipoInteracao.ERRO_SISTEMA)
                .conteudo("IA (" + iaProvider.getNome() + "/" + iaProvider.getModelo()
                    + ") indisponivel; envio automatico bloqueado: " + detalhe)
                .data(LocalDateTime.now())
                .build()
        );
        log.warn("IA indisponivel ao avaliar projeto {} via {}/{}: {}",
            projeto.getId(), iaProvider.getNome(), iaProvider.getModelo(), detalhe);
        return AvaliacaoResultado.builder()
            .capaz(false)
            .motivoRecusa(motivo)
            .build();
    }

    private static String detalheCurto(IaProvider.IaProviderException erro) {
        if (erro.getStatusCode() == 429) {
            return "limite temporário do provedor (HTTP 429)";
        }
        if (erro.getStatusCode() >= 500) {
            return "falha temporária do provedor (HTTP " + erro.getStatusCode() + ")";
        }
        String mensagem = erro.getMessage() != null ? erro.getMessage().replaceAll("\\s+", " ").trim() : "falha sem detalhe";
        return mensagem.length() > 300 ? mensagem.substring(0, 300).trim() + "..." : mensagem;
    }

    static void emCache(HistoricoService historicoService, Projeto projeto, boolean capaz) {
        historicoService.registrarInteracao(
            Interacao.builder()
                .projetoId(projeto.getId())
                .projetoTitulo(projeto.getTitulo())
                .tipo(capaz ? TipoInteracao.PROJETO_APROVADO : TipoInteracao.PROJETO_AVALIADO)
                .conteudo("IA (cache) reutilizou avaliacao: " + (capaz ? "SIM" : "NAO"))
                .data(LocalDateTime.now())
                .build()
        );
    }
}
