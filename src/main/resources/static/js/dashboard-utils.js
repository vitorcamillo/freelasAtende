const $ = (id) => document.getElementById(id);

// Mesmo token do /api/controle/** (CONTROLE_API_TOKEN). Fora do localhost o
// backend exige X-Control-Token; o operador grava uma vez no navegador:
// localStorage.setItem("controleApiToken", "<token>").
const CONTROLE_TOKEN_KEY = "controleApiToken";

function controleToken() {
    try {
        return window.localStorage.getItem(CONTROLE_TOKEN_KEY) || "";
    } catch (error) {
        return "";
    }
}

function authHeaders(headers = {}) {
    const token = controleToken();
    if (token) {
        headers["X-Control-Token"] = token;
    }
    return headers;
}

function escapeHtml(value) {
    return String(value ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#039;");
}

function formatDate(value) {
    if (!value) return "-";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return escapeHtml(value);
    return date.toLocaleString("pt-BR", { dateStyle: "short", timeStyle: "short" });
}

async function fetchJson(url) {
    const response = await fetch(url, { headers: authHeaders({ "Accept": "application/json" }) });
    if (!response.ok) throw new Error("HTTP " + response.status + " em " + url);
    return response.json();
}

async function postJson(url, body = {}) {
    const response = await fetch(url, {
        method: "POST",
        headers: authHeaders({ "Content-Type": "application/json", "Accept": "application/json" }),
        body: JSON.stringify(body)
    });
    if (!response.ok) throw new Error("HTTP " + response.status + " em " + url);
    return response.json();
}

function showToast(message) {
    const toast = $("toast");
    toast.textContent = message || "Operação concluída.";
    toast.classList.remove("hidden");
    window.clearTimeout(showToast.timer);
    showToast.timer = window.setTimeout(() => toast.classList.add("hidden"), 4500);
}

function typeLabel(value) {
    const labels = {
        ENVIAR_PROPOSTA: "Proposta",
        ENVIAR_PERGUNTA: "Pergunta",
        RESPONDER_CONVERSA: "Resposta inbox",
        CANCELAR_PROPOSTA: "Cancelamento"
    };
    return labels[value] || value || "-";
}

function platformLabel(item) {
    return item?.plataformaLabel || item?.plataforma || "99Freelas";
}

function platformKey(item) {
    return item?.plataforma || platformLabel(item);
}

function confidenceChip(value) {
    const normalized = String(value || "MEDIA").toUpperCase();
    if (normalized === "ALTA") return '<span class="chip chip-green">Alta</span>';
    if (normalized === "BLOQUEADA") return '<span class="chip chip-red">Bloqueada</span>';
    return '<span class="chip chip-yellow">Média</span>';
}

function qualityChip(action) {
    const score = Number(action.scoreConfianca ?? 0);
    const decisao = String(action.decisaoQualidade || "").toUpperCase();
    const klass = decisao === "BLOQUEAR" ? "chip-red" : score >= 82 ? "chip-green" : "chip-yellow";
    return `<span class="chip ${klass}">Q ${Number.isFinite(score) ? score : 0}</span>`;
}

function ensinoChip(action) {
    const status = String(action.ensinoStatus || "BLOQUEOU").toUpperCase();
    return status === "PASSOU"
        ? '<span class="chip chip-green">3 regras</span>'
        : '<span class="chip chip-yellow">ensino</span>';
}

function chipForStatus(status) {
    const normalized = String(status || "").toUpperCase();
    if (normalized.includes("BLOQUE")) return "chip-red";
    if (normalized.includes("PASS") || normalized.includes("PERMIT") || normalized.includes("ENVIAD")) return "chip-green";
    return "chip-yellow";
}
