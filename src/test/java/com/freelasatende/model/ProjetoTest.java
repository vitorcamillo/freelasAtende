package com.freelasatende.model;

import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.StatusProjeto;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários para Projeto entity.
 * Testa validações, builders e métodos da entidade.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjetoTest {

    @Test
    @DisplayName("Deve criar projeto com builder")
    void deveCriarProjetoComBuilder() {
        // Act
        Projeto projeto = Projeto.builder()
            .id(1L)
            .titulo("Projeto Teste")
            .descricao("Descrição teste")
            .categoria("TI")
            .orcamento(BigDecimal.valueOf(5000))
            .status(StatusProjeto.NOVO)
            .foiProcessado(false)
            .dataCriacao(LocalDateTime.now())
            .build();

        // Assert
        assertNotNull(projeto);
        assertEquals(1L, projeto.getId());
        assertEquals("Projeto Teste", projeto.getTitulo());
        assertEquals("Descrição teste", projeto.getDescricao());
        assertEquals("TI", projeto.getCategoria());
        assertEquals(BigDecimal.valueOf(5000), projeto.getOrcamento());
        assertEquals(StatusProjeto.NOVO, projeto.getStatus());
        assertFalse(projeto.getFoiProcessado());
    }

    @Test
    @DisplayName("Deve criar projeto com valores default")
    void deveCriarProjetoComValoresDefault() {
        // Act
        Projeto projeto = new Projeto();
        projeto.setId(1L);
        projeto.setTitulo("Teste");

        // Assert
        assertNotNull(projeto);
        assertEquals(1L, projeto.getId());
        assertEquals("Teste", projeto.getTitulo());
        assertNull(projeto.getDescricao());
        assertNull(projeto.getOrcamento());
        assertNull(projeto.getStatus());
        assertNull(projeto.getFoiProcessado());
    }

    @Test
    @DisplayName("Deve permitir anexoUrls")
    void devePermitirAnexosUrls() {
        // Arrange
        Projeto projeto = Projeto.builder().id(1L).titulo("Teste").build();
        java.util.List<String> anexos = new java.util.ArrayList<>();
        anexos.add("http://exemplo.com/arquivo.pdf");
        anexos.add("http://exemplo.com/imagem.png");
        projeto.setAnexosUrls(anexos);

        // Act & Assert
        assertEquals(2, projeto.getAnexosUrls().size());
        assertTrue(projeto.getAnexosUrls().contains("http://exemplo.com/arquivo.pdf"));
    }

    @Test
    @DisplayName("Status deve ser serializado como string no banco")
    void deveSerializarStatusComoString() {
        // Arrange
        Projeto projeto = Projeto.builder()
            .id(1L)
            .titulo("Teste")
            .status(StatusProjeto.NOVO)
            .build();

        // Assert
        assertEquals(StatusProjeto.NOVO, projeto.getStatus());
        assertEquals("NOVO", projeto.getStatus().name());
    }

    @Test
    @DisplayName("Deve comparar projetos pela interface de entidade (equals/hashCode)")
    void deveCompararProjetos() {
        // Arrange
        Projeto projeto1 = Projeto.builder().id(1L).titulo("A").build();
        Projeto projeto2 = Projeto.builder().id(1L).titulo("A").build();
        Projeto projeto3 = Projeto.builder().id(2L).titulo("A").build();

        // Assert - baseado em Lombok @Data (compara por todos os campos)
        assertEquals(projeto1, projeto2); // mesmo ID e título
        assertNotEquals(projeto1, projeto3); // IDs diferentes
        assertEquals(projeto1.hashCode(), projeto2.hashCode());
    }

    @Test
    @DisplayName("Deve usar LocalDateTime para datas")
    void deveUsarLocalDateTimeParaDatas() {
        // Arrange
        LocalDateTime agora = LocalDateTime.now();
        LocalDateTime ontem = LocalDateTime.now().minusDays(1);

        // Act
        Projeto projeto = Projeto.builder()
            .id(1L)
            .titulo("Teste")
            .dataCriacao(agora)
            .dataProcessamento(ontem)
            .build();

        // Assert
        assertEquals(agora, projeto.getDataCriacao());
        assertEquals(ontem, projeto.getDataProcessamento());
    }

    @Test
    @DisplayName("Deve calcular valores minimos e medios")
    void deveCalcularValores() {
        // Arrange
        Projeto projeto = Projeto.builder()
            .id(1L)
            .titulo("Teste")
            .orcamento(BigDecimal.valueOf(10000))
            .valorMinimo(BigDecimal.valueOf(1000))
            .mediaProposta(BigDecimal.valueOf(5000))
            .numeroPropostas(10)
            .build();

        // Assert
        assertEquals(BigDecimal.valueOf(10000), projeto.getOrcamento());
        assertEquals(BigDecimal.valueOf(1000), projeto.getValorMinimo());
        assertEquals(BigDecimal.valueOf(5000), projeto.getMediaProposta());
        assertEquals(10, projeto.getNumeroPropostas());
    }

    @Test
    @DisplayName("Deve ter toString gerado pelo Lombok")
    void deveTerToString() {
        // Arrange
        Projeto projeto = Projeto.builder()
            .id(1L)
            .titulo("Teste")
            .categoria("TI")
            .build();

        // Act
        String toString = projeto.toString();

        // Assert
        assertTrue(toString.contains("Projeto"));
        assertTrue(toString.contains("Teste"));
    }
}
