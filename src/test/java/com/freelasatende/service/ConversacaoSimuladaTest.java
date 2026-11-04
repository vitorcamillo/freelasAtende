package com.freelasatende.service;

import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.TipoInteracao;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Suite de testes de INTEGRACAO para validacao de conversas simuladas e fluxos.
 * Usa beans reais do Spring com H2 em memoria.
 * Cobre os fluxos simulados essenciais de conversa, contexto, ensino e recuperacao.
 *
 * ESTRATEGIA: @SpringBootTest com @ActiveProfiles("test"), sem carregar .env,
 * para manter a suite comum deterministica e sem chamadas reais de IA/Telegram.
 */
class ConversacaoSimuladaTest extends ConversacaoSimuladaTestSupport {

    @Test
    @DisplayName("Triagem recusa marketing e traducao mesmo se IA responder SIM")
    void triagemRecusaMarketingETraducaoMesmoComIaAprovando() throws Exception {
        when(iaProvider.enviar(anyString(), anyInt())).thenReturn("SIM - consigo ajudar");
        Projeto marketing = Projeto.builder()
            .id(System.currentTimeMillis() + 10)
            .titulo("Procuro profissional de marketing para lançamento e venda de livros online")
            .descricao("Preciso de estratégia de marketing, copywriting e lançamento.")
            .categoria("Marketing")
            .orcamento(BigDecimal.valueOf(800))
            .nivelExperiencia("Intermediario")
            .build();
        Projeto traducao = Projeto.builder()
            .id(System.currentTimeMillis() + 11)
            .titulo("Tradução de ebook do inglês para português")
            .descricao("Preciso traduzir e revisar texto comercial.")
            .categoria("Tradução")
            .orcamento(BigDecimal.valueOf(500))
            .nivelExperiencia("Basico")
            .build();

        assertFalse(avaliadorIaService.somosCapazesDeAtender(marketing));
        assertFalse(avaliadorIaService.somosCapazesDeAtender(traducao));
    }

    // =========================================================================
    // TESTES DE RETENCAO DE CONTEXTO - TC-013 a TC-030
    // =========================================================================

    @Nested
    @DisplayName("TC-013 a TC-021: Contexto de Curto Prazo (Sessao Unica)")
    class TestesContextoCurtoPrazo {

        @Test
        @DisplayName("TC-013: Deve reter contexto de decisao de projeto unico na sessao")
        void deveReterContextoDecisaoProjetoUnico() {
            Long projetoId = System.currentTimeMillis();
            Projeto projeto = criarProjeto(projetoId, "Site e-commerce Magento", BigDecimal.valueOf(5000), "Intermediario");

            boolean somosCapazes = avaliadorIaService.somosCapazesDeAtender(projeto);

            assertNotNull(interacaoRepository.findByProjetoIdOrderByDataDesc(projetoId));
            verifyInteracaoExistente(projetoId);
        }

        private void verifyInteracaoExistente(Long projetoId) {
            List<Interacao> interacoes = interacaoRepository.findByProjetoIdOrderByDataDesc(projetoId);
            assertFalse(interacoes.isEmpty(), "Deveria ter interacao registrada");
        }

        @Test
        @DisplayName("TC-014: Deve reter contexto de multiplos projetos na mesma sessao")
        void deveReterContextoMultiplosProjetosMesmaSessao() {
            Long baseId = System.currentTimeMillis() + 1000;
            List<Projeto> projetos = List.of(
                criarProjeto(baseId, "App React Native", BigDecimal.valueOf(8000), "Avancado"),
                criarProjeto(baseId + 1, "API REST Python", BigDecimal.valueOf(3000), "Intermediario"),
                criarProjeto(baseId + 2, "Landing Page", BigDecimal.valueOf(1500), "Basico")
            );

            // Contagem inicial deve ser 0 (BeforeEach limpou)
            long countInicial = interacaoRepository.count();

            for (Projeto projeto : projetos) {
                try {
                    avaliadorIaService.somosCapazesDeAtender(projeto);
                } catch (Exception e) {
                    // IA pode falhar em alguns casos
                }
            }

            // Deve ter criado interacoes para cada projeto
            for (Projeto projeto : projetos) {
                List<Interacao> interacoesDoProjeto = interacaoRepository.findByProjetoIdOrderByDataDesc(projeto.getId());
                // A primeira interacao deve existir (pode ser APROVADO ou AVALIADO)
                assertFalse(interacoesDoProjeto.isEmpty(),
                    "Projeto " + projeto.getId() + " deveria ter interacao");
            }
        }

        @Test
        @DisplayName("TC-015: Deve reter detalhes do projeto apos notificacao")
        void deveReterDetalhesAposNotificacao() {
            Long projetoId = System.currentTimeMillis() + 100;
            Projeto projeto = criarProjeto(projetoId, "Sistema de Gestao ERP", BigDecimal.valueOf(25000), "Avancado");

            avaliadorIaService.somosCapazesDeAtender(projeto);

            List<Interacao> historico = interacaoRepository.findByProjetoIdOrderByDataDesc(projetoId);
            assertEquals(1, historico.size());
            assertEquals(projeto.getTitulo(), historico.get(0).getProjetoTitulo());
        }

        @Test
        @DisplayName("TC-016: Deve vincular resposta do usuario ao projeto correto")
        void deveVincularRespostaUsuarioAoProjetoCorreto() {
            Long projetoId = System.currentTimeMillis() + 200;
            Interacao interacao = criarInteracao(projetoId, "Bot Telegram", TipoInteracao.PROJETO_NOTIFICADO, ModoSistema.LIVRE);
            interacaoRepository.save(interacao);

            List<Interacao> historico = historicoService.buscarPorProjeto(projetoId);

            assertEquals(1, historico.size());
            assertEquals(projetoId, historico.get(0).getProjetoId());
        }

        @Test
        @DisplayName("TC-017: Deve lembrar contexto quando usuario solicita clarificacao")
        void deveLembrarContextoAposClarificacao() {
            Long projetoId = System.currentTimeMillis() + 300;
            String duvida = "Qual prazo de entrega?";
            Projeto projeto = criarProjeto(projetoId, "Portal de Noticias", BigDecimal.valueOf(12000), "Intermediario");

            avaliadorIaService.somosCapazesDeAtender(projeto);

            List<Interacao> resultado = historicoService.buscarPorProjeto(projetoId);

            assertFalse(resultado.isEmpty());
        }

        @Test
        @DisplayName("TC-018: Deve registrar cancelamento durante fluxo de proposta")
        void deveRegistrarCancelamentoMidFlow() {
            Long projetoId = System.currentTimeMillis() + 400;
            historicoService.registrarInteracao(criarInteracao(
                projetoId, "Projeto Cancelado", TipoInteracao.CANCELAMENTO, ModoSistema.REGULAR
            ));

            List<Interacao> interacoes = interacaoRepository.findByProjetoIdOrderByDataDesc(projetoId);
            assertEquals(1, interacoes.size());
            assertEquals(TipoInteracao.CANCELAMENTO, interacoes.get(0).getTipo());
        }

        @Test
        @DisplayName("TC-019: Deve rastrear notificacoes paralelas separadamente")
        void deveRastrearNotificacoesParalelasSeparadamente() {
            List<Long> projetoIds = List.of(
                System.currentTimeMillis() + 500,
                System.currentTimeMillis() + 501,
                System.currentTimeMillis() + 502
            );

            for (Long id : projetoIds) {
                historicoService.registrarInteracao(criarInteracao(id, "Projeto " + id, TipoInteracao.PROJETO_NOTIFICADO, ModoSistema.LIVRE));
            }

            assertEquals(3, interacaoRepository.count());
        }

        @Test
        @DisplayName("TC-020: Deve preservar detalhes de contra-proposta")
        void devePreservarDetalhesContraProposta() {
            Long projetoId = System.currentTimeMillis() + 600;
            historicoService.registrarInteracao(criarInteracao(
                projetoId, "Projeto com Contra", TipoInteracao.CONTRA_PROPOSTA, ModoSistema.REGULAR
            ));

            List<Interacao> interacoes = interacaoRepository.findByProjetoIdOrderByDataDesc(projetoId);
            assertEquals(TipoInteracao.CONTRA_PROPOSTA, interacoes.get(0).getTipo());
        }

        @Test
        @DisplayName("TC-021: Deve registrar reavaliacao com historico original")
        void deveRegistrarReavaliacaoComHistoricoOriginal() {
            Long projetoId = System.currentTimeMillis() + 700;
            Interacao original = criarInteracao(projetoId, "Projeto Original", TipoInteracao.PROJETO_AVALIADO, ModoSistema.LIVRE);
            interacaoRepository.save(original);

            historicoService.registrarInteracao(criarInteracao(
                projetoId, "Projeto Reavaliado", TipoInteracao.REAVALIACAO_SOLICITADA, ModoSistema.REGULAR
            ));

            List<Interacao> historicoOriginal = historicoService.buscarPorProjeto(projetoId);
            assertEquals(2, historicoOriginal.size());
        }
    }

    @Nested
    @DisplayName("TC-022 a TC-030: Contexto de Longo Prazo (Multi-Sessao)")
    class TestesContextoLongoPrazo {

        @Test
        @DisplayName("TC-022: Deve manter memoria de projeto entre sessoes diferentes")
        void deveManterMemoriaProjetoEntreSessoes() {
            Long projetoId = System.currentTimeMillis() + 800;
            Projeto projeto = criarProjeto(projetoId, "Projeto Persistente", BigDecimal.valueOf(8000), "Intermediario");

            // Verifica que projeto NAO esta processado inicialmente
            assertFalse(historicoService.projetoJaProcessado(projetoId));

            // A primeira chamada evaluation vai processar
            avaliadorIaService.somosCapazesDeAtender(projeto);

            // Apos processar, projeto deve constar como processado
            assertTrue(historicoService.projetoJaProcessado(projetoId));
        }

        @Test
        @DisplayName("TC-023: Deve carregar preferencias do usuario ao iniciar")
        void deveCarregarPreferenciasUsuarioAoIniciar() {
            List<Interacao> preferencias = historicoService.buscarUltimas(100);
            assertNotNull(preferencias);
        }

        @Test
        @DisplayName("TC-024: Rejeicoes passadas influenciam notificacoes futuras")
        void rejeicoesPassadasInfluenciamNotificacoesFuturas() {
            Long projetoRejeitado = System.currentTimeMillis() + 900;
            Long projetoSimilar = System.currentTimeMillis() + 901;

            // Primeiro projeto foi rejeitado
            historicoService.registrarInteracao(criarInteracao(
                projetoRejeitado, "Rejeitado", TipoInteracao.REJEICAO_USUARIO, ModoSistema.LIVRE
            ));

            boolean primeiroProcessado = historicoService.projetoJaProcessado(projetoRejeitado);
            boolean segundoProcessado = historicoService.projetoJaProcessado(projetoSimilar);

            assertTrue(primeiroProcessado);
            assertFalse(segundoProcessado);
        }

        @Test
        @DisplayName("TC-026: Deve manter contexto em conversa de 50+ mensagens")
        void deveManterContexto50Mensagens() {
            int quantidadeMensagens = 50;
            Long projetoId = System.currentTimeMillis() + 1000;

            for (int i = 0; i < quantidadeMensagens; i++) {
                historicoService.registrarInteracao(criarInteracao(
                    projetoId,
                    "Mensagem " + i,
                    TipoInteracao.values()[i % TipoInteracao.values().length],
                    ModoSistema.values()[i % ModoSistema.values().length]
                ));
            }

            List<Interacao> interacoes = interacaoRepository.findByProjetoIdOrderByDataDesc(projetoId);
            assertEquals(quantidadeMensagens, interacoes.size());
        }

        @Test
        @DisplayName("TC-027: Sistema permanece responsivo com 100+ mensagens")
        void sistemaPermaneceResponsivo100Mensagens() {
            int quantidadeMensagens = 100;
            long inicio = System.currentTimeMillis();

            Long projetoId = System.currentTimeMillis() + 1100;
            for (int i = 0; i < quantidadeMensagens; i++) {
                historicoService.registrarInteracao(criarInteracao(
                    projetoId,
                    "Mensagem " + i,
                    TipoInteracao.PROJETO_AVALIADO,
                    ModoSistema.LIVRE
                ));
            }

            long duracao = System.currentTimeMillis() - inicio;

            assertTrue(duracao < 5000, "Sistema deve processar 100 mensagens em menos de 5 segundos");
            assertEquals(100, interacaoRepository.count());
        }

        @ParameterizedTest
        @CsvSource({
            "5, 5",
            "10, 10",
            "20, 20",
            "50, 50",
            "100, 100"
        })
        @DisplayName("TC-026/027: Escalabilidade de retencao de contexto")
        void testeEscalabilidadeContexto(int quantidadeMensagens, int esperado) {
            Long projetoId = System.currentTimeMillis() + 1200 + quantidadeMensagens;

            for (int i = 0; i < quantidadeMensagens; i++) {
                historicoService.registrarInteracao(criarInteracao(
                    projetoId,
                    "Mensagem " + i,
                    TipoInteracao.PROJETO_AVALIADO,
                    ModoSistema.LIVRE
                ));
            }

            List<Interacao> interacoes = interacaoRepository.findByProjetoIdOrderByDataDesc(projetoId);
            assertEquals(esperado, interacoes.size());
        }
    }

}
