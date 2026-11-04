package com.freelasatende.service.impl;

import com.freelasatende.model.dto.AcaoControleDTO;
import com.freelasatende.model.dto.ConversaResumo;
import com.freelasatende.model.dto.EnsinoFocoDTO;
import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.entity.MensagemClienteRegistro;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.scheduler.PollingScheduler;
import com.freelasatende.service.interfaceServico.IaProvider;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static com.freelasatende.service.impl.TelegramActionMessageFormatter.escaparTelegram;
import static com.freelasatende.service.impl.TelegramActionMessageFormatter.formatarMensagemDiagnostico;
import static com.freelasatende.service.impl.TelegramActionMessageFormatter.formatarResumoAcao;
import static com.freelasatende.service.impl.TelegramActionMessageFormatter.limitar;
import static com.freelasatende.service.impl.TelegramActionMessageFormatter.listaOuNenhum;
import static com.freelasatende.service.impl.TelegramActionMessageFormatter.valorLong;
import static com.freelasatende.service.impl.TelegramActionMessageFormatter.valorObjeto;
import static com.freelasatende.service.impl.TelegramActionMessageFormatter.valorTexto;

final class TelegramCommandMessageFormatter {

    private TelegramCommandMessageFormatter() {
    }

    static String regrasOperacionais(Map<String, Object> memoria, Path manualVoz, Path regrasOperacionais) {
        String regrasAprendidas = valorObjeto(memoria.get("regrasConsolidadasPreview"));
        StringBuilder sb = new StringBuilder("Regras operacionais\n\n")
            .append("- Nada e enviado ao cliente sem aprovacao humana.\n")
            .append("- Telegram conversa com voce e prepara resposta; cliente so recebe apos aprovacao.\n")
            .append("- Mensagem normal sempre vira conversa operacional.\n")
            .append("- Ensino, reescrita e decisao de pendencia ficam nos botoes.\n\n")
            .append("Memoria IA\n")
            .append("- Saude: ").append(valorObjeto(memoria.get("saude"))).append("\n")
            .append("- Avaliados: ").append(valorObjeto(memoria.get("totalProjetosAvaliados"))).append("\n")
            .append("- Aprovados: ").append(valorObjeto(memoria.get("totalAprovados"))).append("\n")
            .append("- Recusados: ").append(valorObjeto(memoria.get("totalRecusados"))).append("\n");

        if (!"-".equals(regrasAprendidas)) {
            sb.append("- Regras aprendidas: ").append(escaparTelegram(limitar(regrasAprendidas, 550))).append("\n");
        }

        sb.append("\nManual de voz\n")
            .append(escaparTelegram(TelegramOperationalContextHelper.trechoArquivo(manualVoz, 1000)))
            .append("\n\nDocumento de regras\n")
            .append(escaparTelegram(TelegramOperationalContextHelper.trechoArquivo(regrasOperacionais, 1000)));

        return sb.toString();
    }

    static String duvidasOperacionais(List<AcaoControleDTO> comDuvidas, int totalPendencias) {
        if (comDuvidas.isEmpty()) {
            return "Dúvidas abertas\n\n"
                + "Nenhuma dúvida interna pendente agora.\n"
                + "Pendências totais: " + totalPendencias + ".";
        }

        StringBuilder sb = new StringBuilder("Dúvidas abertas\n\n");
        for (AcaoControleDTO acao : comDuvidas) {
            sb.append("#").append(acao.getId())
                .append(" | ").append(valorTexto(acao.getTipo() != null ? acao.getTipo().name() : null))
                .append(" | ").append(escaparTelegram(plataformaLabel(acao)))
                .append("\nProjeto: ").append(escaparTelegram(valorTexto(acao.getTituloProjeto())))
                .append("\nPergunta para voce: ").append(escaparTelegram(valorTexto(acao.getPerguntaParaUsuario())))
                .append("\nDúvidas internas: ").append(escaparTelegram(listaOuNenhum(acao.getDuvidasInternas())))
                .append("\nInformações pendentes: ").append(escaparTelegram(listaOuNenhum(acao.getInformacoesPendentes())))
                .append("\nRiscos: ").append(escaparTelegram(listaOuNenhum(acao.getRiscosDecisao())))
                .append("\n\n");
        }
        return sb.toString();
    }

    static String contextoOperacional(
        PollingScheduler pollingScheduler,
        IaProvider iaProvider,
        Map<String, Object> memoria,
        List<AcaoControleDTO> pendencias,
        EnsinoFocoDTO foco
    ) {
        StringBuilder sb = new StringBuilder("Contexto operacional\n\n")
            .append("Modo: ").append(pollingScheduler.getModoAtual()).append("\n")
            .append("Pausado: ").append(pollingScheduler.isPausado() ? "SIM" : "NAO").append("\n")
            .append("Provider IA: ").append(iaProvider.getNome()).append(" (").append(iaProvider.getModelo()).append(")\n")
            .append("Memoria: ").append(valorObjeto(memoria.get("saude")))
            .append(" | avaliados ").append(valorObjeto(memoria.get("totalProjetosAvaliados")))
            .append(" | aprovados ").append(valorObjeto(memoria.get("totalAprovados")))
            .append(" | recusados ").append(valorObjeto(memoria.get("totalRecusados")))
            .append("\n");

        String regrasAprendidas = valorObjeto(memoria.get("regrasConsolidadasPreview"));
        if (!"-".equals(regrasAprendidas)) {
            sb.append("Regras aprendidas: ").append(escaparTelegram(limitar(regrasAprendidas, 420))).append("\n");
        }

        if (foco != null && foco.isAtivo()) {
            sb.append("\nFoco atual: #").append(valorLong(foco.getAcaoId()))
                .append(" | ").append(escaparTelegram(valorTexto(foco.getTituloProjeto())))
                .append("\n");
        } else {
            sb.append("\nFoco atual: SEM_FOCO\n");
        }

        sb.append("\nPendências: ").append(pendencias.size()).append("\n");
        pendencias.stream().limit(5).forEach(acao -> sb
            .append("- #").append(acao.getId())
            .append(" | ").append(valorTexto(acao.getTipo() != null ? acao.getTipo().name() : null))
            .append(" | ").append(escaparTelegram(plataformaLabel(acao)))
            .append(" | ").append(escaparTelegram(limitar(acao.getTituloProjeto(), 80)))
            .append(" | pergunta: ").append(escaparTelegram(limitar(acao.getPerguntaParaUsuario(), 120)))
            .append("\n"));

        if (pendencias.size() > 5) {
            sb.append("+").append(pendencias.size() - 5).append(" pendências no dashboard.\n");
        }
        return sb.toString();
    }

    static String listaAcoes(String titulo, List<AcaoControleDTO> acoes, EnsinoFocoDTO foco, String excedenteLabel) {
        StringBuilder sb = new StringBuilder(titulo).append("\n\n");
        if (foco != null && foco.isAtivo()) {
            sb.append("Foco atual: #").append(foco.getAcaoId())
                .append(" | ").append(valorTexto(foco.getTituloProjeto()))
                .append("\n\n");
        }
        acoes.stream().limit(8).forEach(acao -> sb.append(formatarResumoAcao(acao)).append("\n"));
        if (acoes.size() > 8) {
            sb.append("\n+").append(acoes.size() - 8).append(" ").append(excedenteLabel);
        }
        return sb.toString();
    }

    static String bloqueios(List<MensagemClienteRegistro> bloqueios) {
        StringBuilder sb = new StringBuilder("Últimos bloqueios do guardião\n\n");
        bloqueios.stream().limit(6).forEach(b -> sb
            .append("#").append(b.getId())
            .append(" | ").append(b.getTipo())
            .append(" | projeto ").append(b.getProjetoId() != null ? b.getProjetoId() : "-")
            .append("\nMotivo: ").append(b.getMotivoBloqueio() != null ? b.getMotivoBloqueio() : "-")
            .append("\nTexto: ").append(limitar(b.getTextoPreview(), 180))
            .append("\n\n"));
        return sb.toString();
    }

    static String diagnosticoConversa(Long id, ConversaResumo conversa, List<MensagemConversa> mensagens) {
        boolean autoriaConfiavel = conversa.getIdFreelancer() != null && conversa.getIdCliente() != null;
        StringBuilder sb = new StringBuilder("Diagnóstico da conversa ")
            .append(id)
            .append("\n\nProjeto: ")
            .append(escaparTelegram(valorTexto(conversa.getNomeProjeto())))
            .append("\nCliente: ")
            .append(escaparTelegram(valorTexto(conversa.getNomeCliente())))
            .append(" (#").append(valorLong(conversa.getIdCliente())).append(")")
            .append("\nFreelancer: ")
            .append(escaparTelegram(valorTexto(conversa.getNomeFreelancer())))
            .append(" (#").append(valorLong(conversa.getIdFreelancer())).append(")")
            .append("\nAutoria confiável: ")
            .append(autoriaConfiavel ? "SIM" : "PARCIAL")
            .append("\n\nÚltimas mensagens:\n");

        mensagens.stream()
            .sorted(Comparator.comparing(
                (MensagemConversa msg) -> msg.getDataCriacaoMillis() != null ? msg.getDataCriacaoMillis() : 0L
            ).reversed())
            .limit(8)
            .forEach(mensagem -> sb.append(formatarMensagemDiagnostico(mensagem)).append("\n"));

        return sb.toString();
    }

    private static String plataformaLabel(AcaoControleDTO acao) {
        if (acao.getPlataformaLabel() != null && !acao.getPlataformaLabel().isBlank()) {
            return acao.getPlataformaLabel();
        }
        return PlataformaFreela.normalizar(acao.getPlataforma()).label();
    }
}
