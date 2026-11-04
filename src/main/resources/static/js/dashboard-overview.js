async function loadOverview() {
    try {
        const overview = await fetchJson("/api/controle/overview");
        state.overview = overview;
        renderOverview(overview);
    } catch (error) {
        renderOperationError(error);
    }
}

async function loadOps() {
    try {
        const ops = await fetchJson("/api/dashboard/ops");
        state.ops = ops;
        renderOps(ops);
    } catch (error) {
        renderOpsError(error);
    }
}

async function loadActions() {
    const actions = await fetchJson("/api/controle/acoes-pendentes");
    state.actions = actions || [];
    renderPlatformFilter();
    const visibleActions = filteredActions();
    if (state.selectedId && !visibleActions.some((action) => action.id === state.selectedId)) {
        state.selectedId = null;
    }
    if (!state.selectedId && visibleActions.length > 0) {
        state.selectedId = visibleActions[0].id;
    }
    renderActions();
    renderActionDetail(state.actions.find((action) => action.id === state.selectedId) || null);
}

function renderOverview(overview) {
    const canOperate = Boolean(overview.podeOperar);
    const writeEnabled = overview.escritaRealHabilitada !== false;
    const simulationEnabled = overview.simulacaoEnvioHabilitada === true && !writeEnabled;
    const auditSummary = overview.auditoriaInboxResumo || {};
    const operation = $("operationState");
    operation.classList.remove("safe", "warn", "error");
    operation.classList.add(writeEnabled ? "error" : (canOperate ? "safe" : "warn"));
    $("operationStateTitle").textContent = writeEnabled
        ? "Envio real liberado"
        : (canOperate ? (simulationEnabled ? "Simulação de envio" : "Leitura segura") : "Atenção");
    $("operationStateReason").textContent = overview.motivoOperacao || "-";

    $("kpiSessao").textContent = overview.sessaoValida ? "Válida" : "Inválida";
    $("kpiSessao").style.color = overview.sessaoValida ? "var(--green)" : "var(--red)";
    $("kpiPendencias").textContent = overview.totalPendencias ?? 0;
    $("kpiBloqueios").textContent = overview.totalBloqueios ?? 0;
    const realSends = overview.totalEnvios ?? 0;
    const simulatedSends = overview.totalEnviosSimulados ?? 0;
    $("kpiEnvios").textContent = simulatedSends > 0 ? `${realSends} real / ${simulatedSends} sim` : realSends;
    $("kpiErros").textContent = overview.errosHoje ?? 0;
    $("kpiConexoes").textContent = overview.statusConexoes || "-";
    renderTopConnections(overview.statusConexoes);
    const writeLockChip = $("writeLockChip");
    if (writeLockChip) {
        writeLockChip.className = "chip " + (writeEnabled ? "chip-red" : "chip-green");
        writeLockChip.textContent = writeEnabled ? "Escrita real ligada" : "Escrita real travada";
    }
    const simulationChip = $("simulationChip");
    if (simulationChip) {
        simulationChip.className = "chip " + (simulationEnabled ? "chip-green" : "chip-yellow");
        simulationChip.textContent = simulationEnabled ? "Envio simulado" : "Sem simulação";
    }
    renderModeWritePolicy();
    const auditPersistedChip = $("auditPersistedChip");
    if (auditPersistedChip) {
        auditPersistedChip.className = "chip " + (auditSummary.persistida ? "chip-green" : "chip-yellow");
        auditPersistedChip.textContent = auditSummary.persistida
            ? `Auditoria #${auditSummary.auditoriaId || "-"}`
            : "Auditoria pendente";
    }
    state.teachingFocus = overview.focoEnsino || null;
    renderTeachingFocus(state.teachingFocus);
    renderQualitySummary(overview.qualidadeOperacional || {});
    renderAudit("auditBlocks", overview.bloqueiosRecentes || [], true);
    renderAudit("auditSends", [
        ...(overview.enviosRecentes || []),
        ...(overview.simulacoesRecentes || [])
    ], false);
    renderLearning(overview);
}

function renderTopConnections(statusConexoes) {
    const topo = $("kpiConexoesTopo");
    const limite = $("kpiConexoesLimite");
    if (!topo || !limite) return;

    const texto = String(statusConexoes || "");
    const abertas = texto.match(/Conexoes abertas:\s*([^|]+)/i)?.[1]?.trim();
    const diario = texto.match(/Limite diario:\s*([^|]+)/i)?.[1]?.trim();
    const renovavel = texto.match(/Sessao renovavel:\s*([^|]+)/i)?.[1]?.trim();

    topo.textContent = abertas || "-";
    limite.textContent = [
        diario ? `Limite ${diario}` : null,
        renovavel ? `Renova ${renovavel}` : null
    ].filter(Boolean).join(" · ") || "Limite: -";
}

function renderModeWritePolicy() {
    const chip = $("modeWritePolicyChip");
    if (!chip) return;
    const modoAtual = state.modoSistema?.modo || "ENSINAMENTO";
    const overview = state.overview || {};
    const writeEnabled = overview.escritaRealHabilitada === true;
    const simulationEnabled = overview.simulacaoEnvioHabilitada === true && !writeEnabled;

    if (modoAtual === "ENSINAMENTO") {
        chip.className = "chip chip-green";
        chip.textContent = "Ensino trava envio direto";
        return;
    }
    if (writeEnabled) {
        chip.className = "chip chip-red";
        chip.textContent = `${modoAtual}: envio real liberado`;
        return;
    }
    if (simulationEnabled) {
        chip.className = "chip chip-yellow";
        chip.textContent = `${modoAtual}: envio simulado`;
        return;
    }
    chip.className = "chip chip-green";
    chip.textContent = `${modoAtual}: escrita travada`;
}

function renderOps(ops) {
    if (!$("opsSafetySummary")) return;
    const escrita = ops?.escrita || {};
    const fila = ops?.fila || {};
    const auditoria = ops?.auditoriaInbox || {};
    const alertas = Array.isArray(ops?.alertas) ? ops.alertas : [];
    const realWrite = escrita.realHabilitada === true;
    const simulated = escrita.simulacaoHabilitada === true && !realWrite;

    $("opsSafetySummary").textContent = realWrite ? "Atenção" : "Ativa";
    $("opsSafetySummary").style.color = realWrite ? "var(--red)" : "var(--green)";
    $("opsWriteSummary").textContent = realWrite ? "Real" : (simulated ? "Simulada" : "Travada");
    $("opsWriteSummary").style.color = realWrite ? "var(--red)" : "var(--green)";
    $("opsPendingSummary").textContent = `${escapeHtml(fila.pendencias ?? 0)} pend.`;
    $("opsAuditSummary").textContent = auditoria.persistida ? `#${escapeHtml(auditoria.auditoriaId || "-")}` : "Pendente";
    $("opsAuditSummary").style.color = auditoria.persistida ? "var(--green)" : "var(--yellow)";

    $("opsAlertList").innerHTML = alertas.length
        ? alertas.map((alerta) => {
            const nivel = String(alerta.nivel || "INFO").toUpperCase();
            const klass = nivel === "CRITICO" ? "chip-red" : nivel === "WARN" || nivel === "INFO" ? "chip-yellow" : "chip-green";
            return `
                <div class="audit-row">
                    <div class="queue-meta">
                        <span class="chip ${klass}">${escapeHtml(nivel)}</span>
                        <span class="chip chip-blue">${escapeHtml(alerta.titulo || "-")}</span>
                    </div>
                    <p class="queue-foot">${escapeHtml(alerta.mensagem || "-")}</p>
                </div>
            `;
        }).join("")
        : '<p class="muted">Sem alertas operacionais.</p>';
}

function renderOpsError(error) {
    if (!$("opsAlertList")) return;
    $("opsSafetySummary").textContent = "-";
    $("opsWriteSummary").textContent = "-";
    $("opsPendingSummary").textContent = "-";
    $("opsAuditSummary").textContent = "-";
    $("opsAlertList").innerHTML = `<p class="log-error">${escapeHtml(error.message)}</p>`;
}

function renderTeachingFocus(focus) {
    const panel = $("teachingFocusPanel");
    const active = Boolean(focus?.ativo);
    panel.classList.toggle("active", active);
    $("teachingFocusTitle").textContent = active
        ? `#${focus.acaoId || "-"} · ${focus.tituloProjeto || "Projeto em foco"}`
        : "Sem foco ativo";
    $("teachingFocusMeta").textContent = active
        ? `${typeLabel(focus.tipo)} · projeto ${focus.projetoId || "-"} · conversa ${focus.conversaId || "-"}`
        : "A fila continua segura.";
    $("teachingFocusText").textContent = active
        ? (focus.textoClientePreview || focus.resumoInterno || "Foco ativo sem texto ao cliente.")
        : "Aguardando projeto prioritário.";
}

function renderQualitySummary(quality) {
    const band = $("qualitySummaryBand");
    const score = Number(quality.scoreMedioFila ?? 0);
    const blocked = Number(quality.pendenciasBloqueada ?? 0);
    const rewrite = Number(quality.decisaoReescrever ?? 0);
    const risky = Number(quality.pendenciasComRisco ?? 0);
    const lowNatural = Number(quality.naturalidadeBaixa ?? 0);
    const reviewStrong = blocked + rewrite + risky;
    const shouldShow = reviewStrong > 0 || lowNatural > 0;
    band.hidden = !shouldShow;

    $("kpiQualidade").textContent = `${Number.isFinite(score) ? score : 0}/100`;
    $("kpiQualidade").style.color = blocked > 0 ? "var(--red)" : score >= 82 ? "var(--green)" : "var(--yellow)";
    $("kpiRevisaoForte").textContent = reviewStrong;
    $("kpiRevisaoForte").style.color = reviewStrong > 0 ? "var(--yellow)" : "var(--green)";
    $("qualitySummaryText").textContent = quality.resumo || "Itens que precisam de revisão forte.";
    $("qualitySummaryChips").innerHTML = [
        `<span class="chip chip-red">Bloqueada ${escapeHtml(blocked)}</span>`,
        `<span class="chip chip-yellow">Reescrever ${escapeHtml(rewrite)}</span>`,
        `<span class="chip chip-yellow">Naturalidade baixa ${escapeHtml(lowNatural)}</span>`
    ].join("");
}

function renderOperationError(error) {
    const operation = $("operationState");
    operation.classList.remove("safe", "warn");
    operation.classList.add("error");
    $("operationStateTitle").textContent = "Sem dados";
    $("operationStateReason").textContent = error.message;
    $("qualitySummaryText").textContent = "Não foi possível carregar a qualidade operacional.";
}

function renderAudit(containerId, items, blocked) {
    const container = $(containerId);
    if (!items.length) {
        container.innerHTML = '<p class="muted">Nenhum registro recente.</p>';
        return;
    }
    container.innerHTML = items.map((item) => `
        <div class="audit-row">
            <div class="queue-meta">
                <span class="chip ${blocked ? "chip-red" : "chip-green"}">${escapeHtml(item.status || "-")}</span>
                <span class="chip chip-blue">${escapeHtml(typeLabel(item.tipo))}</span>
                <span class="chip chip-yellow">${formatDate(item.data)}</span>
            </div>
            <p class="queue-foot">Projeto ${escapeHtml(item.projetoId || "-")} · Conversa ${escapeHtml(item.conversaId || "-")}</p>
            <p class="queue-preview">${escapeHtml(item.textoPreview || "-")}</p>
            ${item.motivoBloqueio ? `<p class="queue-foot" style="color: var(--red)">Motivo: ${escapeHtml(item.motivoBloqueio)}</p>` : ""}
        </div>
    `).join("");
}

function renderLearning(overview) {
    const aprendizado = overview.aprendizado || {};
    const health = overview.health || {};
    const erros = overview.errosRecentes || [];
    $("learningPanel").innerHTML = `
        <p class="section-kicker">IA</p>
        <pre class="json-box">${escapeHtml(JSON.stringify(aprendizado, null, 2))}</pre>
        <p class="section-kicker mt-3">Saúde</p>
        <pre class="json-box">${escapeHtml(JSON.stringify(health, null, 2))}</pre>
        <p class="section-kicker mt-3">Erros recentes</p>
        ${erros.length ? erros.map((erro) => `<p class="queue-foot">${escapeHtml(JSON.stringify(erro))}</p>`).join("") : '<p class="muted">Sem erros recentes.</p>'}
    `;
}
