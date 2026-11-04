package com.freelasatende.service;

import com.freelasatende.model.dto.MensagemConversa;
import com.freelasatende.model.enums.PlataformaFreela;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.service.interfaceServico.AutenticacaoService;
import com.freelasatende.service.interfaceServico.MensagemClienteGuardService;
import com.freelasatende.service.interfaceServico.ScraperService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest(properties = "scheduling.enabled=false")
@EnabledIfSystemProperty(named = "real.write.enabled", matches = "true")
@DisplayName("99Freelas real: operações de escrita")
class FreelasWriteOperationsRealIT {

    @Autowired
    private ScraperService scraperService;

    @Autowired
    private AutenticacaoService autenticacaoService;

    @Autowired
    private MensagemClienteGuardService mensagemClienteGuardService;

    @BeforeEach
    void setup() {
        autenticacaoService.carregarCookies();
        assumeTrue(scraperService.verificarLogin(), "Sessão inválida para testes de escrita reais.");
    }

    @Test
    @DisplayName("Envia pergunta real para projeto informado")
    void enviaPerguntaReal() {
        Long projetoId = getLongProperty("real.test.project.id");
        assumeTrue(projetoId != null && projetoId > 0, "Defina -Dreal.test.project.id para executar envio de pergunta real.");

        String mensagem = textoObrigatorio("real.test.pergunta.texto");
        validarTextoReal(mensagem, projetoId, null, TipoAcaoPendente.ENVIAR_PERGUNTA);

        assertTrue(scraperService.enviarPergunta(projetoId, mensagem), "Pergunta real nao foi enviada.");
    }

    @Test
    @DisplayName("Envia mensagem real em conversa informada")
    void enviaMensagemConversaReal() {
        Long conversaId = getLongProperty("real.test.conversa.id");
        assumeTrue(conversaId != null && conversaId > 0, "Defina -Dreal.test.conversa.id para executar envio de mensagem real.");

        String texto = textoObrigatorio("real.test.conversa.texto");
        validarTextoReal(texto, null, conversaId, TipoAcaoPendente.RESPONDER_CONVERSA);

        MensagemConversa resposta = scraperService.enviarMensagemConversa(conversaId, texto);
        assertNotNull(resposta, "Resposta nula ao enviar mensagem real.");
        assertNotNull(resposta.getId(), "Mensagem real enviada sem ID de retorno.");
    }

    private Long getLongProperty(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String textoObrigatorio(String key) {
        String texto = System.getProperty(key);
        assumeTrue(texto != null && !texto.isBlank(),
            "Defina -" + key + " com texto humano explicito. Nao existe texto padrao para escrita real.");
        return texto.trim();
    }

    private void validarTextoReal(String texto, Long projetoId, Long conversaId, TipoAcaoPendente tipo) {
        MensagemClienteGuardService.ContextoMensagemCliente contexto =
            new MensagemClienteGuardService.ContextoMensagemCliente(
                PlataformaFreela.FREELAS_99,
                projetoId,
                conversaId,
                tipo,
                false
            );
        MensagemClienteGuardService.ValidacaoRascunho validacao =
            mensagemClienteGuardService.validarRascunho(contexto, texto);
        assumeTrue(validacao.permitido(), "Texto real bloqueado pelo guardiao: " + validacao.motivo());
    }
}
