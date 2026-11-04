package com.freelasatende.service.impl;

import com.freelasatende.model.dto.EnsinoFocoDTO;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Configuracao;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.ConfiguracaoRepository;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.freelasatende.service.impl.ControleOperacionalJsonHelper.limitar;

final class ControleEnsinoFocoManager {

    private static final int TEXTO_PREVIEW_LIMITE = 1200;

    private final ConfiguracaoRepository configuracaoRepository;
    private final AcaoPendenteRepository acaoPendenteRepository;
    private final ControleAcaoMapper acaoMapper;

    ControleEnsinoFocoManager(
        ConfiguracaoRepository configuracaoRepository,
        AcaoPendenteRepository acaoPendenteRepository,
        ControleAcaoMapper acaoMapper
    ) {
        this.configuracaoRepository = configuracaoRepository;
        this.acaoPendenteRepository = acaoPendenteRepository;
        this.acaoMapper = acaoMapper;
    }

    EnsinoFocoDTO obterFocoEnsino() {
        Configuracao config = obterConfiguracao();
        if (!Boolean.TRUE.equals(config.getEnsinoFocoAtivo()) || config.getEnsinoFocoAcaoId() == null) {
            return EnsinoFocoDTO.builder()
                .ativo(false)
                .status("SEM_FOCO")
                .mensagem("Nenhum projeto em foco.")
                .build();
        }

        Optional<AcaoPendente> acaoAtual = acaoPendenteRepository.findById(config.getEnsinoFocoAcaoId());
        if (acaoAtual.isPresent() && acaoAtual.get().getStatus() == StatusAcaoPendente.PENDENTE) {
            return acaoMapper.focoDaAcao(acaoAtual.get(), "FOCADO", "Projeto em foco para ensino.");
        }

        return EnsinoFocoDTO.builder()
            .ativo(true)
            .acaoId(config.getEnsinoFocoAcaoId())
            .projetoId(config.getEnsinoFocoProjetoId())
            .conversaId(config.getEnsinoFocoConversaId())
            .tipo(acaoMapper.tipoFoco(config.getEnsinoFocoTipo()))
            .tituloProjeto(config.getEnsinoFocoTitulo())
            .textoClientePreview(config.getEnsinoFocoTextoPreview())
            .desde(config.getEnsinoFocoData())
            .status("FOCO_SEM_PENDENCIA")
            .mensagem("O foco salvo nao tem mais pendencia aberta.")
            .build();
    }

    EnsinoFocoDTO definirFocoEnsino(Long acaoId) {
        if (acaoId == null) {
            return EnsinoFocoDTO.builder()
                .ativo(false)
                .status("ACAO_INVALIDA")
                .mensagem("Informe uma acao pendente para focar.")
                .build();
        }
        Optional<AcaoPendente> optional = acaoPendenteRepository.findById(acaoId);
        if (optional.isEmpty() || optional.get().getStatus() != StatusAcaoPendente.PENDENTE) {
            return EnsinoFocoDTO.builder()
                .ativo(false)
                .acaoId(acaoId)
                .status("ACAO_NAO_ENCONTRADA")
                .mensagem("Acao pendente nao encontrada para foco.")
                .build();
        }

        AcaoPendente acao = optional.get();
        Configuracao config = obterConfiguracao();
        config.setEnsinoFocoAtivo(true);
        config.setEnsinoFocoAcaoId(acao.getId());
        config.setEnsinoFocoProjetoId(acao.getProjetoId());
        config.setEnsinoFocoConversaId(acao.getConversaId());
        config.setEnsinoFocoTipo(acao.getTipo() != null ? acao.getTipo().name() : null);
        config.setEnsinoFocoTitulo(acao.getTituloProjeto());
        config.setEnsinoFocoTextoPreview(limitar(acaoMapper.extrairTextoCliente(acao), TEXTO_PREVIEW_LIMITE));
        config.setEnsinoFocoData(LocalDateTime.now());
        configuracaoRepository.save(config);
        return acaoMapper.focoDaAcao(acao, "FOCADO", "Foco de ensino definido.");
    }

    EnsinoFocoDTO limparFocoEnsino() {
        Configuracao config = obterConfiguracao();
        config.setEnsinoFocoAtivo(false);
        config.setEnsinoFocoAcaoId(null);
        config.setEnsinoFocoProjetoId(null);
        config.setEnsinoFocoConversaId(null);
        config.setEnsinoFocoTipo(null);
        config.setEnsinoFocoTitulo(null);
        config.setEnsinoFocoTextoPreview(null);
        config.setEnsinoFocoData(null);
        configuracaoRepository.save(config);
        return EnsinoFocoDTO.builder()
            .ativo(false)
            .status("SEM_FOCO")
            .mensagem("Foco de ensino limpo.")
            .build();
    }

    Configuracao obterConfiguracao() {
        return configuracaoRepository.findFirstByOrderByIdAsc()
            .orElseGet(() -> configuracaoRepository.save(ControleAcaoMapper.configuracaoPadrao()));
    }
}
