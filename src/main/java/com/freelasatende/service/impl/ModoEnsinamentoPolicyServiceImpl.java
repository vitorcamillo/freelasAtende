package com.freelasatende.service.impl;

import com.freelasatende.model.dto.PoliticaOperacionalResultadoDTO;
import com.freelasatende.model.dto.QualidadeRascunhoDTO;
import com.freelasatende.model.dto.RegraOperacionalDTO;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.service.interfaceServico.ModoEnsinamentoPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ModoEnsinamentoPolicyServiceImpl implements ModoEnsinamentoPolicyService {

    /** APROVADA mais antiga que isto e residuo de processo interrompido, nao envio em curso. */
    private static final long JANELA_APROVACAO_EM_CURSO_MINUTOS = 30;


    private static final String PASSOU = "PASSOU";
    private static final String BLOQUEOU = "BLOQUEOU";

    private final AcaoPendenteRepository acaoPendenteRepository;

    @Override
    public PoliticaOperacionalResultadoDTO avaliar(
        AcaoPendente acao,
        String textoCliente,
        boolean aprovacaoHumana,
        String motivoAprendizado,
        String statusGuardiao,
        String motivoBloqueio,
        QualidadeRascunhoDTO qualidade
    ) {
        List<RegraOperacionalDTO> checklist = new ArrayList<>();
        List<String> sinais = new ArrayList<>();

        checklist.add(regraAprovacao(aprovacaoHumana, motivoAprendizado, sinais));
        checklist.add(regraNaturalidade(acao, textoCliente, aprovacaoHumana, statusGuardiao, motivoBloqueio, qualidade, sinais));
        checklist.add(regraAntiSpam(acao, sinais));

        boolean permitido = checklist.stream().allMatch(r -> PASSOU.equals(r.getStatus()));
        return PoliticaOperacionalResultadoDTO.builder()
            .permitido(permitido)
            .status(permitido ? PASSOU : BLOQUEOU)
            .motivo(permitido ? "Três regras operacionais aprovadas" : primeiroMotivoBloqueio(checklist))
            .checklist(checklist)
            .sinais(sinais)
            .build();
    }

    private RegraOperacionalDTO regraAprovacao(boolean aprovacaoHumana, String motivoAprendizado, List<String> sinais) {
        boolean motivoValido = motivoAprendizado != null && !motivoAprendizado.isBlank();
        boolean passou = aprovacaoHumana && motivoValido;
        if (!passou) {
            sinais.add("feedback_humano_obrigatorio");
        }
        return regra(
            "APROVACAO_HUMANA",
            "Aprovação humana",
            passou,
            passou ? "Ação tem decisão humana com motivo de aprendizado" : "Escolha um motivo antes de aprovar, recusar ou reescrever"
        );
    }

    private RegraOperacionalDTO regraNaturalidade(
        AcaoPendente acao,
        String textoCliente,
        boolean aprovacaoHumana,
        String statusGuardiao,
        String motivoBloqueio,
        QualidadeRascunhoDTO qualidade,
        List<String> sinais
    ) {
        if (acao != null && acao.getTipo() == TipoAcaoPendente.CANCELAR_PROPOSTA) {
            return regra("NATURALIDADE_SEGURANCA", "Naturalidade e segurança", true,
                "Cancelamento não envia texto ao cliente");
        }

        boolean textoValido = textoCliente != null && !textoCliente.isBlank();
        boolean guardiaoLiberou = statusGuardiao == null
            || statusGuardiao.isBlank()
            || "PENDENTE".equalsIgnoreCase(statusGuardiao)
            || "ENVIADA".equalsIgnoreCase(statusGuardiao)
            || "PERMITIDO".equalsIgnoreCase(statusGuardiao);
        // O guardiao bloqueia por motivos de naturezas diferentes. BLOQUEADO_TOM_SISTEMA
        // e julgamento de ESTILO (soa artificial/tecnico), e o operador que clica em
        // "Bom tom" ou "Escopo claro" ja leu a mensagem e discordou do juiz automatico
        // -- a decisao dele e a de maior autoridade sobre tom. Os demais bloqueios
        // (conteudo inseguro, spam, duplicidade) sao seguranca/operacao e continuam
        // fatais. Antes qualquer bloqueio do guardiao vencia a aprovacao humana, o que
        // deixava mensagens aprovadas presas em "Acao bloqueada pelas 3 regras".
        boolean bloqueioApenasDeTom = "BLOQUEADO_TOM_SISTEMA".equalsIgnoreCase(statusGuardiao);
        boolean tomLiberadoPorHumano = bloqueioApenasDeTom && aprovacaoHumana;
        boolean guardiaoOk = guardiaoLiberou || tomLiberadoPorHumano;
        boolean qualidadeBloqueante = qualidade != null && qualidade.bloqueante();
        // Regra DURA de plataforma (exfiltrar contato/pagamento/negociação para fora):
        // é segurança, não gosto. Aprovação humana não vence — o guardião também
        // revalida o texto na aprovação e vem como BLOQUEADO_*.
        boolean regraDuraPlataforma = qualidade != null
            && qualidade.getSinais() != null
            && qualidade.getSinais().contains("risco_regra_plataforma");
        // Aprovação humana explícita JÁ é a revisão exigida pelo juiz de qualidade
        // (naturalidade/clareza/tom): o operador escolheu a resposta direto no botão,
        // então o juiz automático vira aviso para esses casos. Regra dura não.
        boolean qualidadeOk = !qualidadeBloqueante || (aprovacaoHumana && !regraDuraPlataforma);
        boolean passou = textoValido && guardiaoOk && qualidadeOk;

        if (!textoValido) {
            sinais.add("sem_texto_cliente");
        }
        if (!guardiaoOk) {
            sinais.add("guardiao_bloqueou");
        } else if (tomLiberadoPorHumano) {
            sinais.add("tom_revisado_por_humano");
        }
        if (qualidadeBloqueante) {
            sinais.add(regraDuraPlataforma ? "regra_dura_plataforma"
                : (aprovacaoHumana ? "qualidade_revisada_por_humano" : "qualidade_bloqueante"));
        }

        String motivo = "Texto natural e sem risco de plataforma";
        if (!textoValido) {
            motivo = "Texto ao cliente está vazio";
        } else if (!guardiaoOk) {
            motivo = motivoBloqueio != null && !motivoBloqueio.isBlank()
                ? motivoBloqueio
                : "Guardião pediu revisão da mensagem";
        } else if (qualidadeBloqueante && regraDuraPlataforma) {
            motivo = "Conteúdo tenta levar contato, pagamento ou negociação para fora da plataforma; aprovação humana não vence regra de segurança";
        } else if (qualidadeBloqueante && aprovacaoHumana) {
            motivo = "Qualidade revisada e aprovada manualmente pelo operador";
        } else if (qualidadeBloqueante) {
            motivo = qualidade != null ? qualidade.getComentario() : "Qualidade operacional pediu revisão da mensagem";
        }

        return regra("NATURALIDADE_SEGURANCA", "Naturalidade e segurança", passou, motivo);
    }

    private RegraOperacionalDTO regraAntiSpam(AcaoPendente acao, List<String> sinais) {
        if (acao == null) {
            sinais.add("acao_inexistente");
            return regra("ANTI_SPAM_DUPLICIDADE", "Anti-spam e duplicidade", false, "Ação pendente não encontrada");
        }
        // Conta as OUTRAS acoes abertas (exclui a propria, que na aprovacao ja esta APROVADA)
        // e inclui as APROVADAS recentes, que estao em processamento por outra aprovacao.
        Long idPropria = acao.getId() != null ? acao.getId() : -1L;
        LocalDateTime aprovadaDesde = LocalDateTime.now().minusMinutes(JANELA_APROVACAO_EM_CURSO_MINUTOS);
        long outrasProjeto = acao.getProjetoId() != null
            ? acaoPendenteRepository.contarOutrasAbertasDoProjeto(
                acao.getPlataforma(), acao.getProjetoId(), idPropria, aprovadaDesde)
            : 0;
        long outrasConversa = acao.getConversaId() != null
            ? acaoPendenteRepository.contarOutrasAbertasDaConversa(
                acao.getPlataforma(), acao.getConversaId(), idPropria, aprovadaDesde)
            : 0;
        boolean projetoOk = outrasProjeto == 0;
        boolean conversaOk = outrasConversa == 0;
        boolean passou = projetoOk && conversaOk;
        if (!passou) {
            sinais.add("pendencia_duplicada");
        }
        return regra(
            "ANTI_SPAM_DUPLICIDADE",
            "Anti-spam e duplicidade",
            passou,
            passou ? "Não há outra pendência aberta para este contexto" : "Existe mais de uma pendência aberta para o mesmo projeto ou conversa"
        );
    }

    private RegraOperacionalDTO regra(String codigo, String nome, boolean passou, String motivo) {
        return RegraOperacionalDTO.builder()
            .codigo(codigo)
            .nome(nome)
            .status(passou ? PASSOU : BLOQUEOU)
            .motivo(motivo)
            .build();
    }

    private String primeiroMotivoBloqueio(List<RegraOperacionalDTO> checklist) {
        return checklist.stream()
            .filter(r -> BLOQUEOU.equals(r.getStatus()))
            .map(RegraOperacionalDTO::getMotivo)
            .findFirst()
            .orElse("Política operacional bloqueou a ação");
    }
}
