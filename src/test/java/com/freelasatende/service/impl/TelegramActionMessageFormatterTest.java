package com.freelasatende.service.impl;

import com.freelasatende.model.dto.AcaoControleDTO;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TelegramActionMessageFormatterTest {

    @Test
    void resumoEDetalheIncluemLabelDaPlataforma() {
        AcaoControleDTO acao = acaoBase()
            .plataforma(PlataformaFreela.TESTE)
            .plataformaLabel("Teste")
            .build();

        String resumo = TelegramActionMessageFormatter.formatarResumoAcao(acao);
        String detalhe = TelegramActionMessageFormatter.formatarDetalheAcao(acao);

        assertTrue(resumo.contains("Plataforma: Teste"));
        assertTrue(detalhe.contains("Teste"));
        assertFalse(detalhe.contains("Plataforma:"));
    }

    @Test
    void detalheDaAcaoUsaBlocosCompactosSemMetricasTecnicas() {
        String detalhe = TelegramActionMessageFormatter.formatarDetalheAcao(acaoBase().build());

        assertTrue(detalhe.contains("Acao #42 | RESPONDER\\_CONVERSA | PENDENTE"));
        assertTrue(detalhe.contains("Gatilho\n```"));
        assertTrue(detalhe.contains("Resposta ao cliente\n```"));
        assertTrue(detalhe.contains("Historico"));
        assertTrue(detalhe.contains("Leitura"));
        assertFalse(detalhe.contains("Confiança:"));
        assertFalse(detalhe.contains("Qualidade:"));
        assertFalse(detalhe.contains("Naturalidade:"));
        assertFalse(detalhe.contains("Contexto interno:"));
    }

    @Test
    void usaLabelPadraoQuandoPlataformaVeioNula() {
        AcaoControleDTO acao = acaoBase()
            .plataforma(null)
            .plataformaLabel("")
            .build();

        String resumo = TelegramActionMessageFormatter.formatarResumoAcao(acao);

        assertTrue(resumo.contains("Plataforma: 99Freelas"));
    }

    @Test
    void limitarMensagemTelegramFechaBlocoCodigoQuandoCortaDentroDoMarkdown() {
        String texto = "inicio\n```\n" + "x".repeat(5000);

        String limitado = TelegramActionMessageFormatter.limitarMensagemTelegram(texto);

        assertTrue(limitado.contains("\n```"));
        assertEquals(0, contar(limitado, "```") % 2);
    }

    @Test
    void resumoDecodificaEntidadesHtmlDoMarketplace() {
        AcaoControleDTO acao = acaoBase()
            .tituloProjeto("Integra&ccedil;&atilde;o de pagamento e afilia&ccedil;&atilde;o")
            .build();

        String resumo = TelegramActionMessageFormatter.formatarResumoAcao(acao);

        assertTrue(resumo.contains("Integração de pagamento e afiliação"));
        assertFalse(resumo.contains("&ccedil;"));
    }

    private int contar(String texto, String trecho) {
        int total = 0;
        int index = 0;
        while ((index = texto.indexOf(trecho, index)) >= 0) {
            total++;
            index += trecho.length();
        }
        return total;
    }

    private AcaoControleDTO.AcaoControleDTOBuilder acaoBase() {
        return AcaoControleDTO.builder()
            .id(42L)
            .tipo(TipoAcaoPendente.RESPONDER_CONVERSA)
            .status(StatusAcaoPendente.PENDENTE)
            .plataforma(PlataformaFreela.WORKANA)
            .nomeCliente("Guilherme")
            .tituloProjeto("Sistema web")
            .mensagemGatilhoTexto("Preciso de um painel")
            .autoriaConfiavel(true)
            .nivelConfianca("ALTA")
            .scoreConfianca(94)
            .statusGuardiao("PENDENTE")
            .analiseIa("Cliente pediu sistema.")
            .atitudeSugerida("Responder com pergunta objetiva.")
            .textoClientePreview("Consigo te ajudar com esse sistema.");
    }
}
