const ABORDAGEM_INICIAL = document.body?.dataset.abordagemInicial || "";
const ADVANCED_TABS = new Set(["auditoria", "logs", "ml-lab", "controle"]);

const state = {
    overview: null,
    actions: [],
    selectedId: null,
    filter: "TODAS",
    platformFilter: "TODAS",
    activeTab: "operar",
    eventSource: null,
    loadedLogs: false,
    loadedInbox: false,
    loadedInboxAudit: false,
    loadedMlLab: false,
    mlLabRuns: [],
    mlLabOverview: null,
    mlLabSelectedRunId: null,
    mlLabSelectedAgentKey: null,
    teachingFocus: null,
    ops: null
};

function switchTab(tab) {
    state.activeTab = tab;
    document.body.classList.toggle("lab-mode", tab === "ml-lab");
    const advancedNav = document.querySelector(".advanced-nav");
    if (advancedNav) {
        advancedNav.classList.toggle("has-active", ADVANCED_TABS.has(tab));
        advancedNav.open = ADVANCED_TABS.has(tab);
    }
    document.querySelectorAll("#dashboardTabs .tab-btn").forEach((button) => {
        const active = button.dataset.tab === tab;
        button.classList.toggle("active", active);
        button.setAttribute("aria-selected", String(active));
    });
    document.querySelectorAll(".tab-panel").forEach((panel) => {
        const active = panel.dataset.panel === tab;
        panel.classList.toggle("active", active);
        panel.hidden = !active;
    });
    if (tab === "logs" && !state.loadedLogs) {
        loadLogs();
    }
    if (tab === "inbox" && !state.loadedInboxAudit) {
        loadInboxAudit();
    }
    if (tab === "ml-lab") {
        loadMlLabRuns();
    }
    const url = new URL(window.location.href);
    url.searchParams.set("tab", tab);
    window.history.replaceState({}, "", url);
}

function initialTab() {
    const tab = new URLSearchParams(window.location.search).get("tab") || window.location.hash.replace("#", "");
    return ["operar", "auditoria", "logs", "inbox", "ml-lab", "controle"].includes(tab) ? tab : "operar";
}

async function refreshCore() {
    await Promise.allSettled([loadOverview(), loadActions(), loadModoSistema(), loadOps()]);
    $("lastUpdate").textContent = "Atualizado em " + new Date().toLocaleTimeString("pt-BR");
}

async function loadModoSistema() {
    const modo = await fetchJson("/api/modos");
    state.modoSistema = modo;
    renderModoSistema(modo);
}

function renderModoSistema(modo) {
    const modoAtual = modo?.modo || "ENSINAMENTO";
    const pausado = Boolean(modo?.pausado);
    const autoAtivo = Boolean(modo?.autoAtivo);
    $("modoSistemaSelect").value = modoAtual;
    const modoChip = $("modoAtualChip");
    if (modoChip) {
        modoChip.className = "chip " + (modoAtual === "ENSINAMENTO" ? "chip-green" : "chip-yellow");
        modoChip.textContent = `Modo: ${modoAtual}`;
    }
    const pausaChip = $("pausaAtualChip");
    if (pausaChip) {
        pausaChip.className = "chip " + (pausado ? "chip-yellow" : "chip-green");
        pausaChip.textContent = pausado ? "Pausado" : "Rodando";
    }
    const autoChip = $("autoAtualChip");
    if (autoChip) {
        autoChip.className = "chip " + (autoAtivo ? "chip-green" : "chip-yellow");
        autoChip.textContent = autoAtivo ? "Auto ligado" : "Auto desligado";
    }

    setControlButtonState("pauseButton", pausado ? "is-danger-active" : "");
    setControlButtonState("resumeButton", !pausado ? "is-safe-active" : "");
    setControlButtonState("autoOnButton", autoAtivo ? "is-safe-active" : "");
    setControlButtonState("autoOffButton", !autoAtivo ? "is-danger-active" : "");
    renderModeWritePolicy();
}

function setControlButtonState(id, activeClass) {
    const button = $(id);
    if (!button) return;
    button.classList.remove("is-active", "is-safe-active", "is-danger-active");
    if (activeClass) {
        button.classList.add(activeClass);
    }
}

function bindEvents() {
    $("refreshButton").addEventListener("click", refreshCore);
    $("reloadLogsButton").addEventListener("click", loadLogs);
    $("loadInboxButton").addEventListener("click", carregarConversasInbox);
    $("auditInboxButton").addEventListener("click", auditarInboxLeitura);
    $("syncInboxButton").addEventListener("click", sincronizarInboxPendencia);
    $("syncProjectsButton").addEventListener("click", sincronizarProjetosPendencia);
    $("platformFilterSelect").addEventListener("change", (event) => setPlatformFilter(event.target.value));
    $("mlLabCreateButton").addEventListener("click", createMlLabRun);
    $("mlLabStartButton").addEventListener("click", startMlLabRun);
    $("mlLabPauseButton").addEventListener("click", pauseMlLabRun);
    $("mlLabStopButton").addEventListener("click", stopMlLabRun);
    $("mlLabChatButton").addEventListener("click", sendMlLabChat);
    $("focusSelectedButton").addEventListener("click", focarSelecionada);
    $("clearFocusButton").addEventListener("click", limparFocoEnsino);
    $("panicButton").addEventListener("click", pausarTudo);
    $("pauseButton").addEventListener("click", pausarSistema);
    $("resumeButton").addEventListener("click", retomarSistema);
    $("autoOnButton").addEventListener("click", ativarAuto);
    $("autoOffButton").addEventListener("click", desativarAuto);
    $("modoSistemaSelect").addEventListener("change", alterarModoDashboard);
    $("closeRewriteButton").addEventListener("click", fecharReescrita);
    $("cancelRewriteButton").addEventListener("click", fecharReescrita);
    $("confirmRewriteButton").addEventListener("click", confirmarReescrita);

    document.querySelectorAll("#dashboardTabs .tab-btn").forEach((button) => {
        button.addEventListener("click", () => switchTab(button.dataset.tab));
    });
    document.querySelectorAll("#queueFilters .segment").forEach((button) => {
        button.addEventListener("click", () => setFilter(button.dataset.filter));
    });

    $("pendingQueue").addEventListener("click", (event) => {
        const item = event.target.closest("[data-action-id]");
        if (item) selectAction(item.dataset.actionId);
    });

    $("actionDetail").addEventListener("click", (event) => {
        const approve = event.target.closest("[data-approve-id]");
        const reject = event.target.closest("[data-reject-id]");
        const rewrite = event.target.closest("[data-rewrite-id]");
        const focus = event.target.closest("[data-focus-id]");
        const teach = event.target.closest("[data-teach-id]");
        const answer = event.target.closest("[data-answer-question-id]");
        if (approve) aprovarAcao(approve.dataset.approveId);
        if (reject) recusarAcao(reject.dataset.rejectId);
        if (rewrite) abrirReescrita(rewrite.dataset.rewriteId);
        if (focus) focarAcao(focus.dataset.focusId);
        if (teach) registrarContextoEnsino(teach.dataset.teachId);
        if (answer) responderDuvidaIa(answer.dataset.answerQuestionId);
    });

    $("listaConversasContainer").addEventListener("click", (event) => {
        const item = event.target.closest("[data-conversa-id]");
        if (!item) return;
        document.querySelectorAll(".conversation-row").forEach((row) => row.classList.remove("selected"));
        item.classList.add("selected");
        carregarMensagens(item.dataset.conversaId);
    });

    $("mlLabRuns").addEventListener("click", (event) => {
        const item = event.target.closest("[data-ml-run-id]");
        if (!item) return;
        state.mlLabSelectedRunId = Number(item.dataset.mlRunId);
        state.mlLabSelectedAgentKey = null;
        loadMlLabOverview(state.mlLabSelectedRunId);
    });

    $("mlLabAgents").addEventListener("click", (event) => {
        const item = event.target.closest("[data-ml-agent-key]");
        if (!item) return;
        state.mlLabSelectedAgentKey = item.dataset.mlAgentKey;
        renderMlLabAgents(state.mlLabOverview?.agents || [], state.mlLabOverview);
    });

    $("mlLabCandidates").addEventListener("click", (event) => {
        const item = event.target.closest("[data-ml-candidate-id]");
        if (!item) return;
        approveMlLabCandidate(item.dataset.mlCandidateId);
    });
}

document.addEventListener("DOMContentLoaded", () => {
    bindEvents();
    switchTab(initialTab());
    refreshCore();
    connectSSE();
    window.setInterval(refreshCore, 15000);
    window.setInterval(() => {
        if (state.activeTab === "ml-lab" && state.mlLabSelectedRunId) {
            loadMlLabOverview(state.mlLabSelectedRunId);
        }
    }, 10000);
});
