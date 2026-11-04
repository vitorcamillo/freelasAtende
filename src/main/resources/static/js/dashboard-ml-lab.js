async function loadMlLabRuns() {
    const runs = await fetchJson("/api/ml-lab/runs");
    state.loadedMlLab = true;
    state.mlLabRuns = runs || [];
    if (!state.mlLabSelectedRunId && state.mlLabRuns.length) {
        state.mlLabSelectedRunId = state.mlLabRuns[0].id;
    }
    renderMlLabRuns();
    if (state.mlLabSelectedRunId) {
        await loadMlLabOverview(state.mlLabSelectedRunId);
    } else {
        renderMlLabEmpty();
    }
}

async function createMlLabRun() {
    const overview = await postJson("/api/ml-lab/runs", {
        totalCiclos: 100,
        objetivo: "Comparar selecao multi-criterio com 30 IAs, 8 workers, elite, repescagem e filhos protegidos",
        usarIaReal: true,
        maxTokensPorChamada: 1800,
        selectionMode: "MULTI_CRITERIA",
        parallelWorkers: 6,
        eliteCount: 6,
        repechageCount: 9,
        childProtectionCycles: 2
    });
    state.mlLabSelectedRunId = overview.id;
    showToast("ML Lab real criado com 30 IAs e 100 ciclos.");
    await loadMlLabRuns();
}

async function startMlLabRun() {
    if (!state.mlLabSelectedRunId) {
        showToast("Crie ou selecione um run antes de rodar.");
        return;
    }
    $("mlLabSummary").innerHTML = '<p>Rodando laboratorio real em background. Atualize para acompanhar ciclos, chamadas e ranking.</p><div class="chip-row"><span class="chip chip-yellow">30 agentes</span><span class="chip chip-yellow">100 ciclos</span><span class="chip chip-green">IA real</span><span class="chip chip-blue">8 workers</span><span class="chip chip-blue">multi-criterio</span></div>';
    const result = await postJson(`/api/ml-lab/runs/${state.mlLabSelectedRunId}/start`);
    showToast(result.mensagem || "ML Lab executado.");
    state.mlLabOverview = result.overview;
    renderMlLabOverview(result.overview);
    await loadMlLabRuns();
}

async function pauseMlLabRun() {
    if (!state.mlLabSelectedRunId) return;
    const result = await postJson(`/api/ml-lab/runs/${state.mlLabSelectedRunId}/pause`);
    showToast(result.mensagem || "ML Lab pausado.");
    renderMlLabOverview(result.overview);
}

async function stopMlLabRun() {
    if (!state.mlLabSelectedRunId) return;
    const result = await postJson(`/api/ml-lab/runs/${state.mlLabSelectedRunId}/stop`);
    showToast(result.mensagem || "ML Lab parado.");
    renderMlLabOverview(result.overview);
}

async function loadMlLabOverview(runId) {
    const overview = await fetchJson(`/api/ml-lab/runs/${runId}/overview`);
    state.mlLabOverview = overview;
    state.mlLabSelectedRunId = overview.id;
    if (!state.mlLabSelectedAgentKey && overview.agents?.length) {
        state.mlLabSelectedAgentKey = overview.agents[0].agentKey;
    }
    renderMlLabOverview(overview);
}

function renderMlLabEmpty() {
    $("mlLabSummary").innerHTML = '<p>Nenhum laboratorio criado.</p><div class="chip-row"><span class="chip chip-green">Escrita externa bloqueada</span></div>';
    $("mlLabMetrics").innerHTML = `
        <div class="metric-card"><span>Ciclos</span><strong>-</strong></div>
        <div class="metric-card"><span>Geração</span><strong>-</strong></div>
        <div class="metric-card"><span>Chamadas IA</span><strong>-</strong></div>
        <div class="metric-card"><span>Fallback</span><strong>-</strong></div>
        <div class="metric-card"><span>Melhor IA</span><strong>-</strong></div>
    `;
    $("mlLabProgressBar").style.width = "0%";
    $("mlLabRuns").innerHTML = '<p class="muted">Use Criar 100 ciclos.</p>';
    $("mlLabAgents").innerHTML = '<p class="muted">Sem agentes.</p>';
    $("mlLabCandidates").innerHTML = '<p class="muted">Sem candidatas.</p>';
}

function renderMlLabRuns() {
    const container = $("mlLabRuns");
    if (!state.mlLabRuns.length) {
        container.innerHTML = '<p class="muted">Nenhum run criado.</p>';
        return;
    }
    container.innerHTML = state.mlLabRuns.map((run) => `
        <button class="conversation-row ${run.id === state.mlLabSelectedRunId ? "selected" : ""}" type="button" data-ml-run-id="${Number(run.id)}">
            <p class="queue-title">Run #${escapeHtml(run.id)} · ${escapeHtml(run.status || "-")}</p>
            <p class="queue-foot">${escapeHtml(run.ciclosConcluidos ?? 0)}/${escapeHtml(run.totalCiclos ?? 0)} ciclos · melhor ${escapeHtml(run.bestAgentKey || "-")}</p>
        </button>
    `).join("");
}

function renderMlLabOverview(overview) {
    if (!overview) {
        renderMlLabEmpty();
        return;
    }
    state.mlLabOverview = overview;
    const pct = overview.totalCiclos ? Math.round((overview.ciclosConcluidos / overview.totalCiclos) * 100) : 0;
    const statusClass = String(overview.status || "").toUpperCase() === "RODANDO" ? "chip-green" : "chip-yellow";
    const fallbackClass = Number(overview.deterministicFallbackTotal || 0) === 0 ? "chip-green" : "chip-red";
    $("mlLabSummary").innerHTML = `
        <div>
            <p class="section-kicker">Run #${escapeHtml(overview.id)} · ${escapeHtml(overview.status || "-")}</p>
            <h3>${escapeHtml(overview.objetivo || "Laboratorio evolutivo")}</h3>
        </div>
        <div class="chip-row">
            <span class="chip ${statusClass}">${escapeHtml(overview.status || "-")}</span>
            <span class="chip chip-blue">${escapeHtml(overview.totalAgentes || 0)} IAs</span>
            <span class="chip ${overview.realIaEnabled ? "chip-green" : "chip-yellow"}">IA ${overview.realIaEnabled ? "real" : "offline"}</span>
            <span class="chip chip-blue">${escapeHtml(overview.iaProviderName || "-")}/${escapeHtml(overview.iaModelName || "-")}</span>
            <span class="chip chip-blue">${escapeHtml(overview.selectionMode || "TOP10")}</span>
            <span class="chip chip-green">Melhor ${escapeHtml(overview.bestAgentKey || "-")} · ${escapeHtml(overview.bestScore || 0)}</span>
            <span class="chip chip-blue">${escapeHtml(overview.parallelWorkers || 1)} workers</span>
            <span class="chip chip-blue">G${escapeHtml(overview.geracaoAtual || 1)} · ${escapeHtml(overview.evolucoesTotal || 0)} evoluções</span>
            <span class="chip ${fallbackClass}">Fallback ${escapeHtml(overview.deterministicFallbackTotal || 0)}</span>
            <span class="chip ${overview.externalWritesBlocked ? "chip-green" : "chip-red"}">Envio externo ${overview.externalWritesBlocked ? "bloqueado" : "ativo"}</span>
        </div>
        <p class="queue-foot mt-2">${escapeHtml(overview.modoEvolucao || "-")}</p>
        <p class="queue-foot mt-2">${escapeHtml(overview.corpusSummary || "-")}</p>
    `;
    $("mlLabMetrics").innerHTML = `
        <div class="metric-card ml-lab-metric-primary">
            <span>Ciclos</span>
            <strong>${escapeHtml(overview.ciclosConcluidos || 0)}/${escapeHtml(overview.totalCiclos || 0)}</strong>
            <small>${pct}% completo</small>
        </div>
        <div class="metric-card">
            <span>Geração</span>
            <strong>G${escapeHtml(overview.geracaoAtual || 1)}</strong>
            <small>${escapeHtml(overview.evolucoesTotal || 0)} evoluções</small>
        </div>
        <div class="metric-card">
            <span>Chamadas IA</span>
            <strong>${escapeHtml(overview.iaCallsTotal || 0)}</strong>
            <small>${escapeHtml(overview.iaProviderName || "-")}</small>
        </div>
        <div class="metric-card">
            <span>Fallback</span>
            <strong>${escapeHtml(overview.deterministicFallbackTotal || 0)}</strong>
            <small>${Number(overview.deterministicFallbackTotal || 0) === 0 ? "sem queda" : "revisar"}</small>
        </div>
        <div class="metric-card">
            <span>Melhor IA</span>
            <strong>${escapeHtml(overview.bestAgentKey || "-")}</strong>
            <small>score ${escapeHtml(overview.bestScore || 0)}</small>
        </div>
        <div class="metric-card">
            <span>Seleção</span>
            <strong>${escapeHtml(overview.selectionMode || "TOP10")}</strong>
            <small>elite ${escapeHtml(overview.eliteCount || 10)} · repesc. ${escapeHtml(overview.repechageCount || 0)}</small>
        </div>
    `;
    $("mlLabProgressBar").style.width = `${Math.max(0, Math.min(100, pct))}%`;
    renderMlLabAgents(overview.agents || [], overview);
    renderMlLabCandidates(overview.candidates || []);
}

function mlLabAgentKind(agent) {
    const perfil = String(agent.perfil || "").toLowerCase();
    const variante = String(agent.variante || "").toLowerCase();
    if (variante.includes("sobrevivente")) return "sobrevivente";
    if (variante.includes("mutacao") || perfil.includes(" filho ")) return "filho";
    return "base";
}

function mlLabParentKeys(agent) {
    const text = `${agent.perfil || ""} ${agent.linhagem || ""}`;
    const match = text.match(/filho(?: de)?\s+([A-Z0-9-]+)\s*\+\s*([A-Z0-9-]+)/i);
    return match ? [match[1], match[2]] : [];
}

function mlLabRecentWins(overview) {
    const wins = new Map();
    (overview?.recentCycles || []).forEach((cycle) => {
        if (!cycle.bestAgentKey) return;
        wins.set(cycle.bestAgentKey, (wins.get(cycle.bestAgentKey) || 0) + 1);
    });
    return wins;
}

function mlLabBuildDiagnostics(agents, overview) {
    const byKey = new Map(agents.map((agent) => [agent.agentKey, agent]));
    const wins = mlLabRecentWins(overview);
    return agents.map((agent, index) => {
        const kind = mlLabAgentKind(agent);
        const score = Number(agent.scoreTotal || 0);
        const respostas = Number(agent.respostasTotal || 0);
        const pais = mlLabParentKeys(agent);
        const parentScores = pais
            .map((key) => byKey.get(key))
            .filter(Boolean)
            .map((parent) => Number(parent.scoreTotal || 0))
            .filter((value) => value > 0);
        const parentAvg = parentScores.length
            ? parentScores.reduce((sum, value) => sum + value, 0) / parentScores.length
            : null;
        const parentDelta = parentAvg === null || respostas === 0 ? null : Number((score - parentAvg).toFixed(1));
        const backendDelta = Number(agent.parentDelta || 0);
        const winCount = wins.get(agent.agentKey) || 0;
        const rank = index + 1;
        let status = agent.selectionBand || "Em teste";
        if (!agent.selectionBand) {
            if (rank <= 3) status = "Campeao agora";
            else if (rank <= 10) status = "Top 10 vivo";
            else if (kind === "filho") status = "Filho em prova";
            else status = "Zona de corte";
        }

        let trend = "Sem historico comparativo ainda";
        if (agent.protectedCyclesLeft > 0) {
            trend = `Protegido por ${agent.protectedCyclesLeft} ciclo(s)`;
        } else if (winCount > 0) {
            trend = `Venceu ${winCount} ciclo(s) recente(s)`;
        } else if (kind === "filho" && respostas === 0) {
            trend = "Criado na ultima mutacao, aguardando primeiro teste";
        } else if (agent.parentDelta !== undefined && agent.parentDelta !== null) {
            trend = backendDelta >= 0 ? `Acima dos pais +${backendDelta}` : `Abaixo dos pais ${backendDelta}`;
        } else if (parentDelta !== null) {
            trend = parentDelta >= 0 ? `Acima dos pais +${parentDelta}` : `Abaixo dos pais ${parentDelta}`;
        } else if (rank <= 10) {
            trend = "Sobreviveu pelo ranking";
        }

        const foco = agent.regras?.[0] || agent.duvidas?.[0] || agent.variante || "-";
        const origem = pais.length ? `Pais: ${pais.join(" + ")}` : (agent.linhagem || "Base original");
        return { agent, rank, kind, score, respostas, pais, parentAvg, parentDelta: agent.parentDelta ?? parentDelta, winCount, status, trend, foco, origem };
    });
}

function renderMlLabInsightColumn(title, diagnostics, emptyText) {
    const rows = diagnostics.length
        ? diagnostics.map((item) => `
            <div class="ml-insight-row">
                <div>
                    <strong>#${escapeHtml(item.rank)} ${escapeHtml(item.agent.agentKey)}</strong>
                    <span>${escapeHtml(item.status)}</span>
                </div>
                <p>${escapeHtml(item.trend)}</p>
            </div>
        `).join("")
        : `<p class="muted">${escapeHtml(emptyText)}</p>`;
    return `
        <section class="ml-insight-card">
            <h4>${escapeHtml(title)}</h4>
            ${rows}
        </section>
    `;
}

function renderMlLabInsights(diagnostics) {
    const champions = diagnostics.slice(0, 5);
    const improving = diagnostics
        .filter((item) => item.winCount > 0 || (item.parentDelta !== null && item.parentDelta >= 0))
        .slice(0, 5);
    const children = diagnostics
        .filter((item) => item.kind === "filho")
        .slice(0, 5);
    const risk = diagnostics
        .filter((item) => item.respostas === 0 || item.score < 70 || item.status === "CORTE")
        .slice(0, 5);
    return `
        <div class="ml-lab-insights">
            ${renderMlLabInsightColumn("Melhores agora", champions, "Sem ranking")}
            ${renderMlLabInsightColumn("Melhorando", improving, "Ainda sem ganho provado")}
            ${renderMlLabInsightColumn("Filhos/mutacoes", children, "Sem filhos ativos")}
            ${renderMlLabInsightColumn("Perdendo corte", risk, "Sem alerta")}
        </div>
    `;
}

function renderMlLabAgents(agents, overview = state.mlLabOverview) {
    const container = $("mlLabAgents");
    if (!agents.length) {
        container.innerHTML = '<p class="muted">Sem agentes.</p>';
        return;
    }
    if (!state.mlLabSelectedAgentKey || !agents.some((agent) => agent.agentKey === state.mlLabSelectedAgentKey)) {
        state.mlLabSelectedAgentKey = agents[0].agentKey;
    }
    const diagnostics = mlLabBuildDiagnostics(agents, overview);
    const byKey = new Map(diagnostics.map((item) => [item.agent.agentKey, item]));
    container.innerHTML = `
        ${renderMlLabInsights(diagnostics)}
        <div class="ml-agent-list">
            ${agents.map((agent) => {
                const item = byKey.get(agent.agentKey);
                return `
        <button class="conversation-row ml-agent-row ${agent.agentKey === state.mlLabSelectedAgentKey ? "selected" : ""}" type="button" data-ml-agent-key="${escapeHtml(agent.agentKey)}">
            <div class="queue-meta">
                <span class="chip chip-blue">${escapeHtml(agent.agentKey)}</span>
                <span class="chip chip-blue">G${escapeHtml(agent.geracao || 1)}</span>
                <span class="chip chip-green">${escapeHtml(agent.scoreTotal || 0)}</span>
                <span class="chip chip-blue">fit ${escapeHtml(agent.fitnessScore || 0)}</span>
                <span class="chip chip-yellow">${escapeHtml(agent.respostasTotal || 0)} resp.</span>
                <span class="chip ${item?.status === "CORTE" ? "chip-red" : "chip-green"}">#${escapeHtml(item?.rank || "-")}</span>
            </div>
            <p class="queue-title">${escapeHtml(agent.perfil || "-")}</p>
            <p class="queue-foot">${escapeHtml(agent.variante || "-")}</p>
            <div class="ml-agent-diagnosis">
                <strong>${escapeHtml(item?.status || "Em teste")}</strong>
                <span>${escapeHtml(agent.selectionReason || item?.trend || "-")}</span>
            </div>
            <p class="queue-foot">Origem: ${escapeHtml(item?.origem || "-")}</p>
            <p class="queue-preview">${escapeHtml(agent.identidadeFinal || agent.ultimaResposta || agent.estrategia || "-")}</p>
            <p class="queue-foot">Linhagem: ${escapeHtml(agent.linhagem || "-")}</p>
            <p class="queue-foot">Regra final: ${escapeHtml(agent.regras?.[0] || "-")}</p>
        </button>
                `;
            }).join("")}
        </div>
    `;
}

function renderMlLabCandidates(candidates) {
    const container = $("mlLabCandidates");
    if (!candidates.length) {
        container.innerHTML = '<p class="muted">Sem regras candidatas.</p>';
        return;
    }
    container.innerHTML = candidates.map((candidate) => `
        <div class="audit-row">
            <div class="queue-meta">
                <span class="chip chip-blue">#${escapeHtml(candidate.id)}</span>
                <span class="chip chip-blue">${escapeHtml(candidate.agentKey || "-")}</span>
                <span class="chip chip-green">${escapeHtml(candidate.score || 0)}</span>
                <span class="chip ${candidate.status === "APROVADA" ? "chip-green" : "chip-yellow"}">${escapeHtml(candidate.status || "-")}</span>
            </div>
            <p class="queue-preview">${escapeHtml(candidate.regra || "-")}</p>
            <button class="btn btn-ghost" type="button" data-ml-candidate-id="${Number(candidate.id)}">Aprovar no lab</button>
        </div>
    `).join("");
}

async function sendMlLabChat() {
    if (!state.mlLabSelectedRunId || !state.mlLabSelectedAgentKey) {
        showToast("Selecione um run e uma IA.");
        return;
    }
    const mensagem = $("mlLabChatInput").value.trim();
    if (!mensagem) {
        showToast("Digite uma pergunta para a IA.");
        return;
    }
    const response = await postJson(`/api/ml-lab/runs/${state.mlLabSelectedRunId}/agents/${state.mlLabSelectedAgentKey}/chat`, { mensagem });
    $("mlLabChatOutput").textContent = response.resposta || "Sem resposta.";
}

async function approveMlLabCandidate(candidateId) {
    const result = await postJson(`/api/ml-lab/candidates/${candidateId}/approve`);
    showToast(result.mensagem || "Regra aprovada no laboratorio.");
    renderMlLabOverview(result.overview);
}
