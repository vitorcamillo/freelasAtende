import json
import os
from pathlib import Path

from playwright.sync_api import expect, sync_playwright


BASE_URL = os.environ.get("DASHBOARD_BASE_URL", "http://127.0.0.1:18080")
REPORT_PATH = Path(os.environ.get("DASHBOARD_LIVE_PENDING_REPORT", "target/dashboard-live-pending-report.json"))


def api_json(page, method, path):
    request = page.request.post if method == "POST" else page.request.get
    response = request(f"{BASE_URL}{path}", timeout=120_000)
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

        session = api_json(page, "GET", "/api/auth/session")
        assert session.get("sessaoValida") is True, "Sessao 99Freelas invalida."
        report["checks"].append("sessao_99freelas_valida")

        before = api_json(page, "GET", "/api/controle/acoes-pendentes")
        report["pendingBefore"] = len(before)

        page.goto(f"{BASE_URL}/dashboard", wait_until="domcontentloaded")
        page.locator('[data-tab="inbox"]').click()
        expect(page.locator("#syncInboxButton")).to_be_visible()
        with page.expect_response("**/api/controle/sincronizar-inbox", timeout=180_000):
            page.locator("#syncInboxButton").click()
        expect(page.locator("#toast")).to_contain_text("Nada foi enviado", timeout=120_000)
        report["checks"].append("sync_inbox_button_executado")

        after = api_json(page, "GET", "/api/controle/acoes-pendentes")
        if len(after) == len(before):
            page.locator('[data-tab="operar"]').click()
            expect(page.locator("#syncProjectsButton")).to_be_visible()
            with page.expect_response("**/api/controle/sincronizar-projetos", timeout=180_000):
                page.locator("#syncProjectsButton").click()
            expect(page.locator("#toast")).to_contain_text("Nada foi enviado", timeout=180_000)
            report["checks"].append("sync_projects_button_executado")
            after = api_json(page, "GET", "/api/controle/acoes-pendentes")

        report["pendingAfter"] = len(after)
        report["newPending"] = max(0, len(after) - len(before))
        assert len(after) >= len(before), "Fila de pendencias diminuiu durante sincronizacao."

        if report["newPending"] > 0:
            action = after[0]
            assert action.get("status") == "PENDENTE"
            assert action.get("tipo") in ("RESPONDER_CONVERSA", "ENVIAR_PERGUNTA", "ENVIAR_PROPOSTA")
            assert action.get("modoOrigem") == "ENSINAMENTO"
            assert action.get("textoClientePreview")
            report["createdAction"] = {
                "id": action.get("id"),
                "tipo": action.get("tipo"),
                "modoOrigem": action.get("modoOrigem"),
                "conversaId": action.get("conversaId"),
                "projetoId": action.get("projetoId"),
            }
            report["checks"].append("pendencia_real_criada_sem_envio")
        else:
            raise AssertionError("Nenhuma pendencia real criada pela inbox nem por projetos.")

        browser.close()

    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
