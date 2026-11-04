package com.freelasatende.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Dashboard: contrato de UI para ensino controlado")
class DashboardAssetsContractTest {

    private static final Path DASHBOARD_HTML = Path.of("src/main/resources/templates/dashboard.html");
    private static final Path DASHBOARD_CSS = Path.of("src/main/resources/static/css/dashboard.css");
    private static final Path CSS_DIR = Path.of("src/main/resources/static/css/dashboard/");
    private static final Path DASHBOARD_JS = Path.of("src/main/resources/static/js/dashboard.js");
    private static final Path JS_DIR = Path.of("src/main/resources/static/js/");

    private static String readAllDashboardJs() throws Exception {
        String[] arquivos = {"dashboard.js", "dashboard-actions.js", "dashboard-overview.js",
            "dashboard-ml-lab.js", "dashboard-inbox-logs.js", "dashboard-utils.js"};
        StringBuilder sb = new StringBuilder();
        for (String arquivo : arquivos) {
            Path p = JS_DIR.resolve(arquivo);
            if (Files.exists(p)) {
                sb.append(Files.readString(p));
            }
        }
        return sb.toString();
    }

    @Test
    @DisplayName("CSS do dashboard mantém manifesto modular em ordem estável")
    void cssMantemManifestoModularEmOrdemEstavel() throws Exception {
        List<String> modules = List.of("base", "header", "navigation", "layout", "ml-lab",
            "panels", "workflows", "components", "responsive");
        String css = Files.readString(DASHBOARD_CSS);
        int lastIndex = -1;

        for (String module : modules) {
            String importStatement = "@import url(\"./dashboard/" + module + ".css\");";
            int index = css.indexOf(importStatement);

            assertTrue(index > lastIndex, "Import ausente ou fora de ordem: " + module);
            assertTrue(Files.exists(CSS_DIR.resolve(module + ".css")), "Módulo CSS ausente: " + module);
            lastIndex = index;
        }
    }

    @Test
    @DisplayName("HTML expõe áreas essenciais de revisão, foco e reescrita")
    void htmlExpoeAreasEssenciaisDeRevisaoFocoEReescrita() throws Exception {
        String html = Files.readString(DASHBOARD_HTML);

        assertTrue(html.contains("controlCenterApp"));
        assertTrue(html.contains("data-abordagem-inicial=${abordagemInicial}"));
        assertTrue(html.contains("teachingFocusPanel"));
        assertTrue(html.contains("actionDetail"));
        assertTrue(html.contains("rewriteModal"));
        assertTrue(html.contains("rewriteText"));
        assertTrue(html.contains("rewriteComment"));
        assertTrue(html.contains("confirmRewriteButton"));
        assertTrue(html.contains("kpiConexoesTopo"));
        assertTrue(html.contains("syncInboxButton"));
        assertTrue(html.contains("syncProjectsButton"));
        assertTrue(html.contains("tab-ml-lab"));
        assertTrue(html.contains("mlLabCreateButton"));
        assertTrue(html.contains("mlLabAgents"));
        assertTrue(html.contains("mlLabCandidates"));
    }

    @Test
    @DisplayName("HTML mantém a operação diária simples e empurra diagnóstico para avançado")
    void htmlMantemOperacaoDiariaSimples() throws Exception {
        String html = Files.readString(DASHBOARD_HTML);

        assertTrue(html.contains("<h1>Operação</h1>"));
        assertTrue(html.contains("<summary>Avançado</summary>"));
        assertTrue(html.contains("tab-inbox-button"));
        assertTrue(html.contains("class=\"btn btn-ghost aux-control\" id=\"pauseButton\""));
        assertTrue(html.contains("class=\"tab-btn diagnostic-tab\" type=\"button\" data-tab=\"logs\""));
        assertTrue(html.indexOf("tab-inbox-button") < html.indexOf("advanced-nav"));
        assertTrue(html.indexOf("advanced-nav") < html.indexOf("tab-logs-button"));
    }

    @Test
    @DisplayName("JS liga botões de ensinar, responder dúvida, reescrever, aprovar e recusar")
    void jsLigaBotoesDeEnsinarResponderDuvidaReescreverAprovarERecusar() throws Exception {
        String js = readAllDashboardJs();

        assertTrue(js.contains("data-teach-id"));
        assertTrue(js.contains("data-answer-question-id"));
        assertTrue(js.contains("data-rewrite-id"));
        assertTrue(js.contains("data-approve-id"));
        assertTrue(js.contains("data-reject-id"));
        assertTrue(js.contains("registrarContextoEnsino"));
        assertTrue(js.contains("responderDuvidaIa"));
        assertTrue(js.contains("confirmarReescrita"));
        assertTrue(js.contains("aprovarAcao"));
        assertTrue(js.contains("recusarAcao"));
        assertTrue(js.contains("sincronizarInboxPendencia"));
        assertTrue(js.contains("sincronizarProjetosPendencia"));
        assertTrue(js.contains("requiresTeachingConversation"));
        assertTrue(js.contains("createMlLabRun"));
        assertTrue(js.contains("startMlLabRun"));
        assertTrue(js.contains("sendMlLabChat"));
        assertTrue(js.contains("MULTI_CRITERIA"));
        assertTrue(js.contains("parallelWorkers"));
        assertTrue(js.contains("selectionBand"));
        assertTrue(js.contains("protectedCyclesLeft"));
        assertTrue(js.contains("selectionReason"));
        assertTrue(js.contains("/api/ml-lab/runs"));
        assertTrue(js.contains("/api/ml-lab/candidates/${candidateId}/approve"));
        assertTrue(js.contains("dataset.abordagemInicial"));
        assertTrue(js.contains("ADVANCED_TABS"));
        assertTrue(js.contains("renderModeWritePolicy"));
        assertTrue(js.contains("Envio real liberado"));
        assertTrue(js.contains("Ensino trava envio direto"));
    }

    @Test
    @DisplayName("JS chama endpoints seguros e exige motivo antes de aprovar ou recusar")
    void jsChamaEndpointsSegurosEExigeMotivoAntesDeAprovarOuRecusar() throws Exception {
        String js = readAllDashboardJs();

        assertTrue(js.contains("/api/controle/acoes/${id}/contexto"));
        assertTrue(js.contains("/api/controle/acoes/${id}/responder-duvida"));
        assertTrue(js.contains("/api/controle/acoes/${id}/reescrever"));
        assertTrue(js.contains("/api/controle/acoes/${id}/aprovar"));
        assertTrue(js.contains("/api/controle/acoes/${id}/recusar"));
        assertTrue(js.contains("/api/controle/sincronizar-inbox"));
        assertTrue(js.contains("/api/controle/sincronizar-projetos"));
        assertTrue(js.contains("Escolha um motivo para ensinar antes de aprovar."));
        assertTrue(js.contains("Escolha um motivo para ensinar antes de recusar."));
        assertTrue(js.contains("Ação continua pendente") || js.contains("mantido pendente") || js.contains("mantida pendente"));
    }

    @Test
    @DisplayName("JS mostra leitura, atitude e conversa obrigatória da IA")
    void jsMostraLeituraAtitudeEConversaObrigatoriaDaIa() throws Exception {
        String js = readAllDashboardJs();

        assertTrue(js.contains("Leitura da IA"));
        assertTrue(js.contains("Atitude sugerida"));
        assertTrue(js.contains("Conversa de ensino"));
        assertTrue(js.contains("Opções da IA"));
        assertTrue(js.contains("conversaEnsinoObrigatoria"));
        assertTrue(js.contains("conversaEnsinoRespondida"));
        assertTrue(js.contains("Salvar resposta para IA"));
    }
}
