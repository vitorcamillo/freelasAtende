package com.freelasatende.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.dto.AcaoControleDTO;
import com.freelasatende.model.dto.EnsinoFocoDTO;
import com.freelasatende.model.dto.InboxAuditoriaDTO;
import com.freelasatende.model.dto.InboxConversaAuditoriaDTO;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.InboxAuditoriaRegistro;
import com.freelasatende.model.entity.InboxConversaAuditoriaRegistro;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.InboxAuditoriaRegistroRepository;
import com.freelasatende.repository.InboxConversaAuditoriaRegistroRepository;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.freelasatende.service.impl.ControleOperacionalJsonHelper.limitar;
import static com.freelasatende.service.impl.InboxAuditoriaDecisionHelper.analiseAuditoria;
import static com.freelasatende.service.impl.InboxAuditoriaDecisionHelper.autorAuditoria;
import static com.freelasatende.service.impl.InboxAuditoriaDecisionHelper.decisaoIaAuditoria;
import static com.freelasatende.service.impl.InboxAuditoriaDecisionHelper.mensagemAcionavelAuditoria;
import static com.freelasatende.service.impl.InboxAuditoriaDecisionHelper.mensagemVisivelAuditoria;
import static com.freelasatende.service.impl.InboxAuditoriaDecisionHelper.motivoAuditoria;
import static com.freelasatende.service.impl.InboxAuditoriaDecisionHelper.painelCenariosAuditoria;
import static com.freelasatende.service.impl.InboxAuditoriaDecisionHelper.perguntaReadOnlyAuditoria;
import static com.freelasatende.service.impl.InboxAuditoriaDecisionHelper.sinaisAuditoria;
import static com.freelasatende.service.impl.InboxAuditoriaDecisionHelper.statusAuditoria;

@RequiredArgsConstructor
class ControleInboxAuditoriaManager {

    private final ObjectMapper objectMapper;
    private final AcaoPendenteRepository acaoPendenteRepository;
    private final InboxAuditoriaRegistroRepository inboxAuditoriaRegistroRepository;
    private final InboxConversaAuditoriaRegistroRepository inboxConversaAuditoriaRegistroRepository;
    private final MarketplaceGateway marketplaceGateway;
    private final ControleAcaoMapper acaoMapper;
    private final boolean realWriteEnabled;
    private final boolean realWriteSimulateEnabled;
    private final boolean realIaEnabled;
    private final boolean telegramSimulado;
    private final boolean iaPronta;
    private final String motivoIa;

    InboxAuditoriaDTO registrarAuditoriaInbox(InboxAuditoriaDTO auditoria) {
        InboxAuditoriaRegistro registro = inboxAuditoriaRegistroRepository.save(InboxAuditoriaRegistro.builder()
            .data(auditoria.getData())
            .leituraSemMarcarVisualizada(auditoria.isLeituraSemMarcarVisualizada())
            .escritaRealHabilitada(auditoria.isEscritaRealHabilitada())
            .simulacaoEnvioHabilitada(auditoria.isSimulacaoEnvioHabilitada())
            .telegramSimulado(auditoria.isTelegramSimulado())
            .iaRealHabilitada(auditoria.isIaRealHabilitada())
            .iaPronta(auditoria.isIaPronta())
            .motivoIa(auditoria.getMotivoIa())
            .totalConversas(auditoria.getTotalConversas())
            .totalMensagens(auditoria.getTotalMensagens())
            .totalUltimaCliente(auditoria.getTotalUltimaCliente())
            .totalUltimaMinha(auditoria.getTotalUltimaMinha())
            .totalSemTextoUtil(auditoria.getTotalSemTextoUtil())
            .totalAcionaveis(auditoria.getTotalAcionaveis())
            .totalPendenciasExistentes(auditoria.getTotalPendenciasExistentes())
            .totalIgnoradas(auditoria.getTotalIgnoradas())
            .painelCenariosJson(ControleOperacionalJsonHelper.json(objectMapper, auditoria.getPainelCenarios()))
            .relatorioHtml(InboxAuditoriaReportWriter.relatorioHtmlPath())
            .relatorioJson(InboxAuditoriaReportWriter.relatorioJsonPath())
            .build());

        Long auditoriaId = registro.getId();
        LocalDateTime data = registro.getData();
        List<InboxConversaAuditoriaDTO> conversas = auditoria.getConversas() == null
            ? List.of()
            : auditoria.getConversas();
        List<InboxConversaAuditoriaRegistro> registrosConversas = conversas.stream()
            .map(conversa -> registroConversa(auditoriaId, data, conversa))
            .toList();
        inboxConversaAuditoriaRegistroRepository.saveAll(registrosConversas);

        auditoria.setAuditoriaId(auditoriaId);
        auditoria.setPersistida(true);
        auditoria.setRelatorioHtml(InboxAuditoriaReportWriter.relatorioHtmlPath());
        auditoria.setRelatorioJson(InboxAuditoriaReportWriter.relatorioJsonPath());
        conversas.forEach(conversa -> {
            conversa.setAuditoriaId(auditoriaId);
            conversa.setDataAuditoria(data);
        });
        escreverRelatorioAuditoria(auditoria);
        return auditoria;
    }

    InboxAuditoriaDTO mapearAuditoriaPersistida(InboxAuditoriaRegistro registro) {
        List<InboxConversaAuditoriaDTO> conversas = inboxConversaAuditoriaRegistroRepository
            .findByAuditoriaIdOrderByIdAsc(registro.getId())
            .stream()
            .map(this::mapearConversaPersistida)
            .toList();
        return InboxAuditoriaDTO.builder()
            .auditoriaId(registro.getId())
            .persistida(true)
            .leituraSemMarcarVisualizada(registro.isLeituraSemMarcarVisualizada())
            .escritaRealHabilitada(registro.isEscritaRealHabilitada())
            .simulacaoEnvioHabilitada(registro.isSimulacaoEnvioHabilitada())
            .telegramSimulado(registro.isTelegramSimulado())
            .iaRealHabilitada(registro.isIaRealHabilitada())
            .iaPronta(registro.isIaPronta())
            .motivoIa(registro.getMotivoIa())
            .totalConversas(registro.getTotalConversas())
            .totalMensagens(registro.getTotalMensagens())
            .totalUltimaCliente(registro.getTotalUltimaCliente())
            .totalUltimaMinha(registro.getTotalUltimaMinha())
            .totalSemTextoUtil(registro.getTotalSemTextoUtil())
            .totalAcionaveis(registro.getTotalAcionaveis())
            .totalPendenciasExistentes(registro.getTotalPendenciasExistentes())
            .totalIgnoradas(registro.getTotalIgnoradas())
            .painelCenarios(ControleOperacionalJsonHelper.mapJson(objectMapper, registro.getPainelCenariosJson()))
            .relatorioHtml(registro.getRelatorioHtml())
            .relatorioJson(registro.getRelatorioJson())
            .conversas(conversas)
            .data(registro.getData())
            .build();
    }

    InboxAuditoriaDTO auditoriaVazia() {
        return InboxAuditoriaDTO.builder()
            .auditoriaId(null)
            .persistida(false)
            .leituraSemMarcarVisualizada(true)
            .escritaRealHabilitada(realWriteEnabled)
            .simulacaoEnvioHabilitada(realWriteSimulateEnabled)
            .telegramSimulado(telegramSimulado)
            .iaRealHabilitada(realIaEnabled)
            .iaPronta(iaPronta)
            .motivoIa(motivoIa)
            .totalConversas(0)
            .totalMensagens(0)
            .totalUltimaCliente(0)
            .totalUltimaMinha(0)
            .totalSemTextoUtil(0)
            .totalAcionaveis(0)
            .totalPendenciasExistentes(0)
            .totalIgnoradas(0)
            .painelCenarios(painelCenariosAuditoria(List.of()))
            .relatorioHtml(InboxAuditoriaReportWriter.relatorioHtmlPath())
            .relatorioJson(InboxAuditoriaReportWriter.relatorioJsonPath())
            .conversas(List.of())
            .data(LocalDateTime.now())
            .build();
    }

    InboxAuditoriaDTO construirAuditoriaInbox(EnsinoFocoDTO foco) {
        List<InboxConversaAuditoriaDTO> itens = marketplaceGateway.listarConversasInbox().stream()
            .map(conversa -> auditarConversa(conversa, foco))
            .toList();

        int totalMensagens = itens.stream().mapToInt(InboxConversaAuditoriaDTO::getTotalMensagens).sum();
        int totalUltimaCliente = (int) itens.stream()
            .filter(item -> "CLIENTE".equals(item.getUltimaMensagemAutor()))
            .count();
        int totalUltimaMinha = (int) itens.stream()
            .filter(item -> "EU".equals(item.getUltimaMensagemAutor()))
            .count();
        int totalSemTextoUtil = (int) itens.stream()
            .filter(item -> "SEM_TEXTO_UTIL".equals(item.getStatus()))
            .count();
        int totalAcionaveis = (int) itens.stream()
            .filter(InboxConversaAuditoriaDTO::isAcionavel)
            .count();
        int totalPendencias = (int) itens.stream()
            .filter(InboxConversaAuditoriaDTO::isPendenciaExistente)
            .count();
        Map<String, Object> painel = painelCenariosAuditoria(itens);

        return InboxAuditoriaDTO.builder()
            .auditoriaId(null)
            .persistida(false)
            .leituraSemMarcarVisualizada(true)
            .escritaRealHabilitada(realWriteEnabled)
            .simulacaoEnvioHabilitada(realWriteSimulateEnabled)
            .telegramSimulado(telegramSimulado)
            .iaRealHabilitada(realIaEnabled)
            .iaPronta(iaPronta)
            .motivoIa(motivoIa)
            .totalConversas(itens.size())
            .totalMensagens(totalMensagens)
            .totalUltimaCliente(totalUltimaCliente)
            .totalUltimaMinha(totalUltimaMinha)
            .totalSemTextoUtil(totalSemTextoUtil)
            .totalAcionaveis(totalAcionaveis)
            .totalPendenciasExistentes(totalPendencias)
            .totalIgnoradas(Math.max(0, itens.size() - totalAcionaveis))
            .painelCenarios(painel)
            .relatorioHtml(InboxAuditoriaReportWriter.relatorioHtmlPath())
            .relatorioJson(InboxAuditoriaReportWriter.relatorioJsonPath())
            .conversas(itens)
            .data(LocalDateTime.now())
            .build();
    }

    Map<String, Object> resumoAuditoriaInbox(InboxAuditoriaDTO auditoria) {
        Map<String, Object> resumo = new LinkedHashMap<>();
        resumo.put("auditoriaId", auditoria.getAuditoriaId());
        resumo.put("persistida", auditoria.isPersistida());
        resumo.put("data", auditoria.getData());
        resumo.put("totalConversas", auditoria.getTotalConversas());
        resumo.put("totalMensagens", auditoria.getTotalMensagens());
        resumo.put("totalAcionaveis", auditoria.getTotalAcionaveis());
        resumo.put("totalPendenciasExistentes", auditoria.getTotalPendenciasExistentes());
        resumo.put("leituraSemMarcarVisualizada", auditoria.isLeituraSemMarcarVisualizada());
        resumo.put("escritaRealHabilitada", auditoria.isEscritaRealHabilitada());
        resumo.put("simulacaoEnvioHabilitada", auditoria.isSimulacaoEnvioHabilitada());
        resumo.put("telegramSimulado", auditoria.isTelegramSimulado());
        resumo.put("iaRealHabilitada", auditoria.isIaRealHabilitada());
        resumo.put("iaPronta", auditoria.isIaPronta());
        resumo.put("relatorioHtml", auditoria.getRelatorioHtml());
        resumo.put("relatorioJson", auditoria.getRelatorioJson());
        resumo.put("painelCenarios", auditoria.getPainelCenarios());
        return resumo;
    }

    private InboxConversaAuditoriaRegistro registroConversa(
        Long auditoriaId,
        LocalDateTime data,
        InboxConversaAuditoriaDTO conversa
    ) {
        return InboxConversaAuditoriaRegistro.builder()
            .auditoriaId(auditoriaId)
            .dataAuditoria(data)
            .conversaId(conversa.getConversaId())
            .projetoId(conversa.getProjetoId())
            .tituloProjeto(conversa.getTituloProjeto())
            .clienteId(conversa.getClienteId())
            .nomeCliente(conversa.getNomeCliente())
            .totalMensagens(conversa.getTotalMensagens())
            .mensagensCliente(conversa.getMensagensCliente())
            .mensagensMinhas(conversa.getMensagensMinhas())
            .ultimaMensagemId(conversa.getUltimaMensagemId())
            .ultimaMensagemAutor(conversa.getUltimaMensagemAutor())
            .ultimaMensagemTexto(conversa.getUltimaMensagemTexto())
            .ultimaMensagemMillis(conversa.getUltimaMensagemMillis())
            .status(conversa.getStatus())
            .motivo(conversa.getMotivo())
            .analiseOperacional(conversa.getAnaliseOperacional())
            .acionavel(conversa.isAcionavel())
            .pendenciaExistente(conversa.isPendenciaExistente())
            .acaoPendenteId(conversa.getAcaoPendenteId())
            .rascunhoPendente(conversa.getRascunhoPendente())
            .decisaoIa(conversa.getDecisaoIa())
            .perguntaParaUsuario(conversa.getPerguntaParaUsuario())
            .duvidasInternasJson(ControleOperacionalJsonHelper.json(objectMapper, conversa.getDuvidasInternas()))
            .sinaisJson(ControleOperacionalJsonHelper.json(objectMapper, conversa.getSinais()))
            .scoreRisco(conversa.getScoreRisco())
            .decisaoQualidade(conversa.getDecisaoQualidade())
            .comentarioQualidade(conversa.getComentarioQualidade())
            .sinaisQualidadeJson(ControleOperacionalJsonHelper.json(objectMapper, conversa.getSinaisQualidade()))
            .build();
    }

    private InboxConversaAuditoriaDTO mapearConversaPersistida(InboxConversaAuditoriaRegistro registro) {
        return InboxConversaAuditoriaDTO.builder()
            .auditoriaId(registro.getAuditoriaId())
            .dataAuditoria(registro.getDataAuditoria())
            .conversaId(registro.getConversaId())
            .projetoId(registro.getProjetoId())
            .tituloProjeto(registro.getTituloProjeto())
            .clienteId(registro.getClienteId())
            .nomeCliente(registro.getNomeCliente())
            .totalMensagens(registro.getTotalMensagens())
            .mensagensCliente(registro.getMensagensCliente())
            .mensagensMinhas(registro.getMensagensMinhas())
            .ultimaMensagemId(registro.getUltimaMensagemId())
            .ultimaMensagemAutor(registro.getUltimaMensagemAutor())
            .ultimaMensagemTexto(registro.getUltimaMensagemTexto())
            .ultimaMensagemMillis(registro.getUltimaMensagemMillis())
            .status(registro.getStatus())
            .motivo(registro.getMotivo())
            .analiseOperacional(registro.getAnaliseOperacional())
            .acionavel(registro.isAcionavel())
            .pendenciaExistente(registro.isPendenciaExistente())
            .acaoPendenteId(registro.getAcaoPendenteId())
            .rascunhoPendente(registro.getRascunhoPendente())
            .decisaoIa(registro.getDecisaoIa())
            .perguntaParaUsuario(registro.getPerguntaParaUsuario())
            .duvidasInternas(ControleOperacionalJsonHelper.listaJson(objectMapper, registro.getDuvidasInternasJson()))
            .scoreRisco(registro.getScoreRisco())
            .decisaoQualidade(registro.getDecisaoQualidade())
            .comentarioQualidade(registro.getComentarioQualidade())
            .sinaisQualidade(ControleOperacionalJsonHelper.listaJson(objectMapper, registro.getSinaisQualidadeJson()))
            .sinais(ControleOperacionalJsonHelper.listaJson(objectMapper, registro.getSinaisJson()))
            .build();
    }

    private InboxConversaAuditoriaDTO auditarConversa(
        com.freelasatende.model.dto.ConversaResumo conversa,
        EnsinoFocoDTO foco
    ) {
        List<MensagemConversa> mensagens = marketplaceGateway.listarMensagensConversa(conversa.getIdConversa(), false);
        List<MensagemConversa> visiveis = mensagens.stream()
            .filter(InboxAuditoriaDecisionHelper::mensagemVisivelAuditoria)
            .toList();
        MensagemConversa ultima = visiveis.stream()
            .max(Comparator.comparing(msg -> msg.getDataCriacaoMillis() != null ? msg.getDataCriacaoMillis() : 0L))
            .orElse(null);
        Optional<AcaoPendente> pendente = acaoPendenteRepository
            .findFirstByPlataformaAndConversaIdAndTipoAndStatusOrderByDataCriacaoDesc(
                conversa.getPlataforma(),
                conversa.getIdConversa(),
                TipoAcaoPendente.RESPONDER_CONVERSA,
                StatusAcaoPendente.PENDENTE
            );
        AcaoControleDTO acaoControle = pendente
            .map(acao -> acaoMapper.mapearAcao(acao, foco))
            .orElse(null);

        int mensagensCliente = (int) mensagens.stream()
            .filter(msg -> Boolean.FALSE.equals(msg.getEnviadaPorMim()))
            .count();
        int mensagensMinhas = (int) mensagens.stream()
            .filter(msg -> Boolean.TRUE.equals(msg.getEnviadaPorMim()))
            .count();
        boolean acionavel = mensagemAcionavelAuditoria(ultima);
        String autor = autorAuditoria(ultima);
        String status = statusAuditoria(ultima, acionavel, pendente.isPresent());
        String motivo = motivoAuditoria(status);
        List<String> sinais = sinaisAuditoria(ultima, mensagens, pendente.isPresent());

        return InboxConversaAuditoriaDTO.builder()
            .auditoriaId(null)
            .dataAuditoria(null)
            .conversaId(conversa.getIdConversa())
            .projetoId(conversa.getIdProjeto())
            .tituloProjeto(conversa.getNomeProjeto())
            .clienteId(conversa.getIdCliente())
            .nomeCliente(conversa.getNomeCliente())
            .totalMensagens(mensagens.size())
            .mensagensCliente(mensagensCliente)
            .mensagensMinhas(mensagensMinhas)
            .ultimaMensagemId(ultima != null ? ultima.getId() : null)
            .ultimaMensagemAutor(autor)
            .ultimaMensagemTexto(ultima != null ? limitar(ultima.getTexto(), 500) : null)
            .ultimaMensagemMillis(ultima != null ? ultima.getDataCriacaoMillis() : null)
            .status(status)
            .motivo(motivo)
            .analiseOperacional(analiseAuditoria(conversa, mensagens.size(), status, pendente))
            .acionavel(acionavel)
            .pendenciaExistente(pendente.isPresent())
            .acaoPendenteId(pendente.map(AcaoPendente::getId).orElse(null))
            .rascunhoPendente(pendente.map(acaoMapper::extrairTextoCliente).map(texto -> limitar(texto, 500)).orElse(null))
            .decisaoIa(decisaoIaAuditoria(status, acionavel, acaoControle))
            .perguntaParaUsuario(acaoControle != null
                ? acaoControle.getPerguntaParaUsuario()
                : perguntaReadOnlyAuditoria(status))
            .duvidasInternas(acaoControle != null ? acaoControle.getDuvidasInternas() : List.of())
            .scoreRisco(acaoControle != null ? acaoControle.getScoreRisco() : null)
            .decisaoQualidade(acaoControle != null ? acaoControle.getDecisaoQualidade() : null)
            .comentarioQualidade(acaoControle != null ? acaoControle.getComentarioQualidade() : null)
            .sinaisQualidade(acaoControle != null ? acaoControle.getSinaisQualidade() : List.of())
            .sinais(sinais)
            .build();
    }

    private void escreverRelatorioAuditoria(InboxAuditoriaDTO auditoria) {
        new InboxAuditoriaReportWriter(objectMapper).escrever(auditoria);
    }
}
