package com.freelasatende.service;

import com.freelasatende.util.IaSafetyPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.lang.reflect.Constructor;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "scheduling.enabled=false")
@ActiveProfiles("test")
@DisplayName("IaSafetyPolicy Red Team: testes de seguranca de conteudo")
class IaSafetyRedTeamTest {

    @Autowired
    private IaSafetyPolicy iaSafetyPolicy;

    @DynamicPropertySource
    static void propriedadesSemCredenciaisExternas(DynamicPropertyRegistry registry) {
        registry.add("ia.api-key", () -> "");
        registry.add("ia.base-url", () -> "https://api.local.invalid");
        registry.add("ia.model", () -> "test-local");
        registry.add("freelas.email", () -> "");
        registry.add("freelas.senha", () -> "");
        registry.add("telegram.bot-token", () -> "");
        registry.add("telegram.chat-id", () -> "");
    }

    @ParameterizedTest(name = "bloqueia: {0}")
    @MethodSource("casosBloqueados")
    @DisplayName("Red team: deve bloquear frases proibidas")
    void deveBloquearFrasesProibidas(String frase) {
        assertTrue(iaSafetyPolicy.containsUnsafeContent(frase));
        assertEquals("", iaSafetyPolicy.sanitize(frase, 700));
    }

    @ParameterizedTest(name = "permite: {0}")
    @MethodSource("casosPermitidos")
    @DisplayName("Red team: deve permitir respostas profissionais")
    void devePermitirFrasesSeguras(String frase) {
        assertFalse(iaSafetyPolicy.containsUnsafeContent(frase));
        assertEquals(frase, iaSafetyPolicy.sanitize(frase, 700));
    }

    @Test
    @DisplayName("Sanitize deve bloquear conteudo vazio/nulo sem fallback local")
    void sanitizeDeveBloquearConteudoVazioSemFallback() {
        assertEquals("", iaSafetyPolicy.sanitize(null, 700));
        assertEquals("", iaSafetyPolicy.sanitize("   ", 700));
    }

    @Test
    @DisplayName("Sanitize deve normalizar espacos e limitar tamanho")
    void sanitizeDeveNormalizarELimitar() {
        String texto = "Linha   com   muitos   espacos";
        String limpo = iaSafetyPolicy.sanitize(texto, 10);
        assertEquals("Linha com", limpo);
    }

    static Stream<String> casosBloqueados() {
        return Stream.of(
            "Garanto 100% de sucesso no projeto.",
            "Sem risco nenhum, resultado garantido.",
            "Me chama no WhatsApp para fechar.",
            "Me chama no w h a t s a p p para combinar.",
            "Me chama no zap que agilizamos.",
            "Manda seu telefone e e-mail para continuarmos.",
            "Me passe seu gmail.",
            "Podemos fechar por PIX.",
            "Melhor fazer transferencia direta.",
            "Vamos pagar fora da plataforma para economizar taxa.",
            "Ja analisei seu sistema inteiro e seu banco de dados."
        );
    }

    static Stream<String> casosPermitidos() {
        return Stream.of(
            "Consigo iniciar ainda hoje e alinhar os detalhes tecnicos contigo.",
            "Se fizer sentido, envio um plano de execucao por etapas.",
            "Posso seguir com escopo, prazo e valor dentro da plataforma.",
            "Para te responder melhor, pode confirmar o prazo ideal de entrega?"
        );
    }
}
