package com.freelasatende.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O Cloudflare injeta scripts (ex.: challenge-platform) em TODAS as paginas do
 * site, inclusive em respostas 200 de sessao logada valida. O detector nao pode
 * confundir script embutido com pagina de bloqueio — isso gerava alerta falso
 * de "Sessao Freelancer.com.br expirada" no Telegram a cada ciclo de polling.
 */
class FreelancerBrClientCloudflareDetectorTest {

    private final FreelancerBrClient client = new FreelancerBrClient(null, null, null);

    @Test
    void paginaLogadaComScriptEmbutidoNaoEDesafio() {
        String inboxLogada = "<html><head><script src=\"/cdn-cgi/challenge-platform/h/b/orchestrate\""
            + "></script></head><body data-user-id=\"123\"><a href=\"/account/logout\">Sair</a>"
            + "<div class=\"inbox\">Mensagens</div></body></html>";
        assertFalse(client.avaliarDesafio(200, inboxLogada),
            "Resposta 200 com marcadores de sessao logada e script challenge embutido e pagina valida");
    }

    /**
     * Regressao real: a fonte Roboto ("ro<b>bot</b>o") aparece em toda pagina do
     * site e o script do Cloudflare tambem. A heuristica "cloudflare + bot"
     * marcava a inbox VALIDA como desafio, e o sistema pedia cookie manual no
     * Telegram varias vezes por dia mesmo com a sessao logada.
     */
    @Test
    void fonteRobotoComScriptCloudflareNaoEDesafio() {
        String paginaReal = "<html><head>"
            + "<link href=\"https://fonts.googleapis.com/css2?family=roboto:wght@300\" rel=\"stylesheet\">"
            + "<link rel=\"preload\" as=\"font\" href=\"/fonts/roboto/roboto-400.woff2\">"
            + "<script src=\"https://static.cloudflareinsights.com/beacon.min.js\"></script>"
            + "</head><body><a href=\"/account/logout\">Sair</a>Minhas mensagens</body></html>";

        assertFalse(client.avaliarDesafio(200, paginaReal),
            "Fonte Roboto + beacon do Cloudflare nao podem ser lidos como bloqueio");
    }

    @Test
    void paginaJustAMomentEDesafioMesmoComStatus200() {
        String desafio = "<html><head><title>Just a moment...</title></head>"
            + "<body>Verifica\u00e7\u00e3o de seguran\u00e7a</body></html>";
        assertTrue(client.avaliarDesafio(200, desafio),
            "Pagina exclusiva de challenge deve ser detectada mesmo com status 200");
    }

    @Test
    void status403E503SaoDesafio() {
        assertTrue(client.avaliarDesafio(403, "<html>qualquer conteudo</html>"));
        assertTrue(client.avaliarDesafio(503, "<html>qualquer conteudo</html>"));
    }

    @Test
    void paginasDeBloqueioDoCloudflareContinuamSendoDetectadas() {
        // A deteccao passou a exigir texto EXCLUSIVO de pagina de bloqueio, em vez
        // da heuristica "cloudflare"+"bot", que gerava falso positivo na fonte Roboto.
        assertTrue(client.avaliarDesafio(200,
            "<html><head><title>Attention Required! | Cloudflare</title></head></html>"));
        assertTrue(client.avaliarDesafio(200,
            "<html><body>Checking your browser before accessing freelancer.com.br</body></html>"));
    }
}
