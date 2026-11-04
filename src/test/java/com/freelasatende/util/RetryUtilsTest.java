package com.freelasatende.util;

import com.freelasatende.service.interfaceServico.IaProvider;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários para RetryUtils.
 * Testa a lógica de retry com backoff exponencial sem dependências externas.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetryUtilsTest {

    @Test
    @DisplayName("Deve retornar resultado na primeira tentativa quando bem-sucedido")
    void deveRetornarResultadoNaPrimeiraTentativa() throws Exception {
        // Arrange
        int[] contador = {0};

        // Act
        String resultado = RetryUtils.comRetry(() -> {
            contador[0]++;
            return "sucesso";
        }, 3, "teste");

        // Assert
        assertEquals("sucesso", resultado);
        assertEquals(1, contador[0], "Deveria ter executado apenas 1 vez");
    }

    @Test
    @DisplayName("Deve fazer retry quando primeira tentativa falha")
    void deveFazerRetryQuandoPrimeiraTentativaFalha() throws Exception {
        // Arrange
        int[] contador = {0};

        // Act
        String resultado = RetryUtils.comRetry(() -> {
            contador[0]++;
            if (contador[0] < 2) {
                throw new RuntimeException("falhou");
            }
            return "sucesso";
        }, 3, "teste");

        // Assert
        assertEquals("sucesso", resultado);
        assertEquals(2, contador[0], "Deveria ter executado 2 vezes");
    }

    @Test
    @DisplayName("Deve lancar exception apos todas tentativas falharem")
    void deveLancarExceptionAposTodasTentativasFalharem() {
        // Arrange
        int[] contador = {0};

        // Act & Assert
        assertThrows(RuntimeException.class, () -> {
            RetryUtils.comRetry(() -> {
                contador[0]++;
                throw new RuntimeException("sempre falha");
            }, 3, "teste");
        });

        assertEquals(3, contador[0], "Deveria ter tentado 3 vezes");
    }

    @Test
    @DisplayName("Deve usar CheckedSupplier e funcionar igual comRetry")
    void deveUsarCheckedSupplier() throws Exception {
        // Arrange
        int[] contador = {0};

        // Act
        String resultado = RetryUtils.comRetryChecked(() -> {
            contador[0]++;
            return "feito";
        }, 2, "teste-checked");

        // Assert
        assertEquals("feito", resultado);
        assertEquals(1, contador[0]);
    }

    @Test
    @DisplayName("Deve calcular backoff exponencial correto")
    void deveCalcularBackoffExponencialCorreto() {
        // Act & Assert - verificar crescimento exponencial
        long t1 = RetryUtils.calcularBackoff(1);
        long t2 = RetryUtils.calcularBackoff(2);
        long t3 = RetryUtils.calcularBackoff(3);

        // Base: 1s, 2s, 4s com jitter de ±30%
        assertTrue(t1 >= 700 && t1 <= 1300, "Tentativa 1 deve ser ~1s, got: " + t1);
        assertTrue(t2 >= 1400 && t2 <= 2600, "Tentativa 2 deve ser ~2s, got: " + t2);
        assertTrue(t3 >= 2800 && t3 <= 5200, "Tentativa 3 deve ser ~4s, got: " + t3);
    }

    @Test
    @DisplayName("Deve limitar backoff maximo em 30 segundos")
    void deveLimitarBackoffMaximoEm30Segundos() {
        // Act
        long t10 = RetryUtils.calcularBackoff(10);

        // Assert - mesmo com tentativa alta, não deve passar de 30s + jitter
        assertTrue(t10 <= 39000, "Não deve exceder 39s (30s + 30% jitter), got: " + t10);
        assertTrue(t10 >= 21000, "Deve ter pelo menos 21s (30s - 30% jitter), got: " + t10);
    }

    @Test
    @DisplayName("Deve ter jitter aleatorio (valores diferentes em diferentes chamadas)")
    void deveTerJitterAleatorio() {
        // Act
        long t1a = RetryUtils.calcularBackoff(1);
        long t1b = RetryUtils.calcularBackoff(1);
        long t1c = RetryUtils.calcularBackoff(1);

        // Assert - pelo menos uma das chamadas deve ser diferente
        boolean todasIguais = (t1a == t1b) && (t1b == t1c);
        assertFalse(todasIguais, "Deveria haver variação no jitter entre chamadas");
    }

    @Test
    @DisplayName("Deve funcionar com lambda que retorna null")
    void deveFuncionarComLambdaQueRetornaNull() throws Exception {
        // Act
        Object resultado = RetryUtils.comRetry(() -> null, 2, "teste-null");

        // Assert
        assertNull(resultado);
    }

    @Test
    @DisplayName("Deve fazer retry comCheckedSupplier ate sucesso")
    void deveFazerRetryComCheckedSupplierAteSucesso() throws Exception {
        // Arrange
        int[] contador = {0};

        // Act
        Integer resultado = RetryUtils.comRetryChecked(() -> {
            contador[0]++;
            if (contador[0] < 3) {
                throw new Exception("ainda não");
            }
            return 42;
        }, 5, "teste-checked-retry");

        // Assert
        assertEquals(42, resultado);
        assertEquals(3, contador[0]);
    }

    @Test
    @DisplayName("Nao deve fazer retry em falha de IA nao-retriavel")
    void naoDeveFazerRetryEmFalhaIaNaoRetriavel() {
        // Arrange
        int[] contador = {0};

        // Act & Assert
        IaProvider.IaProviderException erro = assertThrows(IaProvider.IaProviderException.class, () -> {
            RetryUtils.comRetryChecked(() -> {
                contador[0]++;
                throw new IaProvider.IaProviderException("saldo insuficiente", 402, false);
            }, 3, "teste-nao-retriavel");
        });

        assertEquals(402, erro.getStatusCode());
        assertEquals(1, contador[0], "Falha nao-retriavel nao deve tentar novamente");
    }

    @Test
    @DisplayName("Classe não deve ter construtores públicos (utilitaria)")
    void classeNaoDeveTerConstrutoresPublicos() throws Exception {
        // Verifica que o construtor privado não é acessível diretamente
        java.lang.reflect.Constructor<?>[] constructors = RetryUtils.class.getDeclaredConstructors();
        assertEquals(1, constructors.length, "Deveria ter apenas 1 construtor (privado)");
        assertFalse(java.lang.reflect.Modifier.isPublic(constructors[0].getModifiers()),
            "Construtor não deve ser público");
    }
}
