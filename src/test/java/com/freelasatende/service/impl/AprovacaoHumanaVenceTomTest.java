package com.freelasatende.service.impl;

import com.freelasatende.model.dto.PoliticaOperacionalResultadoDTO;
import com.freelasatende.model.dto.QualidadeRascunhoDTO;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.repository.AcaoPendenteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixa a autoridade do operador sobre o juiz automatico de TOM.
 *
 * <p>Caso real: ao clicar em "Bom tom" ou "Escopo claro" no Telegram, a acao
 * voltava com "Acao bloqueada pelas 3 regras: Tom artificial, tecnico ou de
 * sistema detectado". O operador leu a mensagem e discordou do juiz, mas
 * qualquer bloqueio do guardiao vencia a decisao humana.</p>
 *
 * <p>Tom e estilo e cede a aprovacao humana. Conteudo inseguro, spam e
 * duplicidade sao seguranca/operacao e continuam fatais.</p>
 */
class AprovacaoHumanaVenceTomTest {

    private final ModoEnsinamentoPolicyServiceImpl policy =
        new ModoEnsinamentoPolicyServiceImpl(Mockito.mock(AcaoPendenteRepository.class));

    /**
     * Acao sem projeto/conversa: a regra anti-spam nao tem duplicidade para
     * contar, entao o resultado isola a regra de naturalidade sob teste.
     */
    private AcaoPendente acaoSemDuplicidade() {
        return new AcaoPendente();
    }

    private PoliticaOperacionalResultadoDTO avaliar(String statusGuardiao, boolean aprovacaoHumana) {
        return policy.avaliar(
            acaoSemDuplicidade(),
            "Oi! Vi seu projeto e queria entender melhor o escopo antes de propor prazo.",
            aprovacaoHumana,
            aprovacaoHumana ? "BOM_TOM" : null,
            statusGuardiao,
            "motivo do guardiao",
            QualidadeRascunhoDTO.builder().sinais(List.of()).build()
        );
    }

    @Test
    @DisplayName("Bom tom libera bloqueio de tom do guardiao")
    void aprovacaoHumanaVenceBloqueioDeTom() {
        assertTrue(avaliar("BLOQUEADO_TOM_SISTEMA", true).isPermitido());
    }

    @Test
    @DisplayName("Sem aprovacao humana, bloqueio de tom continua valendo")
    void semAprovacaoHumanaTomContinuaBloqueado() {
        assertFalse(avaliar("BLOQUEADO_TOM_SISTEMA", false).isPermitido());
    }

    @Test
    @DisplayName("Conteudo inseguro nao cede a aprovacao humana")
    void conteudoInseguroContinuaFatal() {
        assertFalse(avaliar("BLOQUEADO_CONTEUDO_INSEGURO", true).isPermitido());
    }

    @Test
    @DisplayName("Spam e duplicidade nao cedem a aprovacao humana")
    void spamEDuplicidadeContinuamFatais() {
        assertFalse(avaliar("BLOQUEADO_SPAM", true).isPermitido());
        assertFalse(avaliar("BLOQUEADO_DUPLICADO", true).isPermitido());
    }

    @Test
    @DisplayName("Guardiao liberado segue permitido")
    void guardiaoLiberadoSeguePermitido() {
        assertTrue(avaliar("PERMITIDO", true).isPermitido());
    }
}
