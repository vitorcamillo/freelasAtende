package com.freelasatende.service.impl;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScraperHttpClientTest {

    private static HttpHeaders headersCom(String nome, String valor) {
        return HttpHeaders.of(Map.of(nome, List.of(valor)), (chave, v) -> true);
    }

    @ParameterizedTest(name = "[{index}] status={0} cf-mitigated={1} body={2} -> {3}")
    @CsvSource({
        "403, challenge, 'html qualquer', true",
        "503, '', 'Just a moment...', true",
        "503, '', 'cf-chl-bypass do script', true",
        "503, '', '<script src=/cdn-cgi/challenge-platform/>', true",
        "403, '', 'erro comum do servidor', false",
        "422, challenge, 'payload invalido', false",
        "500, challenge, 'erro interno', false",
        "200, challenge, 'ok', false"
    })
    void isCloudflareChallenge(int status, String cfMitigated, String body, boolean esperado) {
        HttpHeaders headers = cfMitigated.isEmpty()
            ? HttpHeaders.of(Map.of(), (chave, v) -> true)
            : headersCom("cf-mitigated", cfMitigated);

        assertEquals(esperado, ScraperHttpClient.isCloudflareChallenge(status, headers, body));
    }
}
