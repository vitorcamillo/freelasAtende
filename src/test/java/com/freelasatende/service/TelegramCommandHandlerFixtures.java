package com.freelasatende.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.model.dto.AcaoControleDTO;
import com.freelasatende.model.dto.ConversaMensagemControleDTO;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;

import java.time.LocalDateTime;
import java.util.List;

final class TelegramCommandHandlerFixtures {

    private TelegramCommandHandlerFixtures() {
    }

    static JsonNode updateComCallback(String callbackId, String data, String chatId) throws Exception {
        String json = """
            {
              "ok": true,
              "result": [
                {
                  "update_id": 1000,
                  "callback_query": {
                    "id": "%s",
                    "data": "%s",
                    "message": {
                      "message_id": 321,
                      "chat": {
                        "id": "%s"
                      }
                    }
                  }
                }
              ]
            }
            """.formatted(callbackId, data, chatId);
        return new ObjectMapper().readTree(json);
    }

    static JsonNode updateComMensagem(String texto, String chatId) throws Exception {
        String json = """
            {
              "ok": true,
              "result": [
                {
                  "update_id": 1001,
                  "message": {
                    "text": "%s",
                    "chat": {
                      "id": "%s"
                    }
                  }
                }
              ]
            }
            """.formatted(texto, chatId);
        return new ObjectMapper().readTree(json);
    }

    static AcaoControleDTO acaoControle(Long id) {
        return AcaoControleDTO.builder()
            .id(id)
            .projetoId(99L)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Landing page")
            .resumoInterno("Confirmar prazo antes de responder.")
            .textoClientePreview("Oi, consegue confirmar o prazo ideal?")
            .entendimentoIa("Cliente quer prazo antes de decidir.")
            .analiseIa("Li o contexto e falta prazo desejado antes de uma resposta segura.")
            .opcoesIa(List.of("perguntar prazo", "rascunhar proposta curta"))
            .atitudeSugerida("Conversar com voce antes de perguntar ao cliente.")
            .conversaEnsinoObrigatoria(true)
            .conversaEnsinoRespondida(false)
            .informacoesConfirmadas(List.of("cliente pediu prazo"))
            .informacoesPendentes(List.of("prazo ideal", "urgencia"))
            .sabeFazer(List.of("perguntar sem prometer prazo"))
            .duvidasInternas(List.of("se devemos perguntar prazo ou propor estimativa"))
            .perguntasParaCliente(List.of("Qual prazo ideal para entrega?"))
            .riscosDecisao(List.of("responder prazo sem base"))
            .proximaAcaoSugerida("Salvar orientacao de ensino antes de aprovar.")
            .perguntaParaUsuario("Quer que eu pergunte prazo ou ja proponha uma estimativa?")
            .regraAprendizadoSugerida("Em projetos de landing page, confirmar prazo antes de estimar entrega.")
            .mensagemGatilhoId(501L)
            .mensagemGatilhoTexto("Consegue me passar uma ideia de prazo?")
            .mensagemGatilhoAutor("CLIENTE")
            .mensagemGatilhoNome("Cliente")
            .idCliente(1613360L)
            .nomeCliente("Cliente")
            .idFreelancer(269409L)
            .nomeFreelancer("Vitor Camillo")
            .ultimasMensagensMinhas(List.of(ConversaMensagemControleDTO.builder()
                .id(500L)
                .autor("EU")
                .nome("Vitor Camillo")
                .texto("Oi, já vi seu projeto e estou avaliando.")
                .dataCriacaoMillis(1000L)
                .build()))
            .ultimasMensagensCliente(List.of(ConversaMensagemControleDTO.builder()
                .id(501L)
                .autor("CLIENTE")
                .nome("Cliente")
                .texto("Consegue me passar uma ideia de prazo?")
                .dataCriacaoMillis(2000L)
                .build()))
            .mensagensIgnoradasResumo(List.of("1 mensagem minha detectada: entra no contexto, mas nao aciona resposta."))
            .autoriaConfiavel(true)
            .motivoAutoria("Autoria definida por idFreelancer/idCliente da conversa.")
            .statusGuardiao("PENDENTE")
            .nivelConfianca("ALTA")
            .scoreNaturalidade(96)
            .scoreClareza(98)
            .scoreRisco(0)
            .scoreDuplicidade(0)
            .scoreConfianca(94)
            .decisaoQualidade("APROVAR")
            .comentarioQualidade("Rascunho natural.")
            .sinaisQualidade(List.of())
            .sinaisRisco(List.of())
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
    }
}
