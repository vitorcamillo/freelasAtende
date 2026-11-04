package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.OperacaoDefaults;
import com.freelasatende.model.dto.AcaoControleDTO;
import com.freelasatende.model.dto.EnsinoFocoDTO;
import com.freelasatende.model.dto.PoliticaOperacionalResultadoDTO;
import com.freelasatende.model.dto.QualidadeRascunhoDTO;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Configuracao;
import com.freelasatende.model.entity.MensagemClienteRegistro;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusMensagemCliente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.repository.MensagemClienteRegistroRepository;
import com.freelasatende.service.interfaceServico.ModoEnsinamentoPolicyService;
import com.freelasatende.service.interfaceServico.QualidadeRascunhoService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.freelasatende.service.impl.ControleOperacionalJsonHelper.booleanPayload;
import static com.freelasatende.service.impl.ControleOperacionalJsonHelper.limitar;
import static com.freelasatende.service.impl.ControleOperacionalJsonHelper.longPayload;
import static com.freelasatende.service.impl.ControleOperacionalJsonHelper.nivelAutonomiaPayload;
import static com.freelasatende.service.impl.ControleOperacionalJsonHelper.textPayload;
import static com.freelasatende.service.impl.ControleOperacionalSummaryHelper.motivosAprendizado;

final class ControleAcaoMapper {

    private static final int TEXTO_PREVIEW_LIMITE = 1200;
    private static final List<StatusMensagemCliente> STATUS_RELACIONADOS = List.of(
        StatusMensagemCliente.PENDENTE,
        StatusMensagemCliente.BLOQUEADA,
        StatusMensagemCliente.ENVIADA,
        StatusMensagemCliente.SIMULADA
    );

    private final ObjectMapper objectMapper;
    private final MensagemClienteRegistroRepository mensagemClienteRegistroRepository;
    private final QualidadeRascunhoService qualidadeRascunhoService;
    private final ModoEnsinamentoPolicyService modoEnsinamentoPolicyService;

    ControleAcaoMapper(
        ObjectMapper objectMapper,
        MensagemClienteRegistroRepository mensagemClienteRegistroRepository,
        QualidadeRascunhoService qualidadeRascunhoService,
        ModoEnsinamentoPolicyService modoEnsinamentoPolicyService
    ) {
        this.objectMapper = objectMapper;
        this.mensagemClienteRegistroRepository = mensagemClienteRegistroRepository;
        this.qualidadeRascunhoService = qualidadeRascunhoService;
        this.modoEnsinamentoPolicyService = modoEnsinamentoPolicyService;
    }

    AcaoControleDTO mapearAcao(AcaoPendente acao, EnsinoFocoDTO foco) {
        String texto = extrairTextoCliente(acao);
        JsonNode payload = ControleOperacionalJsonHelper.lerPayload(objectMapper, acao);
        Optional<MensagemClienteRegistro> registro = buscarRegistroRelacionado(acao);
        String statusGuardiao = registro.map(r -> r.getStatus().name()).orElse(statusGuardiaoFallback(acao));
        String motivoBloqueio = registro.map(MensagemClienteRegistro::getMotivoBloqueio).orElse(null);
        QualidadeRascunhoDTO qualidade = qualidadeRascunhoService.avaliar(texto, statusGuardiao, motivoBloqueio);
        List<String> sinais = sinaisRisco(acao, texto, registro, qualidade);
        PoliticaOperacionalResultadoDTO politica = modoEnsinamentoPolicyService.avaliar(
            acao,
            texto,
            false,
            acao.getObservacaoUsuario(),
            statusGuardiao,
            motivoBloqueio,
            qualidade
        );

        PlataformaFreela plataforma = PlataformaFreela.normalizar(acao.getPlataforma());
        return AcaoControleDTO.builder()
            .id(acao.getId())
            .plataforma(plataforma)
            .plataformaLabel(plataforma.label())
            .projetoId(acao.getProjetoId())
            .conversaId(acao.getConversaId())
            .tipo(acao.getTipo())
            .status(acao.getStatus())
            .modoOrigem(acao.getModoOrigem())
            .tituloProjeto(acao.getTituloProjeto())
            .resumoInterno(acao.getResumo())
            .textoClientePreview(limitar(texto, TEXTO_PREVIEW_LIMITE))
            .nivelAutonomia(nivelAutonomiaPayload(payload))
            .entendimentoIa(textPayload(payload, "entendimentoIa"))
            .analiseIa(textPayload(payload, "analiseIa"))
            .opcoesIa(ControleOperacionalJsonHelper.textosPayload(objectMapper, payload, "opcoesIa"))
            .atitudeSugerida(textPayload(payload, "atitudeSugerida"))
            .conversaEnsinoObrigatoria(booleanPayload(payload, "conversaEnsinoObrigatoria"))
            .conversaEnsinoRespondida(conversaEnsinoRespondida(payload))
            .informacoesConfirmadas(ControleOperacionalJsonHelper.textosPayload(objectMapper, payload, "informacoesConfirmadas"))
            .informacoesPendentes(ControleOperacionalJsonHelper.textosPayload(objectMapper, payload, "informacoesPendentes"))
            .sabeFazer(ControleOperacionalJsonHelper.textosPayload(objectMapper, payload, "sabeFazer"))
            .duvidasInternas(ControleOperacionalJsonHelper.textosPayload(objectMapper, payload, "duvidasInternas"))
            .perguntasParaCliente(ControleOperacionalJsonHelper.textosPayload(objectMapper, payload, "perguntasParaCliente"))
            .riscosDecisao(ControleOperacionalJsonHelper.textosPayload(objectMapper, payload, "riscosDecisao"))
            .proximaAcaoSugerida(textPayload(payload, "proximaAcaoSugerida"))
            .perguntaParaUsuario(textPayload(payload, "perguntaParaUsuario"))
            .regraAprendizadoSugerida(textPayload(payload, "regraAprendizadoSugerida"))
            .mensagemGatilhoId(longPayload(payload, "mensagemGatilhoId"))
            .mensagemGatilhoTexto(textPayload(payload, "mensagemGatilhoTexto"))
            .mensagemGatilhoAutor(textPayload(payload, "mensagemGatilhoAutor"))
            .mensagemGatilhoNome(textPayload(payload, "mensagemGatilhoNome"))
            .idCliente(longPayload(payload, "idCliente"))
            .nomeCliente(textPayload(payload, "nomeCliente"))
            .idFreelancer(longPayload(payload, "idFreelancer"))
            .nomeFreelancer(textPayload(payload, "nomeFreelancer"))
            .ultimasMensagensMinhas(ControleOperacionalJsonHelper.mensagensPayload(objectMapper, payload, "ultimasMensagensMinhas"))
            .ultimasMensagensCliente(ControleOperacionalJsonHelper.mensagensPayload(objectMapper, payload, "ultimasMensagensCliente"))
            .mensagensIgnoradasResumo(ControleOperacionalJsonHelper.textosPayload(objectMapper, payload, "mensagensIgnoradasResumo"))
            .autoriaConfiavel(booleanPayload(payload, "autoriaConfiavel"))
            .motivoAutoria(textPayload(payload, "motivoAutoria"))
            .statusGuardiao(statusGuardiao)
            .motivoBloqueio(motivoBloqueio)
            .nivelConfianca(nivelConfianca(sinais, qualidade))
            .scoreNaturalidade(qualidade.getScoreNaturalidade())
            .scoreClareza(qualidade.getScoreClareza())
            .scoreRisco(qualidade.getScoreRisco())
            .scoreDuplicidade(qualidade.getScoreDuplicidade())
            .scoreConfianca(qualidade.getScoreConfianca())
            .decisaoQualidade(qualidade.getDecisaoRecomendada())
            .comentarioQualidade(qualidade.getComentario())
            .sinaisQualidade(qualidade.getSinais())
            .sinaisRisco(sinais)
            .checklistRegras(politica.getChecklist())
            .feedbackObrigatorio(true)
            .motivosAprendizado(motivosAprendizado())
            .ensinoStatus(politica.getStatus())
            .focoEnsinoAtual(acaoEstaEmFoco(acao, foco))
            .dataCriacao(acao.getDataCriacao())
            .dataAtualizacao(acao.getDataAtualizacao())
            .observacaoUsuario(acao.getObservacaoUsuario())
            .build();
    }

    EnsinoFocoDTO focoDaAcao(AcaoPendente acao, String status, String mensagem) {
        return EnsinoFocoDTO.builder()
            .ativo(true)
            .acaoId(acao.getId())
            .projetoId(acao.getProjetoId())
            .conversaId(acao.getConversaId())
            .tipo(acao.getTipo())
            .tituloProjeto(acao.getTituloProjeto())
            .resumoInterno(acao.getResumo())
            .textoClientePreview(limitar(extrairTextoCliente(acao), TEXTO_PREVIEW_LIMITE))
            .desde(LocalDateTime.now())
            .status(status)
            .mensagem(mensagem)
            .build();
    }

    TipoAcaoPendente tipoFoco(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return TipoAcaoPendente.valueOf(valor);
        } catch (Exception e) {
            return null;
        }
    }

    String extrairTextoCliente(AcaoPendente acao) {
        JsonNode node = ControleOperacionalJsonHelper.lerPayload(objectMapper, acao);
        if (node.isMissingNode()) {
            return "";
        }
        JsonNode mensagem = node.get("mensagem");
        return mensagem != null && !mensagem.isNull() ? mensagem.asText("") : "";
    }

    static Configuracao configuracaoPadrao() {
        return Configuracao.builder()
            .modoSistema(com.freelasatende.model.enums.ModoSistema.ENSINAMENTO)
            .pollingIntervaloSegundos(30)
            .ativo(true)
            .limiteConexoesDiario(OperacaoDefaults.LIMITE_CONEXOES_DIARIO)
            .conexoesUsadasHoje(0)
            .dataUltimoReset(LocalDateTime.now())
            .build();
    }

    private boolean conversaEnsinoRespondida(JsonNode payload) {
        JsonNode notas = payload.path("notasEnsino");
        JsonNode respostas = payload.path("respostasDuvidasIa");
        return (notas.isArray() && !notas.isEmpty()) || (respostas.isArray() && !respostas.isEmpty());
    }

    private boolean acaoEstaEmFoco(AcaoPendente acao, EnsinoFocoDTO foco) {
        if (acao == null || foco == null || !foco.isAtivo()) {
            return false;
        }
        if (foco.getAcaoId() != null && foco.getAcaoId().equals(acao.getId())) {
            return true;
        }
        if (foco.getConversaId() != null && foco.getConversaId().equals(acao.getConversaId())) {
            return true;
        }
        return foco.getProjetoId() != null && foco.getProjetoId().equals(acao.getProjetoId());
    }

    private Optional<MensagemClienteRegistro> buscarRegistroRelacionado(AcaoPendente acao) {
        if (acao.getConversaId() != null) {
            return mensagemClienteRegistroRepository
                .findFirstByPlataformaAndConversaIdAndStatusInOrderByDataDesc(
                    acao.getPlataforma(),
                    acao.getConversaId(),
                    STATUS_RELACIONADOS
                );
        }
        if (acao.getProjetoId() != null) {
            return mensagemClienteRegistroRepository
                .findFirstByPlataformaAndProjetoIdAndStatusInOrderByDataDesc(
                    acao.getPlataforma(),
                    acao.getProjetoId(),
                    STATUS_RELACIONADOS
                );
        }
        return Optional.empty();
    }

    private List<String> sinaisRisco(
        AcaoPendente acao,
        String texto,
        Optional<MensagemClienteRegistro> registro,
        QualidadeRascunhoDTO qualidade
    ) {
        List<String> sinais = new ArrayList<>();
        if (acao.getTipo() == TipoAcaoPendente.CANCELAR_PROPOSTA) {
            sinais.add("cancelamento_requer_revisao_humana");
        }
        if (acao.getTipo() != TipoAcaoPendente.CANCELAR_PROPOSTA && (texto == null || texto.isBlank())) {
            sinais.add("sem_texto_cliente");
        }
        if (registro.isEmpty() && acao.getTipo() != TipoAcaoPendente.CANCELAR_PROPOSTA) {
            sinais.add("sem_registro_guardiao");
        }
        if (registro.map(r -> r.getStatus() == StatusMensagemCliente.BLOQUEADA).orElse(false)) {
            sinais.add("guardiao_bloqueou_rascunho");
        }
        if (qualidade != null && qualidade.bloqueante()) {
            sinais.add("qualidade_bloqueante");
        }
        if (qualidade != null && qualidade.getScoreRisco() != null && qualidade.getScoreRisco() >= 50) {
            sinais.add("risco_qualidade_alto");
        }
        if (qualidade != null && qualidade.getScoreNaturalidade() != null && qualidade.getScoreNaturalidade() < 70) {
            sinais.add("naturalidade_baixa");
        }
        return sinais;
    }

    private String nivelConfianca(List<String> sinais, QualidadeRascunhoDTO qualidade) {
        if (sinais.contains("guardiao_bloqueou_rascunho")) {
            return "BLOQUEADA";
        }
        if (qualidade != null && "BLOQUEADA".equalsIgnoreCase(qualidade.getNivelConfianca())) {
            return "BLOQUEADA";
        }
        if (qualidade != null && "ALTA".equalsIgnoreCase(qualidade.getNivelConfianca()) && sinais.isEmpty()) {
            return "ALTA";
        }
        if (!sinais.isEmpty()) {
            return "MEDIA";
        }
        return qualidade != null && qualidade.getNivelConfianca() != null ? qualidade.getNivelConfianca() : "ALTA";
    }

    private String statusGuardiaoFallback(AcaoPendente acao) {
        return acao.getTipo() == TipoAcaoPendente.CANCELAR_PROPOSTA ? "NAO_APLICAVEL" : "SEM_REGISTRO";
    }
}
