package com.freelasatende.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Falsos positivos medidos em producao: respostas legitimas que citam celular,
 * telefone, transferencia ou palavras que contem tokens de canal (ex. "what's",
 * "pixel") nao devem ser bloqueadas quando nao ha intento de contato externo.
 */
@DisplayName("IaSafetyPolicy: falsos positivos do filtro anti-contato")
class IaSafetyPolicyFalsoPositivoTest {

    @Test
    @DisplayName("Nao deve bloquear respostas legitimas que citam celular/telefone/transferencia")
    void naoDeveBloquearRespostasLegitimas() {
        List<String> mensagens = List.of(
            "O app de assinatura digital funciona pelo celular do cliente.",
            "O sistema utiliza para assinatura o proprio celular.",
            "Posso montar o app que organiza pelo celular a assinatura.",
            "What's the deadline you have in mind?",
            "Ajusto o layout pixel a pixel.",
            "Faco a transferencia de arquivos para o servidor.",
            "Integro com o telefone do usuario via SMS da plataforma.",
            "O app de assinatura pelo celular fica pronto em 15 dias.", "Faco o contato com 2 integracoes.", "Exporto para Excel 2019.",
            "Faco a transferencia de arquivos da conta do Drive."
        );

        for (String mensagem : mensagens) {
            assertFalse(IaSafetyPolicy.containsUnsafeContent(mensagem),
                "Resposta legitima foi bloqueada indevidamente: " + mensagem);
        }
    }

    @Test
    @DisplayName("Deve bloquear tentativas de contato/pagamento fora da plataforma")
    void deveBloquearTentativasDeContato() {
        List<String> mensagens = List.of(
            "me chama no zap",
            "meu whats 11999999999",
            "z a p",
            "w.p.p",
            "pix: 123",
            "me liga no celular",
            "meu telefone: 11 99999-9999",
            "pagar via pix",
            "manda no telegram",
            "xxwhatsappyy",
            "meu gmail joao@gmail.com",
            "whats11999999999",
            "wpp11999999999",
            "zap 11999999999",
            "z,a,p",
            "w+p+p",
            "z~a~p",
            "p:i:x",
            "meu celular é (11) 99999-9999",
            "meu telefone para contato 11 99999-9999",
            "faca o pagamento por transferencia para minha conta",
            "deposito na minha conta",
            "z'a'p",
            "w'p'p",
            "p'i'x",
            "me chama no zapzap",
            "meu whatsap",
            "chama no zapp",
            "whatszap",
            "meu celular 99999-9999",
            "meu cel: 9 9999-9999",
            "11 9 9999 9999",
            "qual seu telefone?",
            "me fala seu celular",
            "pode me passar seu numero?",
            "qual o seu telefone?"
        );

        for (String mensagem : mensagens) {
            assertTrue(IaSafetyPolicy.containsUnsafeContent(mensagem),
                "Tentativa de contato externo nao bloqueada: " + mensagem);
        }
    }
}
