package com.freelasatende.model;

import com.freelasatende.model.entity.Cliente;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários para Cliente entity.
 * Testa validações, builders e métodos da entidade.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClienteTest {

    @Test
    @DisplayName("Deve criar cliente com builder")
    void deveCriarClienteComBuilder() {
        // Act
        Cliente cliente = Cliente.builder()
            .id(1L)
            .nome("João Silva")
            .urlFoto("http://exemplo.com/foto.jpg")
            .mediaNota(4.5)
            .projetosConcluidos(10)
            .projetosPublicados(15)
            .projetosEmAndamento(2)
            .projetosCancelados(1)
            .recomendacoes(5)
            .identidadeVerificada(true)
            .localizacao("São Paulo, SP")
            .build();

        // Assert
        assertNotNull(cliente);
        assertEquals(1L, cliente.getId());
        assertEquals("João Silva", cliente.getNome());
        assertEquals("http://exemplo.com/foto.jpg", cliente.getUrlFoto());
        assertEquals(4.5, cliente.getMediaNota());
        assertEquals(10, cliente.getProjetosConcluidos());
        assertEquals(15, cliente.getProjetosPublicados());
        assertEquals(2, cliente.getProjetosEmAndamento());
        assertEquals(1, cliente.getProjetosCancelados());
        assertEquals(5, cliente.getRecomendacoes());
        assertTrue(cliente.getIdentidadeVerificada());
        assertEquals("São Paulo, SP", cliente.getLocalizacao());
    }

    @Test
    @DisplayName("Deve criar cliente com valores default")
    void deveCriarClienteComValoresDefault() {
        // Act
        Cliente cliente = new Cliente();
        cliente.setId(1L);
        cliente.setNome("Maria");

        // Assert
        assertNotNull(cliente);
        assertEquals(1L, cliente.getId());
        assertEquals("Maria", cliente.getNome());
        assertNull(cliente.getUrlFoto());
        assertNull(cliente.getMediaNota());
        assertNull(cliente.getIdentidadeVerificada());
    }

    @Test
    @DisplayName("Deve permitir nota zero")
    void devePermitirNotaZero() {
        // Act
        Cliente cliente = Cliente.builder()
            .id(1L)
            .nome("Cliente Novo")
            .mediaNota(0.0)
            .build();

        // Assert
        assertEquals(0.0, cliente.getMediaNota());
    }

    @Test
    @DisplayName("Deve permitir valores nulos para campos opcionais")
    void devePermitirValoresNulos() {
        // Act
        Cliente cliente = Cliente.builder()
            .id(1L)
            .nome("Basico")
            .build();

        // Assert
        assertNotNull(cliente);
        assertNotNull(cliente.getNome());
        assertNull(cliente.getUrlFoto());
        assertNull(cliente.getLocalizacao());
        assertNull(cliente.getProjetosConcluidos());
    }

    @Test
    @DisplayName("Deve comparar clientes pela interface de entidade")
    void deveCompararClientes() {
        // Arrange
        Cliente c1 = Cliente.builder().id(1L).nome("A").build();
        Cliente c2 = Cliente.builder().id(1L).nome("A").build();
        Cliente c3 = Cliente.builder().id(2L).nome("A").build();

        // Assert - baseado em Lombok @Data
        assertEquals(c1, c2); // mesmo ID e nome
        assertNotEquals(c1, c3); // IDs diferentes
    }

    @Test
    @DisplayName("Deve ter toString gerado pelo Lombok")
    void deveTerToString() {
        // Arrange
        Cliente cliente = Cliente.builder()
            .id(1L)
            .nome("Teste Cliente")
            .localizacao("Rio de Janeiro")
            .build();

        // Act
        String toString = cliente.toString();

        // Assert
        assertTrue(toString.contains("Cliente"));
        assertTrue(toString.contains("Teste Cliente"));
    }

    @Test
    @DisplayName("Deve calcular total de projetos")
    void deveCalcularTotalProjetos() {
        // Arrange
        Cliente cliente = Cliente.builder()
            .id(1L)
            .nome("Calculista")
            .projetosConcluidos(5)
            .projetosPublicados(10)
            .projetosEmAndamento(2)
            .projetosCancelados(1)
            .build();

        // Act
        int total = cliente.getProjetosConcluidos() + cliente.getProjetosPublicados()
            + cliente.getProjetosEmAndamento() + cliente.getProjetosCancelados();

        // Assert
        assertEquals(18, total);
    }
}
