async function loadLogs() {
    try {
        const logs = await fetchJson("/api/dashboard/logs?limite=80");
        state.loadedLogs = true;
        renderLogs(logs || []);
    } catch (error) {
        $("logsContainer").innerHTML = `<p class="log-error">${escapeHtml(error.message)}</p>`;
    }
}

function renderLogs(logs) {
    if (!logs.length) {
        $("logsContainer").innerHTML = '<p class="muted">Sem logs recentes.</p>';
        return;
    }
    $("logsContainer").innerHTML = logs.map(renderLogLine).join("");
}

function renderLogLine(log) {
    const type = String(log.tipo || "INFO").toUpperCase();
    const klass = type.includes("ERROR") ? "log-error" : type.includes("WARN") ? "log-warn" : "log-info";
    return `
        <div class="log-row">
            <span class="${klass}" style="font-weight: 800">${escapeHtml(type)}</span>
            <span class="queue-foot">${formatDate(log.timestamp)}</span>
            <p class="queue-preview">${escapeHtml(log.mensagem || "-")}</p>
        </div>
    `;
}

function connectSSE() {
    if (!window.EventSource) {
        $("sseStatusText").textContent = "Logs via polling";
        $("sseStatusText").className = "chip chip-yellow ml-auto";
        return;
    }
    // EventSource nao envia headers: o backend aceita ?token= somente nesta rota.
    const token = controleToken();
    const url = token
        ? "/api/dashboard/logs/stream?token=" + encodeURIComponent(token)
        : "/api/dashboard/logs/stream";
    state.eventSource = new EventSource(url);
    state.eventSource.onopen = () => {
        $("sseStatusText").textContent = "Logs ao vivo";
        $("sseStatusText").className = "chip chip-green ml-auto";
    };
    state.eventSource.addEventListener("log", (event) => {
        try {
            const log = JSON.parse(event.data);
            if (state.activeTab === "logs") {
                const container = $("logsContainer");
                if (container.querySelector(".muted")) {
                    container.innerHTML = "";
                }
                container.insertAdjacentHTML("afterbegin", renderLogLine(log));
            }
        } catch (error) {
            console.debug("Log SSE inválido", error);
        }
    });
    state.eventSource.onerror = () => {
        $("sseStatusText").textContent = "Logs instáveis";
        $("sseStatusText").className = "chip chip-yellow ml-auto";
    };
}

async function carregarConversasInbox() {
    const container = $("listaConversasContainer");
    container.innerHTML = '<div class="empty-state">Carregando inbox.</div>';
    try {
        const conversas = await fetchJson("/api/dashboard/conversas");
        state.loadedInbox = true;
        if (!conversas.length) {
            container.innerHTML = '<div class="empty-state">Nenhuma conversa encontrada.</div>';
            return;
        }
        container.innerHTML = conversas.map((conversa) => `
            <button class="conversation-row" type="button" data-conversa-id="${Number(conversa.idConversa)}">
                <p class="queue-title">${escapeHtml(conversa.nomeProjeto || "Sem projeto")}</p>
                <p class="queue-foot">${escapeHtml(conversa.nomeCliente || "-")}</p>
            </button>
        `).join("");
    } catch (error) {
        container.innerHTML = `<div class="empty-state log-error">${escapeHtml(error.message)}</div>`;
    }
}

async function sincronizarInboxPendencia() {
    const result = await postJson("/api/controle/sincronizar-inbox");
    showToast(result.mensagem || "Inbox sincronizada.");
    await refreshCore();
    await loadInboxAudit();
    await carregarConversasInbox();
}

async function auditarInboxLeitura() {
    const container = $("inboxAuditList");
    container.innerHTML = '<p class="muted">Auditando leitura read-only.</p>';
    const audit = await postJson("/api/controle/auditar-inbox");
    renderInboxAudit(audit);
    showToast(`Inbox auditada: ${audit.totalConversas || 0} conversas, ${audit.totalMensagens || 0} mensagens.`);
}

async function loadInboxAudit() {
    try {
        const audit = await fetchJson("/api/controle/auditoria-inbox");
        state.loadedInboxAudit = true;
        renderInboxAudit(audit);
    } catch (error) {
        $("inboxAuditList").innerHTML = `<p class="log-error">${escapeHtml(error.message)}</p>`;
    }
}

function renderInboxAudit(audit) {
    const summary = $("inboxAuditSummary");
    const list = $("inboxAuditList");
    const conversas = audit?.conversas || [];
    const painel = audit?.painelCenarios || {};
    summary.innerHTML = `
        <p>${escapeHtml(audit?.motivoIa || "Auditoria de inbox.")}</p>
        <div class="chip-row">
            <span class="chip ${audit?.persistida ? "chip-green" : "chip-yellow"}">Persistida ${audit?.persistida ? `#${escapeHtml(audit?.auditoriaId || "-")}` : "nao"}</span>
            <span class="chip chip-blue">Conversas ${escapeHtml(audit?.totalConversas ?? 0)}</span>
            <span class="chip chip-blue">Mensagens ${escapeHtml(audit?.totalMensagens ?? 0)}</span>
            <span class="chip chip-green">Cliente ${escapeHtml(audit?.totalUltimaCliente ?? 0)}</span>
            <span class="chip chip-yellow">Minhas ${escapeHtml(audit?.totalUltimaMinha ?? 0)}</span>
            <span class="chip chip-yellow">Sem texto ${escapeHtml(audit?.totalSemTextoUtil ?? 0)}</span>
            <span class="chip chip-yellow">Perguntou dono ${escapeHtml(painel.perguntouAoDono ?? 0)}</span>
            <span class="chip chip-yellow">Respondeu ${escapeHtml(painel.respondeu ?? 0)}</span>
            <span class="chip ${Number(painel.vazouContexto || 0) > 0 ? "chip-red" : "chip-green"}">Vazou contexto ${escapeHtml(painel.vazouContexto ?? 0)}</span>
            <span class="chip ${audit?.leituraSemMarcarVisualizada ? "chip-green" : "chip-red"}">Read-only ${audit?.leituraSemMarcarVisualizada ? "sim" : "nao"}</span>
            <span class="chip ${audit?.telegramSimulado ? "chip-green" : "chip-yellow"}">Telegram ${audit?.telegramSimulado ? "simulado" : "real"}</span>
        </div>
        ${audit?.relatorioHtml ? `<p class="queue-foot mt-2">Relatório local: ${escapeHtml(audit.relatorioHtml)}</p>` : ""}
    `;
    if (!conversas.length) {
        list.innerHTML = '<p class="muted">Nenhuma auditoria por conversa carregada.</p>';
        return;
    }
    list.innerHTML = conversas.map((item) => {
        const actionable = item.acionavel || item.pendenciaExistente;
        const chip = item.pendenciaExistente ? "chip-green" : actionable ? "chip-yellow" : "chip-blue";
        return `
            <div class="audit-row">
                <div class="queue-meta">
                    <span class="chip ${chip}">${escapeHtml(item.status || "-")}</span>
                    <span class="chip chip-blue">${escapeHtml(platformLabel(item))}</span>
                    <span class="chip chip-blue">${escapeHtml(item.decisaoIa || "-")}</span>
                    <span class="chip chip-blue">#${escapeHtml(item.conversaId || "-")}</span>
                    <span class="chip chip-yellow">${escapeHtml(item.totalMensagens ?? 0)} msgs</span>
                    <span class="chip chip-yellow">Última ${escapeHtml(item.ultimaMensagemAutor || "-")}</span>
                    ${item.scoreRisco != null ? `<span class="chip ${Number(item.scoreRisco) >= 50 ? "chip-red" : "chip-green"}">Risco ${escapeHtml(item.scoreRisco)}</span>` : ""}
                </div>
                <p class="queue-title">${escapeHtml(item.tituloProjeto || "Sem projeto")}</p>
                <p class="queue-foot">${escapeHtml(item.motivo || "-")}</p>
                <p class="queue-preview">${escapeHtml(item.analiseOperacional || "-")}</p>
                ${item.ultimaMensagemTexto ? `<p class="queue-foot">Gatilho: ${escapeHtml(item.ultimaMensagemTexto)}</p>` : ""}
                ${item.perguntaParaUsuario ? `<p class="queue-foot">Pergunta ao dono: ${escapeHtml(item.perguntaParaUsuario)}</p>` : ""}
                ${(item.duvidasInternas || []).length ? `<p class="queue-foot">Dúvidas internas: ${escapeHtml(item.duvidasInternas.join(" · "))}</p>` : ""}
                ${item.rascunhoPendente ? `<p class="queue-foot">Mensagem pendente: ${escapeHtml(item.rascunhoPendente)}</p>` : ""}
                ${item.decisaoQualidade ? `<p class="queue-foot">Qualidade: ${escapeHtml(item.decisaoQualidade)} · ${escapeHtml(item.comentarioQualidade || "")}</p>` : ""}
                ${(item.sinaisQualidade || []).length ? `<div class="chip-row mt-2">${item.sinaisQualidade.map((sinal) => `<span class="chip chip-yellow">${escapeHtml(sinal)}</span>`).join("")}</div>` : ""}
            </div>
        `;
    }).join("");
}

async function sincronizarProjetosPendencia() {
    const result = await postJson("/api/controle/sincronizar-projetos");
    showToast(result.mensagem || "Projetos sincronizados.");
    await refreshCore();
}

async function carregarMensagens(idConversa) {
    const container = $("chatMensagensContainer");
    container.innerHTML = '<div class="empty-state">Carregando mensagens.</div>';
    try {
        const mensagens = await fetchJson(`/api/dashboard/conversas/${idConversa}/mensagens`);
        if (!mensagens.length) {
            container.innerHTML = '<div class="empty-state">Sem mensagens nesta conversa.</div>';
            return;
        }
        container.innerHTML = mensagens.map((msg) => `
            <div class="message-row">
                <div class="queue-meta">
                    <span class="chip ${msg.enviadaPorMim ? "chip-blue" : "chip-green"}">${msg.enviadaPorMim ? "Você" : "Cliente"}</span>
                    <span class="chip chip-yellow">${msg.dataCriacaoMillis ? new Date(Number(msg.dataCriacaoMillis)).toLocaleString("pt-BR") : "-"}</span>
                </div>
                <p class="message-preview">${escapeHtml(msg.texto || "-")}</p>
            </div>
        `).join("");
    } catch (error) {
        container.innerHTML = `<div class="empty-state log-error">${escapeHtml(error.message)}</div>`;
    }
}
