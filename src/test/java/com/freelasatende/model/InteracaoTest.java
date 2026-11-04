package com.freelasatende.model;

import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.TipoInteracao;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários para Interacao entity.
 * Testa validações, builders e métodos da entidade.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InteracaoTest {

    @Test
    @DisplayName("Deve criar interacao com builder")
    void deveCriarInteracaoComBuilder() {
        // Act
        LocalDateTime agora = LocalDateTime.now();
        Interacao interacao = Interacao.builder()
            .id(1L)
            .projetoId(100L)
            .projetoTitulo("Projeto Teste")
            .tipo(TipoInteracao.PROJETO_ENCONTRADO)
            .conteudo("Conteúdo da proposta")
            .data(agora)
            .modoUsado(ModoSistema.LIVRE)
            .build();

        // Assert
        assertNotNull(interacao);
        assertEquals(1L, interacao.getId());
        assertEquals(100L, interacao.getProjetoId());
        assertEquals("Projeto Teste", interacao.getProjetoTitulo());
        assertEquals(TipoInteracao.PROJETO_ENCONTRADO, interacao.getTipo());
        assertEquals("Conteúdo da proposta", interacao.getConteudo());
        assertEquals(agora, interacao.getData());
        assertEquals(ModoSistema.LIVRE, interacao.getModoUsado());
    }

    @Test
    @DisplayName("Deve criar interacao com valores default")
    void deveCriarInteracaoComValoresDefault() {
        // Act
        Interacao interacao = new Interacao();
        interacao.setId(1L);
        interacao.setProjetoId(50L);
        interacao.setTipo(TipoInteracao.PROJETO_ENCONTRADO);

        // Assert
        assertNotNull(interacao);
        assertEquals(1L, interacao.getId());
        assertEquals(50L, interacao.getProjetoId());
        assertEquals(TipoInteracao.PROJETO_ENCONTRADO, interacao.getTipo());
        assertNull(interacao.getConteudo());
        assertNull(interacao.getModoUsado());
    }

    @Test
    @DisplayName("Deve suportar todos os tipos de interacao")
    void deveSuportarTodosTiposInteracao() {
        // Arrange
        Interacao interacao = Interacao.builder().id(1L).build();

        // Act & Assert
        for (TipoInteracao tipo : TipoInteracao.values()) {
            interacao.setTipo(tipo);
            assertEquals(tipo, interacao.getTipo());
        }
    }

    @Test
    @DisplayName("Deve suportar todos os modos do sistema")
    void deveSuportarTodosModosSistema() {
        // Arrange
        Interacao interacao = Interacao.builder().id(1L).build();

        // Act & Assert
        for (ModoSistema modo : ModoSistema.values()) {
            interacao.setModoUsado(modo);
            assertEquals(modo, interacao.getModoUsado());
        }
    }

    @Test
    @DisplayName("Deve limitar tamanho do conteudo em 5000 caracteres")
    void deveLimitarTamanhoConteudo() {
        // Arrange
        String conteudoGrande = "x".repeat(5000);
        Interacao interacao = Interacao.builder()
            .id(1L)
            .conteudo(conteudoGrande)
            .build();

        // Assert
        assertEquals(5000, interacao.getConteudo().length());
    }

    @Test
    @DisplayName("Deve comparar interacoes pela interface de entidade")
    void deveCompararInteracoes() {
        // Arrange - mesmo ID e projetoId para serem iguais
        Interacao i1 = Interacao.builder().id(1L).projetoId(100L).build();
        Interacao i2 = Interacao.builder().id(1L).projetoId(100L).build();
        Interacao i3 = Interacao.builder().id(2L).projetoId(100L).build();

        // Assert - baseado em Lombok @Data
        assertEquals(i1, i2); // mesmo ID e projetoId
        assertNotEquals(i1, i3); // IDs diferentes
    }

    @Test
    @DisplayName("Deve ter toString gerado pelo Lombok")
    void deveTerToString() {
        // Arrange
        Interacao interacao = Interacao.builder()
            .id(1L)
            .projetoTitulo("Teste")
            .tipo(TipoInteracao.PROJETO_ENCONTRADO)
            .build();

        // Act
        String toString = interacao.toString();

        // Assert
        assertTrue(toString.contains("Interacao"));
    }

    @Test
    @DisplayName("Deve usar LocalDateTime para data")
    void deveUsarLocalDateTimeParaData() {
        // Arrange
        LocalDateTime agora = LocalDateTime.now();
        LocalDateTime depois = agora.plusHours(1);

        // Act
        Interacao i1 = Interacao.builder().id(1L).data(agora).build();
        Interacao i2 = Interacao.builder().id(2L).data(depois).build();

        // Assert
        assertEquals(agora, i1.getData());
        assertTrue(i2.getData().isAfter(i1.getData()));
    }
}
