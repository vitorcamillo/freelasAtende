package com.freelasatende.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.IaConfig;
import com.freelasatende.config.TelegramConfig;
import com.freelasatende.model.entity.Interacao;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.TipoInteracao;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.repository.ProjetoRepository;
import com.freelasatende.service.impl.TelegramServiceImpl;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.HistoricoService;
import com.freelasatende.service.interfaceServico.IaProvider;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
abstract class ConversacaoSimuladaTestSupport {

    @Autowired
    protected AvaliadorIaService avaliadorIaService;

    @Autowired
    protected HistoricoService historicoService;

    @MockBean
    protected TelegramServiceImpl telegramService;

    @MockBean
    protected IaProvider iaProvider;

    @Autowired
    protected InteracaoRepository interacaoRepository;

    @Autowired
    protected ProjetoRepository projetoRepository;

    @Autowired
    protected AcaoPendenteRepository acaoPendenteRepository;

    @Autowired
    protected IaConfig iaConfig;

    @Autowired
    protected TelegramConfig telegramConfig;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void setUpConversacaoSimulada() {
        configurarIaFake();
        acaoPendenteRepository.deleteAll();
        projetoRepository.deleteAll();
        interacaoRepository.deleteAll();
    }

    protected Projeto criarProjeto(Long id, String titulo, BigDecimal orcamento, String nivel) {
        return Projeto.builder()
            .id(id)
            .titulo(titulo)
            .descricao("Descricao teste para " + titulo)
            .categoria("Desenvolvimento")
            .orcamento(orcamento)
            .nivelExperiencia(nivel)
            .numeroPropostas(5)
            .mediaProposta(orcamento != null ? orcamento.multiply(BigDecimal.valueOf(0.8)) : BigDecimal.valueOf(500))
            .valorMinimo(BigDecimal.valueOf(100))
            .build();
    }

    protected Interacao criarInteracao(Long projetoId, String titulo, TipoInteracao tipo, ModoSistema modo) {
        return Interacao.builder()
            .projetoId(projetoId)
            .projetoTitulo(titulo)
            .tipo(tipo)
            .modoUsado(modo)
            .conteudo("Interacao teste")
            .data(LocalDateTime.now())
            .build();
    }

    private void configurarIaFake() {
        try {
            when(iaProvider.enviar(anyString(), anyInt())).thenAnswer(invocation ->
                respostaIaFake(invocation.getArgument(0, String.class))
            );
        } catch (IaProvider.IaProviderException e) {
            throw new IllegalStateException(e);
        }
        when(iaProvider.getNome()).thenReturn("FakeIA");
        when(iaProvider.getModelo()).thenReturn("test-offline");
        when(iaProvider.estaDisponivel()).thenReturn(true);
    }

    private String respostaIaFake(String prompt) {
        String p = prompt == null ? "" : prompt.toLowerCase();
        if (p.contains("apenas json")) {
            return """
                {"naturalidade":9,"clareza":9,"risco":0,"duplicidade":0,"confianca":9,"decisao":"APROVAR","comentario":"ok","sinais":[]}
                """;
        }
        if (p.contains("sem_duvidas")) {
            return "SEM_DUVIDAS";
        }
        if (p.contains("click here free money") || p.contains("projeto spam") || p.contains("magento")) {
            return "NAO - fora do escopo seguro";
        }
        return "SIM - projeto dentro do escopo";
    }
}
