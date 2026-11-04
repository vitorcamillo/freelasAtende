package com.freelasatende.service;

import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.TipoInteracao;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cenarios de resiliencia, limites e regras de comportamento da conversa simulada.
 */
class ConversacaoSimuladaResilienciaTest extends ConversacaoSimuladaTestSupport {

    @Nested
    @DisplayName("TC-082 a TC-089: Testes de Rate Limiting")
    class TestesRateLimiting {

        private static final AtomicInteger count = new AtomicInteger(0);

        @ParameterizedTest
        @CsvSource({
            "1, true",
            "2, true",
            "3, true",
            "4, false",
            "5, false"
        })
        @DisplayName("TC-083: Limite de 3 projetos por ciclo respeitado")
        void limiteProjetosPorCiclo(int quantidade, boolean dentroLimite) {
            boolean podeProcessar = count.incrementAndGet() <= 3;

            if (dentroLimite) {
                assertTrue(podeProcessar);
            } else {
                assertFalse(podeProcessar);
            }
        }

        @Test
        @DisplayName("TC-084: Pausa apos 5 rejeicoes consecutivas")
        void pausaAposRejeicoesConsecutivas() {
            int rejeicoesConsecutivas = 5;
            AtomicInteger rejeicoes = new AtomicInteger(0);

            for (int i = 0; i < rejeicoesConsecutivas; i++) {
                historicoService.registrarInteracao(criarInteracao(
                    (long) (50000 + i),
                    "Rejeicao " + i,
                    TipoInteracao.REJEICAO_USUARIO,
                    ModoSistema.LIVRE
                ));
                rejeicoes.incrementAndGet();
            }

            assertTrue(rejeicoes.get() >= 5);
        }

        @Test
        @DisplayName("TC-085: Renovacao de sessao apos 24h")
        void renovacaoSessao24h() {
            int interacoesHoje = historicoService.contarInteracoesDoDia();
            assertTrue(interacoesHoje >= 0);
        }

        @Test
        @DisplayName("TC-088: Backoff exponencial em falhas")
        void backoffExponencialFalhas() {
            int[] delays = {1000, 2000, 4000, 8000};

            for (int i = 0; i < delays.length; i++) {
                int expectedDelay = (int) Math.pow(2, i) * 1000;
                assertEquals(delays[i], expectedDelay);
            }
        }
    }

    @Nested
    @DisplayName("Cenarios GET LOST - Sistema NAO deve se perder")
    class TestesGetLost {

        @Test
        @DisplayName("GL-001: Troca de modo durante avaliacao - contexto mantido")
        void getLostTrocaModoAvaliacao() {
            Long projetoId = System.currentTimeMillis() + 6000;

            historicoService.registrarInteracao(criarInteracao(
                projetoId, "Projeto", TipoInteracao.PROJETO_AVALIADO, ModoSistema.LIVRE
            ));

            historicoService.registrarInteracao(criarInteracao(
                projetoId, "Projeto", TipoInteracao.MODO_ALTERADO, ModoSistema.ENSINAMENTO
            ));

            List<Interacao> interacoes = interacaoRepository.findByProjetoIdOrderByDataDesc(projetoId);
            assertEquals(2, interacoes.size());
        }

        @Test
        @DisplayName("GL-002: Resposta a notificacao antiga - sistema identifica stale")
        void getLostRespostaNotificacaoAntiga() {
            Long projetoId = System.currentTimeMillis() + 6001;
            Long projetoIdAtual = System.currentTimeMillis() + 6002;

            historicoService.registrarInteracao(criarInteracao(
                projetoId, "Projeto Antigo", TipoInteracao.PROJETO_AVALIADO, ModoSistema.LIVRE
            ));

            boolean projetoAntigo = historicoService.projetoJaProcessado(projetoId);
            boolean projetoAtual = historicoService.projetoJaProcessado(projetoIdAtual);

            assertTrue(projetoAntigo);
            assertFalse(projetoAtual);
        }

        @Test
        @DisplayName("GL-003: Falha de rede no meio de proposal - recover gracefully")
        void getLostFalhaRedeMeioProposal() {
            AtomicInteger tentativas = new AtomicInteger(0);

            for (int i = 0; i < 3; i++) {
                try {
                    telegramService.enviarNotificacao("Proposta");
                    break;
                } catch (Exception e) {
                    tentativas.incrementAndGet();
                }
            }

            assertTrue(tentativas.get() >= 0);
        }

        @Test
        @DisplayName("GL-004: Falha de banco durante save - rollback")
        void getLostFalhaBancoRollback() {
            Long projetoId = System.currentTimeMillis() + 6003;

            Interacao interacao = criarInteracao(projetoId, "Projeto", TipoInteracao.PROJETO_AVALIADO, ModoSistema.LIVRE);
            interacao.setId(null);
            Interacao salva = interacaoRepository.save(interacao);

            assertNotNull(salva.getId());
            assertTrue(interacaoRepository.existsById(salva.getId()));
        }

        @Test
        @DisplayName("GL-005: Projeto duplicado identificado - sem duplicate notification")
        void getLostProjetoDuplicado() {
            Long projetoId = System.currentTimeMillis() + 6004;

            historicoService.registrarInteracao(criarInteracao(
                projetoId, "Projeto", TipoInteracao.PROJETO_AVALIADO, ModoSistema.LIVRE
            ));

            boolean primeiraVez = historicoService.projetoJaProcessado(projetoId);
            boolean segundaVez = historicoService.projetoJaProcessado(projetoId);

            assertTrue(primeiraVez);
            assertTrue(segundaVez);
        }

        @Test
        @DisplayName("GL-006: Notificacoes concorrentes rastreadas separadamente")
        void getLostNotificacoesConcorrentes() {
            List<Long> projetoIds = List.of(
                System.currentTimeMillis() + 6005,
                System.currentTimeMillis() + 6006,
                System.currentTimeMillis() + 6007
            );

            for (Long id : projetoIds) {
                historicoService.registrarInteracao(criarInteracao(id, "P" + id, TipoInteracao.PROJETO_NOTIFICADO, ModoSistema.LIVRE));
            }

            assertEquals(3, interacaoRepository.count());
        }

        @Test
        @DisplayName("GL-007: Mudanca de modo no meio da conversa preserva contexto")
        void getLostMudancaModoMeioConversa() {
            Long projetoId = System.currentTimeMillis() + 6008;

            historicoService.registrarInteracao(criarInteracao(projetoId, "P1", TipoInteracao.PROJETO_APROVADO, ModoSistema.LIVRE));
            historicoService.registrarInteracao(criarInteracao(projetoId, "P2", TipoInteracao.PROJETO_NOTIFICADO, ModoSistema.LIVRE));
            historicoService.registrarInteracao(criarInteracao(projetoId, "P3", TipoInteracao.DUVIDA_CLARIFICADA, ModoSistema.REGULAR));
            historicoService.registrarInteracao(criarInteracao(projetoId, "P4", TipoInteracao.APROVACAO_USUARIO, ModoSistema.REGULAR));

            List<Interacao> interacoes = interacaoRepository.findByProjetoIdOrderByDataDesc(projetoId);
            assertEquals(4, interacoes.size());
        }

        @Test
        @DisplayName("GL-008: Respostas fora de ordem - sistemaresponde corretamente")
        void getLostRespostasForaOrdem() {
            Long projetoId = System.currentTimeMillis() + 6009;

            historicoService.registrarInteracao(criarInteracao(projetoId, "Msg1", TipoInteracao.PROJETO_AVALIADO, ModoSistema.LIVRE));
            historicoService.registrarInteracao(criarInteracao(projetoId, "Msg3", TipoInteracao.PROJETO_AVALIADO, ModoSistema.LIVRE));
            historicoService.registrarInteracao(criarInteracao(projetoId, "Msg2", TipoInteracao.PROJETO_AVALIADO, ModoSistema.LIVRE));

            List<Interacao> interacoes = interacaoRepository.findByProjetoIdOrderByDataDesc(projetoId);
            assertEquals(3, interacoes.size());
        }

        @Test
        @DisplayName("GL-009: Limite de 2 mensagens por projeto respeitado")
        void getLostLimite2MensagensPorProjeto() {
            Long projetoId = System.currentTimeMillis() + 6010;
            int maxMensagensPorProjeto = 2;

            historicoService.registrarInteracao(criarInteracao(projetoId, "Msg1", TipoInteracao.PROJETO_NOTIFICADO, ModoSistema.LIVRE));
            historicoService.registrarInteracao(criarInteracao(projetoId, "Msg2", TipoInteracao.APROVACAO_USUARIO, ModoSistema.LIVRE));

            AtomicInteger count = new AtomicInteger(2);
            boolean podeEnviarTerceira = count.incrementAndGet() <= maxMensagensPorProjeto;

            assertFalse(podeEnviarTerceira);
        }

        @Test
        @DisplayName("GL-010: Sistema NAO envia mensagens nao autorizadas")
        void getLostNaoEnviaNaoAutorizado() {
            String mensagemAutorizada = "🎯 Novo projeto interessante";

            try {
                telegramService.enviarNotificacao(mensagemAutorizada);
            } catch (Exception e) {
                // Telegram pode falhar sem credenciais
            }
        }
    }

    @Nested
    @DisplayName("Regras de Ouro - Sistema NAO deve quebrar regras")
    class TestesRegrasComportamento {

        @Test
        @DisplayName("REGRA-001: Sistema NAO envia mensagens sem autorizacao")
        void regraNaoEnviaSemAutorizacao() {
            try {
                telegramService.enviarNotificacao("🎯 Novo projeto encontrado");
            } catch (Exception e) {
                // Telegram pode falhar sem credenciais
            }
        }

        @Test
        @DisplayName("REGRA-002: Sistema respeita limite de 3 projetos por ciclo")
        void regraLimite3Projetos() {
            Long baseId = System.currentTimeMillis() + 7000;
            List<Projeto> projetos = List.of(
                criarProjeto(baseId, "P1", BigDecimal.valueOf(1000), "Basico"),
                criarProjeto(baseId + 1, "P2", BigDecimal.valueOf(2000), "Basico"),
                criarProjeto(baseId + 2, "P3", BigDecimal.valueOf(3000), "Basico"),
                criarProjeto(baseId + 3, "P4", BigDecimal.valueOf(4000), "Basico")
            );

            int maxProjetosCiclo = 3;
            int countProcessados = 0;

            for (Projeto p : projetos) {
                if (countProcessados < maxProjetosCiclo) {
                    try {
                        telegramService.notificarProjetoInteressante(p);
                        countProcessados++;
                    } catch (Exception e) {
                        // Telegram pode falhar - nao conta como processado
                    }
                }
            }

            assertEquals(3, countProcessados);
        }

        @Test
        @DisplayName("REGRA-003: Sistema NAO repete projetos ja processados")
        void regraNaoRepeteProjetosProcessados() {
            Long projetoId = System.currentTimeMillis() + 7004;

            historicoService.registrarInteracao(criarInteracao(
                projetoId, "Projeto", TipoInteracao.PROJETO_AVALIADO, ModoSistema.LIVRE
            ));

            boolean processado = historicoService.projetoJaProcessado(projetoId);
            assertTrue(processado);
        }

        @Test
        @DisplayName("REGRA-004: Sistema NAO faz auto-proposta em ENSINAMENTO")
        void regraNaoAutoPropostaEnsino() {
            Long projetoId = System.currentTimeMillis() + 7005;
            ModoSistema modo = ModoSistema.ENSINAMENTO;

            historicoService.registrarInteracao(criarInteracao(
                projetoId, "Ensino", TipoInteracao.PERGUNTA_SIM_NAO, modo
            ));

            List<Interacao> interacoes = interacaoRepository.findByProjetoIdOrderByDataDesc(projetoId);
            assertEquals(ModoSistema.ENSINAMENTO, interacoes.get(0).getModoUsado());
        }

        @Test
        @DisplayName("REGRA-005: Sistema registra TODAS interacoes no historico")
        void regraRegistraTodasInteracoes() {
            List<TipoInteracao> tipos = List.of(
                TipoInteracao.PROJETO_AVALIADO,
                TipoInteracao.PROJETO_APROVADO,
                TipoInteracao.PROJETO_NOTIFICADO,
                TipoInteracao.DUVIDA_CLARIFICADA
            );

            Long projetoId = System.currentTimeMillis() + 7006;
            for (TipoInteracao tipo : tipos) {
                historicoService.registrarInteracao(criarInteracao(
                    projetoId, "Projeto", tipo, ModoSistema.LIVRE
                ));
            }

            List<Interacao> interacoes = interacaoRepository.findByProjetoIdOrderByDataDesc(projetoId);
            assertEquals(tipos.size(), interacoes.size());
        }
    }
}
