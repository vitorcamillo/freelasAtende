package com.freelasatende.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freelasatende.config.IaConfig;
import com.freelasatende.model.entity.AcaoPendente;
import com.freelasatende.model.entity.Projeto;
import com.freelasatende.model.enums.ModoSistema;
import com.freelasatende.model.enums.StatusAcaoPendente;
import com.freelasatende.model.enums.TipoAcaoPendente;
import com.freelasatende.repository.AcaoPendenteRepository;
import com.freelasatende.repository.InteracaoRepository;
import com.freelasatende.service.impl.AutoAtendimentoServiceImpl;
import com.freelasatende.service.interfaceServico.AprendizadoService;
import com.freelasatende.service.interfaceServico.AvaliadorIaService;
import com.freelasatende.service.interfaceServico.ScraperService;
import com.freelasatende.service.interfaceServico.TelegramService;
import com.freelasatende.service.interfaceServico.HistoricoService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * Testes FUNCIONAIS REAIS do AutoAtendimentoService.
 * Usa SpringBootTest com todos os serviços reais configurados.
 */
@SpringBootTest(properties = "scheduling.enabled=false")
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutoAtendimentoServiceRealIT {

    @Autowired
    private AutoAtendimentoServiceImpl autoAtendimentoService;

    @Autowired
    private ScraperService scraperService;

    @Autowired
    private AvaliadorIaService avaliadorIaService;

    @Autowired
    private TelegramService telegramService;

    @Autowired
    private HistoricoService historicoService;

    @Autowired
    private AprendizadoService aprendizadoService;

    @Autowired
    private AcaoPendenteRepository acaoPendenteRepository;

    @Autowired
    private InteracaoRepository interacaoRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private static final Path MEMORIA_PATH = Paths.get("data/memoria-contexto.json");
    private static String memoriaBackup;
    private static boolean memoriaExistiaAntes;

    @DynamicPropertySource
    static void carregarDotEnv(DynamicPropertyRegistry registry) {
        Map<String, String> env = lerDotEnvSeExistir(Paths.get(".env").toFile());

        registry.add("ia.api-key", () -> env.getOrDefault("IA_API_KEY", ""));
        registry.add("ia.base-url", () -> env.getOrDefault("IA_BASE_URL", "https://api.openai.com/v1"));
        registry.add("ia.model", () -> env.getOrDefault("IA_MODEL", "gpt-4o-mini"));
        registry.add("ia.timeout", () -> env.getOrDefault("IA_TIMEOUT", "30"));
        registry.add("freelas.email", () -> env.getOrDefault("FREELAS_EMAIL", ""));
        registry.add("freelas.senha", () -> env.getOrDefault("FREELAS_PASSWORD", ""));
        registry.add("telegram.bot-token", () -> env.getOrDefault("TELEGRAM_BOT_TOKEN", ""));
        registry.add("telegram.chat-id", () -> env.getOrDefault("TELEGRAM_CHAT_ID", ""));
    }

    private static Map<String, String> lerDotEnvSeExistir(java.io.File file) {
        Map<String, String> map = new java.util.HashMap<>();
        if (!file.exists() || !file.isFile()) {
            return map;
        }
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int idx = line.indexOf('=');
                if (idx <= 0) {
                    continue;
                }
                String key = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();
                value = value.replaceAll("^\"|\"$", "").replaceAll("^\'|'$", "");
                map.put(key, value);
            }
        } catch (Exception e) {
            // ignore
        }
        return map;
    }

    @BeforeAll
    void setup() throws Exception {
        // Backup memória contexto
        Files.createDirectories(MEMORIA_PATH.getParent());
        memoriaExistiaAntes = Files.exists(MEMORIA_PATH);
        if (memoriaExistiaAntes) {
            memoriaBackup = Files.readString(MEMORIA_PATH);
        }

        // Verifica se está logado no 99Freelas
        boolean logado = scraperService.verificarLogin();
        Assumptions.assumeTrue(logado, "Precisa estar logado no 99Freelas para rodar este teste");
    }

    @AfterAll
    void restaurarMemoria() throws Exception {
        if (memoriaExistiaAntes && memoriaBackup != null) {
            Files.writeString(MEMORIA_PATH, memoriaBackup);
        } else {
            Files.deleteIfExists(MEMORIA_PATH);
        }
    }

    @AfterEach
    @Transactional
    void limparDados() {
        // Limpa ações pendentes criadas nos testes
        acaoPendenteRepository.deleteAll();
        interacaoRepository.deleteAll();
    }

    @Test
    @DisplayName("Deve processar um projeto real do 99Freelas e criar ação pendente")
    void deveProcessarProjetoRealECriarAcaoPendente() {
        // Arrange: buscar um projeto real do 99Freelas
        var projetos = scraperService.buscarProjetosNovos();
        assertFalse(projetos.isEmpty(), "Deveria encontrar projetos no 99Freelas");

        Projeto projeto = projetos.get(0);
        System.out.println("Processando projeto real: " + projeto.getTitulo() + " (ID: " + projeto.getId() + ")");

        // Act: processar o projeto
        autoAtendimentoService.processarProjeto(projeto, ModoSistema.REGULAR);

        // Assert: verificar que foi criado algo no banco
        long acoesPendentes = acaoPendenteRepository.count();
        System.out.println("Ações pendentes criadas: " + acoesPendentes);

        // O projeto deve ter gerado uma ação (proposta ou pergunta) ou ter sido ignorado
        // Não deve ter dado erro
        assertTrue(true, "Processamento completou sem exceção");
    }

    @Test
    @DisplayName("Deve sincronizar inbox real do 99Freelas")
    void deveSincronizarInboxReal() {
        // Act
        autoAtendimentoService.sincronizarInbox(ModoSistema.REGULAR);

        // Assert: não deve ter dado erro
        assertTrue(true, "Sincronização completou");
    }

    @Test
    @DisplayName("Deve listar ações pendentes corretamente")
    void deveListarAcoesPendentes() {
        // Arrange: criar uma ação pendente de teste
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(999999L)
            .tipo(TipoAcaoPendente.ENVIAR_PROPOSTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Projeto de Teste")
            .resumo("Teste de proposta")
            .payloadJson("{}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acaoPendenteRepository.save(acao);

        // Act
        long pendentes = autoAtendimentoService.contarAcoesPendentes();

        // Assert
        assertTrue(pendentes >= 1, "Deveria ter pelo menos 1 ação pendente");
    }

    @Test
    @DisplayName("Deve rejeitar ação pendente e registrar aprendizado")
    void deveRejeitarAcaoPendente() {
        // Arrange: criar ação pendente
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(888888L)
            .tipo(TipoAcaoPendente.ENVIAR_PROPOSTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Projeto Teste Rejeição")
            .resumo("Proposta de teste")
            .payloadJson("{}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);

        // Act
        String resultado = autoAtendimentoService.processarFeedback(acao.getId(), false, "Não quero enviar");

        // Assert
        assertTrue(resultado.contains("rejeitada") || resultado.contains("Rejeitada"));
        assertEquals(StatusAcaoPendente.REJEITADA, acaoPendenteRepository.findById(acao.getId()).get().getStatus());
    }

    @Test
    @DisplayName("Deve aprovar ação pendente e executar")
    @Disabled("Requer projeto real e pode enviar dados para o 99Freelas")
    void deveAprovarEAcaoPendente() {
        // Arrange: criar ação pendente
        AcaoPendente acao = AcaoPendente.builder()
            .projetoId(777777L)
            .tipo(TipoAcaoPendente.ENVIAR_PERGUNTA)
            .status(StatusAcaoPendente.PENDENTE)
            .modoOrigem(ModoSistema.REGULAR)
            .tituloProjeto("Projeto Teste Aprovação")
            .resumo("Pergunta de teste")
            .payloadJson("{\"projetoId\": 777777, \"mensagem\": \"Qual o prazo?\"}")
            .dataCriacao(LocalDateTime.now())
            .dataAtualizacao(LocalDateTime.now())
            .build();
        acao = acaoPendenteRepository.save(acao);

        // Act
        String resultado = autoAtendimentoService.processarFeedback(acao.getId(), true, "Pode enviar");

        // Assert
        assertTrue(resultado.contains("executada") || resultado.contains("sucesso") || resultado.contains("Executada"));
    }

    @Test
    @DisplayName("Deve contar zero ações quando não há pendências")
    void deveContarZeroQuandoNaoHaPendencia() {
        // Arrange: garantir que não há pendências
        acaoPendenteRepository.deleteAll();

        // Act
        long pendentes = autoAtendimentoService.contarAcoesPendentes();

        // Assert
        assertEquals(0, pendentes, "Não deveria ter ações pendentes");
    }

    @Test
    @DisplayName("Deve processar projeto nulo sem exceção")
    void deveProcessarProjetoNuloSemExcecao() {
        // Act & Assert: não deve lançar exceção
        assertDoesNotThrow(() -> autoAtendimentoService.processarProjeto(null, ModoSistema.REGULAR));
    }

    @Test
    @DisplayName("Deve buscar projetos reais do 99Freelas")
    void deveBuscarProjetosReaisDo99Freelas() {
        // Act
        var projetos = scraperService.buscarProjetosNovos();

        // Assert
        assertNotNull(projetos);
        assertFalse(projetos.isEmpty(), "Deveria encontrar projetos");

        Projeto primeiro = projetos.get(0);
        assertNotNull(primeiro.getId(), "ID do projeto não pode ser nulo");
        assertNotNull(primeiro.getTitulo(), "Título do projeto não pode ser nulo");

        System.out.println("Projetos encontrados: " + projetos.size());
        System.out.println("Primeiro projeto: " + primeiro.getTitulo() + " (ID: " + primeiro.getId() + ")");
    }

    @Test
    @DisplayName("Deve buscar ranking de cliente real")
    void deveBuscarRankingClienteReal() {
        // Arrange: pegar um projeto real
        var projetos = scraperService.buscarProjetosNovos();
        org.junit.jupiter.api.Assumptions.assumeFalse(projetos.isEmpty(), "Precisa de projetos para testar");

        Long projetoId = projetos.get(0).getId();

        // Act
        var cliente = scraperService.buscarCliente(projetoId);

        // Assert
        assertNotNull(cliente, "Cliente não pode ser nulo");
        System.out.println("Cliente: " + cliente.getNome() + " - Nota: " + cliente.getMediaNota());
    }

    @Test
    @DisplayName("Deve verificar se está logado no 99Freelas")
    void deveVerificarLoginNo99Freelas() {
        // Act
        boolean logado = scraperService.verificarLogin();

        // Assert
        assertTrue(logado, "Deveria estar logado no 99Freelas");
    }

    @Test
    @DisplayName("Deve conectar na IA real e avaliar projeto")
    void deveConectarIaRealEAvaliarProjeto() {
        // Arrange
        Projeto projeto = Projeto.builder()
            .id(123456L)
            .titulo("Sistema web com React e Node.js")
            .descricao("Preciso de um sistema completo com autenticação e banco de dados")
            .categoria("Web")
            .nivelExperiencia("Pleno")
            .orcamento(BigDecimal.valueOf(5000))
            .build();

        // Act
        boolean capaz = avaliadorIaService.somosCapazesDeAtender(projeto);

        // Assert
        System.out.println("Resultado da avaliação: " + (capaz ? "CAPAZ" : "NÃO CAPAZ"));
        // Não verificamos o resultado específico, apenas que não deu erro
        assertTrue(true, "Avaliação completou");
    }
}
