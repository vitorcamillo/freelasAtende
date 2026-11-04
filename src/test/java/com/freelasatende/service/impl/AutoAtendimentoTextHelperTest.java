package com.freelasatende.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AutoAtendimentoTextHelperTest {

    @Test
    void extraiSlugDoWorkanaComQueryEFragmento() {
        assertEquals(
            "criar-landing-page-em-react",
            AutoAtendimentoTextHelper.extrairSlug(
                "https://www.workana.com/job/criar-landing-page-em-react?ref=home#bid",
                12345L
            )
        );
        assertEquals(
            "~022081785343887882413",
            AutoAtendimentoTextHelper.extrairSlug(
                "https://www.upwork.com/jobs/~022081785343887882413/",
                99L
            )
        );
    }

    @Test
    void preservaExtracaoDoFormatoProjectERejeitaUrlDesconhecida() {
        assertEquals(
            "python-api",
            AutoAtendimentoTextHelper.extrairSlug("https://site.test/project/python-api-12345", 12345L)
        );
        assertNull(AutoAtendimentoTextHelper.extrairSlug("https://site.test/projects/python-api", 12345L));
    }
}
