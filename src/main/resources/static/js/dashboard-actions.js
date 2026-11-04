function setFilter(filter) {
    state.filter = filter;
    document.querySelectorAll("#queueFilters .segment").forEach((button) => {
        button.classList.toggle("active", button.dataset.filter === filter);
    });
    alignSelectionWithCurrentFilters();
    renderActions();
    renderActionDetail(state.actions.find((action) => action.id === state.selectedId) || null);
}

function setPlatformFilter(filter) {
    state.platformFilter = filter || "TODAS";
    alignSelectionWithCurrentFilters();
    renderActions();
    renderActionDetail(state.actions.find((action) => action.id === state.selectedId) || null);
}

function renderPlatformFilter() {
    const select = $("platformFilterSelect");
    if (!select) return;
    const current = state.platformFilter || "TODAS";
    const options = [...new Map(state.actions.map((action) => [
        platformKey(action),
        platformLabel(action)
    ])).entries()];
    select.innerHTML = `
        <option value="TODAS">Todas plataformas</option>
        ${options.map(([value, label]) => `<option value="${escapeHtml(value)}">${escapeHtml(label)}</option>`).join("")}
    `;
    select.value = options.some(([value]) => value === current) ? current : "TODAS";
    state.platformFilter = select.value;
}

function alignSelectionWithCurrentFilters() {
    const visible = filteredActions();
    if (state.selectedId && visible.some((action) => action.id === state.selectedId)) {
        return;
    }
    state.selectedId = visible.length ? visible[0].id : null;
}

function filteredActions() {
    return state.actions.filter((action) => {
        const matchesType = state.filter === "TODAS" || action.tipo === state.filter;
        const matchesPlatform = state.platformFilter === "TODAS" || platformKey(action) === state.platformFilter;
        return matchesType && matchesPlatform;
    });
}

function renderActions() {
    const container = $("pendingQueue");
    const actions = filteredActions();
    if (!actions.length) {
        container.innerHTML = '<div class="empty-state">Nenhuma pendência neste filtro.</div>';
        return;
    }
    container.innerHTML = actions.map((action) => {
        const selected = action.id === state.selectedId ? "selected" : "";
        const focused = action.focoEnsinoAtual ? "focused" : "";
        const risk = (action.sinaisRisco || []).length;
        const riskChip = risk ? `<span class="chip chip-yellow">${risk} alerta(s)</span>` : "";
        const focusChip = action.focoEnsinoAtual ? '<span class="chip chip-green">foco</span>' : "";
        const conversaObrigatoria = requiresTeachingConversation(action);
        const teachingTalkChip = conversaObrigatoria && !action.conversaEnsinoRespondida
            ? '<span class="chip chip-yellow">precisa ensino</span>'
            : "";
        const qualidadeCritica = String(action.decisaoQualidade || "").toUpperCase() === "BLOQUEAR";
        const qualidadeChip = qualidadeCritica ? qualityChip(action) : "";
        return `
            <button class="queue-item ${selected} ${focused}" type="button" data-action-id="${Number(action.id)}">
                <div class="queue-meta">
                    <span class="chip chip-blue">${escapeHtml(typeLabel(action.tipo))}</span>
                    <span class="chip chip-blue">${escapeHtml(platformLabel(action))}</span>
                    ${focusChip}
                    ${teachingTalkChip}
                    ${qualidadeChip}
                    ${riskChip}
                </div>
                <p class="queue-title">${escapeHtml(action.tituloProjeto || "Sem título")}</p>
                <p class="queue-preview">${escapeHtml(action.textoClientePreview || action.resumoInterno || "-")}</p>
                <p class="queue-foot">#${escapeHtml(action.id)} · ${formatDate(action.dataCriacao)}</p>
            </button>
        `;
    }).join("");
}

function selectAction(id) {
    state.selectedId = Number(id);
    renderActions();
    const action = state.actions.find((item) => item.id === state.selectedId);
    renderActionDetail(action || null);
}

function renderActionDetail(action) {
    const container = $("actionDetail");
    if (!action) {
        container.innerHTML = `
            <div class="empty-detail">
                <p class="section-kicker">Revisão</p>
                <h2>Selecione uma pendência</h2>
                <p>Escolha um item da fila para revisar a mensagem e decidir.</p>
            </div>
        `;
        return;
    }

    const riscos = action.sinaisRisco || [];
    const sinaisQualidade = action.sinaisQualidade || [];
    const motivos = action.motivosAprendizado?.length
        ? action.motivosAprendizado
        : ["BOM_TOM", "ESCOPO_CLARO", "ROBOTICO", "RISCO_PLATAFORMA", "DUPLICADO", "OUTRO"];
    const defaultMotivo = motivos.includes("BOM_TOM") ? "BOM_TOM" : motivos[0];
    const conversaObrigatoria = requiresTeachingConversation(action);
    const textoCliente = action.textoClientePreview || "-";
    const isPrimeiraAbordagem = textoCliente.trim() === ABORDAGEM_INICIAL;
    const fluxoAtual = isPrimeiraAbordagem
        ? "Abrir contato. Escopo, plano e proposta só vêm depois da resposta do cliente."
        : (action.proximaAcaoSugerida || action.perguntaParaUsuario || "Revise a mensagem antes de decidir.");
    const detalheResumo = [
        action.nivelConfianca ? `Confiança ${action.nivelConfianca}` : null,
        action.decisaoQualidade ? `Qualidade ${action.decisaoQualidade}` : null,
        riscos.length ? `${riscos.length} alerta(s)` : null
    ].filter(Boolean).join(" · ");

    container.innerHTML = `
        <div class="detail-shell">
            <div class="detail-header">
                <div class="queue-meta">
                    <span class="chip chip-blue">#${escapeHtml(action.id)}</span>
                    <span class="chip chip-blue">${escapeHtml(platformLabel(action))}</span>
                    <span class="chip chip-blue">${escapeHtml(typeLabel(action.tipo))}</span>
                    ${confidenceChip(action.nivelConfianca)}
                    ${qualityChip(action)}
                    ${ensinoChip(action)}
                </div>
                <h2>${escapeHtml(action.tituloProjeto || "Sem título")}</h2>
                <p class="queue-foot">Projeto ${escapeHtml(action.projetoId || "-")} · Conversa ${escapeHtml(action.conversaId || "-")} · ${formatDate(action.dataCriacao)}</p>
            </div>

            <div class="detail-scroll scroll-thin">
                <section class="primary-message-card">
                    <span class="field-label">Mensagem que vai para o cliente</span>
                    <p class="message-preview">${escapeHtml(textoCliente)}</p>
                </section>

                <section class="decision-brief">
                    <span class="field-label">Decisão agora</span>
                    <p>${escapeHtml(fluxoAtual)}</p>
                    ${detalheResumo ? `<small>${escapeHtml(detalheResumo)}</small>` : ""}
                </section>

                <details class="detail-section teaching-section" ${conversaObrigatoria && !action.conversaEnsinoRespondida ? "open" : ""}>
                    <summary>${conversaObrigatoria ? "Ensinar antes de aprovar" : "Ajustar ou ensinar"}</summary>
                    <div class="detail-body">
                        <textarea id="teachingContextText" class="field teach-field" rows="4" placeholder="Oriente a IA se precisar mudar tom, limite ou regra desta ação."></textarea>
                        <div class="teach-actions">
                            <button class="btn btn-primary" type="button" data-teach-id="${Number(action.id)}">Salvar orientação</button>
                            <button class="btn btn-ghost" type="button" data-focus-id="${Number(action.id)}">Focar conversa</button>
                        </div>
                    </div>
                </details>

                <details class="detail-section">
                    <summary>Ver leitura completa da IA</summary>
                    <div class="detail-body">
                        ${renderDecisionNotebook(action)}
                    </div>
                </details>

                <details class="detail-section">
                    <summary>Segurança e contexto</summary>
                    <div class="detail-body">
                        <p class="context-box">${escapeHtml(action.resumoInterno || "-")}</p>
                        ${renderConversationContext(action)}
                        <div class="mt-3">${renderChecklist(action)}</div>
                        <div class="metric-grid">
                            ${metric("Naturalidade", action.scoreNaturalidade ?? "-")}
                            ${metric("Clareza", action.scoreClareza ?? "-")}
                            ${metric("Risco", action.scoreRisco ?? "-")}
                            ${metric("Duplicidade", action.scoreDuplicidade ?? "-")}
                            ${metric("Decisão", action.decisaoQualidade || "-")}
                        </div>
                        <p class="context-box mt-3">${escapeHtml(action.comentarioQualidade || "-")}</p>
                        <div class="chip-row mt-2">
                            ${sinaisQualidade.length ? sinaisQualidade.map((sinal) => `<span class="chip chip-yellow">${escapeHtml(sinal)}</span>`).join("") : '<span class="chip chip-green">Texto fluido</span>'}
                            <span class="chip ${chipForStatus(action.statusGuardiao)}">Guardião ${escapeHtml(action.statusGuardiao || "-")}</span>
                            ${riscos.length ? riscos.map((risco) => `<span class="chip chip-yellow">${escapeHtml(risco)}</span>`).join("") : '<span class="chip chip-green">Sem alerta crítico</span>'}
                        </div>
                        ${action.motivoBloqueio ? `<p class="queue-foot" style="color: var(--red)">Motivo: ${escapeHtml(action.motivoBloqueio)}</p>` : ""}
                    </div>
                </details>

                <details class="detail-section">
                    <summary>Histórico</summary>
                    <div class="detail-body">
                        <p class="context-box">${escapeHtml(action.observacaoUsuario || "Ainda sem decisão humana.")}</p>
                    </div>
                </details>
            </div>

            <div class="detail-actions">
                <label class="motivo-wrap">
                    <span class="field-label">Motivo</span>
                    <select id="actionReasonSelect" class="field">
                        ${motivos.map((motivo) => `<option value="${escapeHtml(motivo)}" ${motivo === defaultMotivo ? "selected" : ""}>${escapeHtml(motivo)}</option>`).join("")}
                    </select>
                </label>
                <button class="btn btn-safe" type="button" data-approve-id="${Number(action.id)}">Aprovar</button>
                <button class="btn btn-danger" type="button" data-reject-id="${Number(action.id)}">Recusar</button>
                <button class="btn btn-primary" type="button" data-rewrite-id="${Number(action.id)}">Reescrever</button>
                <button class="btn btn-ghost" type="button" data-focus-id="${Number(action.id)}">Focar</button>
            </div>
        </div>
    `;
}

function renderDecisionNotebook(action) {
    const pergunta = action.perguntaParaUsuario || "";
    const regra = action.regraAprendizadoSugerida || "";
    const conversaObrigatoria = requiresTeachingConversation(action);
    const conversaStatus = conversaObrigatoria
        ? (action.conversaEnsinoRespondida
            ? "Respondida: agora a aprovação pode seguir pelas demais regras de segurança."
            : "Obrigatória: converse com a IA ou salve uma orientação antes de aprovar qualquer envio.")
        : "Não obrigatória neste item.";
    const rows = [
        ["Autonomia", action.nivelAutonomia || "RASCUNHA"],
        ["Entendeu", action.entendimentoIa || "Sem entendimento registrado."],
        ["Leitura da IA", action.analiseIa || action.entendimentoIa || "Sem leitura registrada."],
        ["Atitude sugerida", action.atitudeSugerida || action.proximaAcaoSugerida || "Revisar pendência."],
        ["Conversa de ensino", conversaStatus],
        ["Próxima ação", action.proximaAcaoSugerida || "Revisar pendência."],
        ["Pergunta para você", pergunta || "Aprovar, ajustar ou ensinar regra."],
        ["Regra possível", regra || "Sem regra sugerida."]
    ];
    return `
        <div class="decision-notebook">
            ${rows.map(([label, value]) => `
                <div class="decision-row">
                    <span class="field-label">${escapeHtml(label)}</span>
                    <p class="context-box">${escapeHtml(value)}</p>
                </div>
            `).join("")}
            ${decisionList("Informações confirmadas", action.informacoesConfirmadas)}
            ${decisionList("Informações pendentes", action.informacoesPendentes)}
            ${decisionList("Opções da IA", action.opcoesIa)}
            ${decisionList("Sei fazer", action.sabeFazer)}
            ${decisionList("Dúvidas internas", action.duvidasInternas)}
            ${decisionList("Perguntas ao cliente", action.perguntasParaCliente)}
            ${decisionList("Riscos", action.riscosDecisao)}
            ${pergunta ? `
                <div class="decision-row">
                    <span class="field-label">Responder dúvida</span>
                    <textarea id="iaQuestionAnswerText" class="field teach-field" rows="3" placeholder="Responda a dúvida da IA para ela reprocessar esta mensagem."></textarea>
                    <input id="iaQuestionRuleText" class="field mt-2" type="text" value="${escapeHtml(regra)}" placeholder="Regra confirmada para aprender daqui em diante">
                    <div class="teach-actions">
                        <button class="btn btn-primary" type="button" data-answer-question-id="${Number(action.id)}">Salvar resposta para IA</button>
                    </div>
                </div>
            ` : ""}
        </div>
    `;
}

function requiresTeachingConversation(action) {
    return Boolean(action?.conversaEnsinoObrigatoria)
        || (action?.modoOrigem === "ENSINAMENTO" && action?.tipo !== "CANCELAR_PROPOSTA");
}

function decisionList(label, items) {
    const values = Array.isArray(items) ? items.filter(Boolean) : [];
    if (!values.length) {
        return `
            <div class="decision-row">
                <span class="field-label">${escapeHtml(label)}</span>
                <p class="context-box">Nenhum item.</p>
            </div>
        `;
    }
    return `
        <div class="decision-row">
            <span class="field-label">${escapeHtml(label)}</span>
            <div class="chip-row">
                ${values.map((item) => `<span class="chip chip-blue">${escapeHtml(item)}</span>`).join("")}
            </div>
        </div>
    `;
}

function metric(label, value) {
    return `
        <div class="metric-card">
            <span>${escapeHtml(label)}</span>
            <strong>${escapeHtml(value)}</strong>
        </div>
    `;
}

function renderChecklist(action) {
    const regras = action.checklistRegras || [];
    if (!regras.length) {
        return '<p class="muted">Checklist indisponível.</p>';
    }
    return `
        <div class="chip-row">
            ${regras.map((regra) => `
                <span class="chip ${chipForStatus(regra.status)}" title="${escapeHtml(regra.motivo || "")}">
                    ${escapeHtml(regra.nome || regra.codigo || "-")}: ${escapeHtml(regra.status || "-")}
                </span>
            `).join("")}
        </div>
    `;
}

function renderConversationContext(action) {
    const gatilho = action.mensagemGatilhoTexto
        ? `<p class="queue-foot">Gatilho ${escapeHtml(action.mensagemGatilhoAutor || "-")}: ${escapeHtml(action.mensagemGatilhoTexto)}</p>`
        : "";
    const minhas = (action.ultimasMensagensMinhas || []).slice(0, 2)
        .map((msg) => `<p class="queue-foot">Eu: ${escapeHtml(msg.texto || "-")}</p>`).join("");
    const cliente = (action.ultimasMensagensCliente || []).slice(0, 2)
        .map((msg) => `<p class="queue-foot">Cliente: ${escapeHtml(msg.texto || "-")}</p>`).join("");
    const ignoradas = (action.mensagensIgnoradasResumo || []).slice(0, 2)
        .map((msg) => `<p class="queue-foot">Ignorada: ${escapeHtml(msg)}</p>`).join("");
    return gatilho || minhas || cliente || ignoradas
        ? `<div class="mt-3">${gatilho}${cliente}${minhas}${ignoradas}</div>`
        : "";
}

function selectedReason() {
    return $("actionReasonSelect")?.value?.trim() || "";
}

async function aprovarAcao(id) {
    const motivo = selectedReason();
    if (!motivo) {
        showToast("Escolha um motivo para ensinar antes de aprovar.");
        return;
    }
    const result = await postJson(`/api/controle/acoes/${id}/aprovar`, { motivoAprendizado: motivo });
    showToast(result.mensagem || "Ação aprovada.");
    await refreshCore();
}

async function recusarAcao(id) {
    const motivo = selectedReason();
    if (!motivo) {
        showToast("Escolha um motivo para ensinar antes de recusar.");
        return;
    }
    const result = await postJson(`/api/controle/acoes/${id}/recusar`, { motivoAprendizado: motivo });
    showToast(result.mensagem || "Ação recusada.");
    await refreshCore();
}

function abrirReescrita(id) {
    const action = state.actions.find((item) => item.id === Number(id));
    if (!action) return;
    $("rewriteActionId").value = id;
    $("rewriteText").value = action.textoClientePreview || "";
    $("rewriteComment").value = "FORA_DO_TOM";
    $("rewriteValidation").classList.add("hidden");
    $("rewriteValidation").textContent = "";
    $("rewriteModal").classList.remove("hidden");
    $("rewriteText").focus();
}

function fecharReescrita() {
    $("rewriteModal").classList.add("hidden");
}

async function confirmarReescrita() {
    const id = $("rewriteActionId").value;
    const texto = $("rewriteText").value.trim();
    const comentario = $("rewriteComment").value.trim();
    const validation = $("rewriteValidation");
    if (!texto) {
        validation.textContent = "Informe o texto ao cliente antes de validar.";
        validation.classList.remove("hidden");
        return;
    }
    if (!comentario) {
        validation.textContent = "Informe o motivo de aprendizado antes de salvar a reescrita.";
        validation.classList.remove("hidden");
        return;
    }
    try {
        const result = await postJson(`/api/controle/acoes/${id}/reescrever`, { texto, comentario });
        validation.textContent = result.mensagem || "Reescrita processada.";
        validation.classList.remove("hidden");
        await refreshCore();
        if (result.sucesso) {
            fecharReescrita();
            showToast(result.mensagem || "Mensagem reescrita e mantida pendente.");
        }
    } catch (error) {
        validation.textContent = error.message;
        validation.classList.remove("hidden");
    }
}

async function focarAcao(id) {
    if (!id) {
        showToast("Selecione uma pendência para focar.");
        return;
    }
    const focus = await postJson(`/api/controle/foco-ensino/acoes/${id}`);
    state.teachingFocus = focus;
    showToast(focus.mensagem || "Foco de ensino definido.");
    await refreshCore();
}

async function registrarContextoEnsino(id) {
    const field = $("teachingContextText");
    const texto = field?.value?.trim() || "";
    if (!texto) {
        showToast("Escreva uma orientação para a IA aprender neste projeto.");
        field?.focus();
        return;
    }
    const result = await postJson(`/api/controle/acoes/${id}/contexto`, { texto });
    showToast(result.mensagem || "Orientação salva e ação mantida pendente.");
    await refreshCore();
}

async function responderDuvidaIa(id) {
    const answerField = $("iaQuestionAnswerText");
    const ruleField = $("iaQuestionRuleText");
    const resposta = answerField?.value?.trim() || "";
    const regra = ruleField?.value?.trim() || "";
    if (!resposta) {
        showToast("Responda a dúvida da IA antes de reprocessar.");
        answerField?.focus();
        return;
    }
    const result = await postJson(`/api/controle/acoes/${id}/responder-duvida`, { resposta, regra });
    showToast(result.mensagem || "Resposta salva e mensagem mantida pendente.");
    await refreshCore();
}

async function focarSelecionada() {
    await focarAcao(state.selectedId);
}

async function limparFocoEnsino() {
    const focus = await postJson("/api/controle/foco-ensino/limpar");
    state.teachingFocus = focus;
    showToast(focus.mensagem || "Foco de ensino limpo.");
    await refreshCore();
}

async function pausarSistema() {
    await postJson("/api/modos/pausar");
    showToast("Sistema pausado.");
    await refreshCore();
}

async function pausarTudo() {
    await postJson("/api/modos/pausar");
    await postJson("/api/modos/auto/desativar");
    showToast("Kill switch acionado: sistema pausado e auto desligado.");
    await refreshCore();
}

async function retomarSistema() {
    await postJson("/api/modos/retomar");
    showToast("Sistema retomado.");
    await refreshCore();
}

async function ativarAuto() {
    await postJson("/api/modos/auto/ativar");
    showToast("Auto ativado.");
    await refreshCore();
}

async function desativarAuto() {
    await postJson("/api/modos/auto/desativar");
    showToast("Auto desativado.");
    await refreshCore();
}

async function alterarModoDashboard() {
    const modo = $("modoSistemaSelect").value;
    const response = await fetch(`/api/modos?modo=${encodeURIComponent(modo)}`, { method: "PUT", headers: authHeaders() });
    if (!response.ok) throw new Error("HTTP " + response.status + " ao alterar modo");
    showToast(`Modo alterado para ${modo}.`);
    await refreshCore();
}
