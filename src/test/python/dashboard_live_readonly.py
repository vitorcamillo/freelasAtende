import json
import os
from pathlib import Path

from playwright.sync_api import expect, sync_playwright


BASE_URL = os.environ.get("DASHBOARD_BASE_URL", "http://127.0.0.1:18080")
REPORT_PATH = Path(os.environ.get("DASHBOARD_LIVE_REPORT", "target/dashboard-live-readonly-report.json"))


def api_json(page, path):
    response = page.request.get(f"{BASE_URL}{path}", timeout=60_000)
    assert response.ok, f"{path} HTTP {response.status}: {response.text()[:500]}"
    return response.json()


def main():
    report = {
        "baseUrl": BASE_URL,
        "writesToClient": False,
        "usesMocks": False,
        "checks": [],
    }

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 1000})

        session = api_json(page, "/api/auth/session")
        report["session"] = {"sessaoValida": bool(session.get("sessaoValida"))}
        assert session.get("sessaoValida") is True, "Sessao 99Freelas invalida. Atualize data/cookies.json ou POST /api/auth/cookies."
        report["checks"].append("sessao_99freelas_valida")

        status = api_json(page, "/api/status")
        report["status99Freelas"] = status.get("status99Freelas")
        assert status.get("status99Freelas") == "LOGADO", f"Status 99Freelas inesperado: {status}"
        report["checks"].append("status_99freelas_logado")

        overview = api_json(page, "/api/controle/overview")
        report["overview"] = {
            "totalPendencias": overview.get("totalPendencias"),
            "totalBloqueios": overview.get("totalBloqueios"),
            "totalEnvios": overview.get("totalEnvios"),
            "totalEnviosSimulados": overview.get("totalEnviosSimulados"),
            "podeOperar": overview.get("podeOperar"),
            "escritaRealHabilitada": overview.get("escritaRealHabilitada"),
            "simulacaoEnvioHabilitada": overview.get("simulacaoEnvioHabilitada"),
            "motivoOperacao": overview.get("motivoOperacao"),
        }
        assert overview.get("escritaRealHabilitada") is False, "Dry-run exige real.write.enabled=false."
        assert overview.get("simulacaoEnvioHabilitada") is False, "Read-only puro exige real.write.simulate=false."
        assert overview.get("podeOperar") is False, "Dashboard read-only nao deve operar com escrita real desligada."
        assert "aprendizado" in overview
        assert "qualidadeOperacional" in overview
        report["checks"].append("overview_real_carregado")
        report["checks"].append("escrita_real_bloqueada_no_overview")

        actions = api_json(page, "/api/controle/acoes-pendentes")
        report["pendingActionsCount"] = len(actions)
        assert isinstance(actions, list)
        report["checks"].append("pendencias_reais_carregadas")

        conversas = api_json(page, "/api/dashboard/conversas")
        report["inboxConversationsCount"] = len(conversas)
        assert isinstance(conversas, list)
        report["checks"].append("inbox_99freelas_real_carregada")

        page.goto(f"{BASE_URL}/dashboard", wait_until="domcontentloaded")
        expect(page.locator("#controlCenterApp")).to_be_visible()
        expect(page.locator("#dashboardTabs")).to_be_visible()
        report["checks"].append("dashboard_renderizado")

        page.locator('[data-tab="inbox"]').click()
        expect(page.locator('[data-panel="inbox"]')).to_be_visible()
        page.locator("#loadInboxButton").click()
        page.wait_for_selector("#listaConversasContainer .conversation-row, #listaConversasContainer .empty-state", timeout=60_000)
        inbox_text = page.locator("#listaConversasContainer").inner_text(timeout=10_000)
        assert "Clique em carregar inbox" not in inbox_text
        report["checks"].append("botao_inbox_dashboard_aciona_endpoint_real")

        page.locator('[data-tab="operar"]').click()
        expect(page.locator('[data-panel="operar"]')).to_be_visible()
        expect(page.locator("#lastUpdate")).to_contain_text("Atualizado em", timeout=20_000)
        report["checks"].append("operar_atualiza_sem_erro")

        browser.close()

    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
