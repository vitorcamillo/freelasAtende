package com.freelasatende.service;

import com.freelasatende.model.dto.QualidadeRascunhoDTO;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.service.interfaceServico.ModoEnsinamentoPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "scheduling.enabled=false")
@ActiveProfiles("test")
class ModoEnsinamentoPolicyServiceTest {

    @Autowired
    private ModoEnsinamentoPolicyService policyService;

    @Autowired
    private AcaoPendenteRepository acaoPendenteRepository;

    @BeforeEach
    void limpar() {
        acaoPendenteRepository.deleteAll();
    }

    @Test
    @DisplayName("Aprova quando as três regras passam")
    void aprovaQuandoTresRegrasPassam() {
        AcaoPendente acao = salvarAcao(10L, 20L);

        var resultado = policyService.avaliar(
            acao,
            "Oi, consigo seguir por aqui. Voce confirma o prazo ideal?",
            true,
            "BOM_TOM",
            "PENDENTE",
            null,
            qualidadeAprovada()
        );

        assertTrue(resultado.isPermitido());
        assertTrue(resultado.getChecklist().stream().allMatch(r -> "PASSOU".equals(r.getStatus())));
    }

    @Test
    @DisplayName("Bloqueia sem aprovação humana com motivo")
    void bloqueiaSemAprovacaoHumana() {
        AcaoPendente acao = salvarAcao(11L, 21L);

        var resultado = policyService.avaliar(acao, "Oi, tudo bem?", false, "", "PENDENTE", null, qualidadeAprovada());

        assertFalse(resultado.isPermitido());
        assertTrue(resultado.getSinais().contains("feedback_humano_obrigatorio"));
    }

    @Test
    @DisplayName("Bloqueia tom de sistema ou risco de plataforma")
    void bloqueiaTomSistema() {
        AcaoPendente acao = salvarAcao(12L, 22L);
        QualidadeRascunhoDTO qualidade = QualidadeRascunhoDTO.builder()
            .decisaoRecomendada("BLOQUEAR")
            .nivelConfianca("BLOQUEADA")
            .comentario("Tom de sistema")
            .sinais(List.of("tom_sistema_ou_ia"))
            .build();

        var resultado = policyService.avaliar(acao, "Teste técnico automatizado", true, "ROBOTICO", "BLOQUEADA", "Tom de sistema", qualidade);

        assertFalse(resultado.isPermitido());
        assertTrue(resultado.getSinais().contains("guardiao_bloqueou"));
    }

    @Test
    @DisplayName("Aprovação humana direta libera mesmo com juiz de qualidade bloqueante")
    void aprovacaoHumanaLiberaQualidadeBloqueante() {
        AcaoPendente acao = salvarAcao(14L, 25L);
        QualidadeRascunhoDTO qualidade = QualidadeRascunhoDTO.builder()
            .decisaoRecomendada("BLOQUEAR")
            .nivelConfianca("BLOQUEADA")
            .comentario("Mensagem deve ser revisada antes de qualquer aprovacao.")
            .sinais(List.of())
            .build();

        var resultado = policyService.avaliar(
            acao,
            "Oi, consigo seguir por aqui com o que voce pediu.",
            true,
            "BOM_TOM",
            "PENDENTE",
            null,
            qualidade
        );

        assertTrue(resultado.isPermitido());
        assertTrue(resultado.getSinais().contains("qualidade_revisada_por_humano"));
    }

    @Test
    @DisplayName("Sem aprovação humana o juiz de qualidade bloqueante continua barrando")
    void semAprovacaoHumanaQualidadeBloqueia() {
        AcaoPendente acao = salvarAcao(15L, 26L);
        QualidadeRascunhoDTO qualidade = QualidadeRascunhoDTO.builder()
            .decisaoRecomendada("BLOQUEAR")
            .nivelConfianca("BLOQUEADA")
            .comentario("Mensagem deve ser revisada antes de qualquer aprovacao.")
            .sinais(List.of())
            .build();

        var resultado = policyService.avaliar(
            acao,
            "Oi, consigo seguir por aqui com o que voce pediu.",
            false,
            "BOM_TOM",
            "PENDENTE",
            null,
            qualidade
        );

        assertFalse(resultado.isPermitido());
        assertTrue(resultado.getSinais().contains("qualidade_bloqueante"));
    }

    @Test
    @DisplayName("Bloqueia duplicidade de pendência aberta")
    void bloqueiaDuplicidadePendente() {
        AcaoPendente acao = salvarAcao(13L, 23L);
        salvarAcao(13L, 24L);

        var resultado = policyService.avaliar(acao, "Oi, tudo bem?", true, "BOM_TOM", "PENDENTE", null, qualidadeAprovada());

        assertFalse(resultado.isPermitido());
        assertTrue(resultado.getSinais().contains("pendencia_duplicada"));
    }

    @Test
    @DisplayName("Na aprovacao a propria acao (ja APROVADA) nao conta, mas outra aprovacao em curso conta")
    void antiSpamExcluiPropriaAcaoEContaAprovacaoEmCurso() {
        AcaoPendente acao = salvarAcao(31L, 41L);
        acao.setStatus(StatusAcaoPendente.APROVADA);
        acaoPendenteRepository.save(acao);

        var sozinha = policyService.avaliar(acao, "Oi, tudo bem?", true, "BOM_TOM", "PENDENTE", null, qualidadeAprovada());
        assertFalse(sozinha.getSinais().contains("pendencia_duplicada"));

        // Pendente ha 2 h, reivindicada agora: o UPDATE atomico renova a data e ela conta como em curso.
        AcaoPendente outra = salvarAcao(31L, 42L);
        outra.setDataAtualizacao(LocalDateTime.now().minusHours(2));
        acaoPendenteRepository.save(outra);
        acaoPendenteRepository.transicaoAtomicaDeStatus(
            outra.getId(), StatusAcaoPendente.PENDENTE, StatusAcaoPendente.APROVADA, LocalDateTime.now());

        var comOutraEmCurso = policyService.avaliar(acao, "Oi, tudo bem?", true, "BOM_TOM", "PENDENTE", null, qualidadeAprovada());
        assertTrue(comOutraEmCurso.getSinais().contains("pendencia_duplicada"));
    }

    private AcaoPendente salvarAcao(Long projetoId, Long conversaId) {
        return acaoPendenteRepository.save(AcaoPendente.builder()
            .projetoId(projetoId)
            .conversaId(conversaId)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.ENSINAMENTO)
            .tituloProjeto("Projeto")
            .resumo("Resumo")
            .payloadJson("{}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build());
    }

    private QualidadeRascunhoDTO qualidadeAprovada() {
        return QualidadeRascunhoDTO.builder()
            .scoreNaturalidade(90)
            .scoreClareza(90)
            .scoreRisco(0)
            .scoreDuplicidade(0)
            .scoreConfianca(90)
            .nivelConfianca("ALTA")
            .decisaoRecomendada("APROVAR")
            .comentario("Natural")
            .sinais(List.of())
            .build();
    }
}
