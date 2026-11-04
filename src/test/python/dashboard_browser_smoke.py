import os

from playwright.sync_api import expect, sync_playwright


BASE_URL = os.environ.get("DASHBOARD_BASE_URL", "http://127.0.0.1:18080")


def main():
    console_errors = []

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 1000})
        page.on(
            "console",
            lambda message: console_errors.append(message.text)
            if message.type == "error"
            else None,
        )

        page.goto(f"{BASE_URL}/dashboard", wait_until="domcontentloaded")
        expect(page.locator("#controlCenterApp")).to_be_visible()
        expect(page.locator("#dashboardTabs")).to_be_visible()
        expect(page.locator("#teachingFocusPanel")).to_be_attached()
        expect(page.locator("#actionDetail")).to_be_visible()
        expect(page.locator("#rewriteModal")).to_be_attached()
        expect(page.locator("#writeLockChip")).to_be_attached()
        expect(page.locator("#modeWritePolicyChip")).to_be_visible()
        expect(page.locator("#simulationChip")).to_be_attached()
        expect(page.locator("#opsSafetySummary")).to_be_attached()

        overview = page.request.get(f"{BASE_URL}/api/controle/overview")
        assert overview.ok, f"overview HTTP {overview.status}"
        overview_json = overview.json()
        assert "totalPendencias" in overview_json
        assert "aprendizado" in overview_json
        assert "qualidadeOperacional" in overview_json
        assert overview_json.get("escritaRealHabilitada") is False

        actions = page.request.get(f"{BASE_URL}/api/controle/acoes-pendentes")
        assert actions.ok, f"acoes-pendentes HTTP {actions.status}"
        assert isinstance(actions.json(), list)

        ops = page.request.get(f"{BASE_URL}/api/dashboard/ops")
        assert ops.ok, f"dashboard ops HTTP {ops.status}"
        ops_json = ops.json()
        assert ops_json.get("modoSeguro") is True
        assert ops_json.get("escrita", {}).get("travaAtiva") is True
        assert isinstance(ops_json.get("alertas"), list)

        modo = page.request.get(f"{BASE_URL}/api/modos")
        assert modo.ok, f"modos HTTP {modo.status}"
        modo_json = modo.json()
        assert "modo" in modo_json
        assert "pausado" in modo_json
        assert "autoAtivo" in modo_json

        for tab in ("auditoria", "logs", "inbox", "ml-lab", "controle", "operar"):
            if tab in {"auditoria", "logs", "ml-lab", "controle"}:
                page.locator(".advanced-nav").evaluate("node => node.open = true")
            page.locator(f'[data-tab="{tab}"]').click()
            expect(page.locator(f'[data-panel="{tab}"]')).to_be_visible()
            if tab == "ml-lab":
                expect(page.locator("#mlLabSummary")).to_contain_text("Run #", timeout=20_000)
                expect(page.locator("#mlLabRuns")).to_contain_text("Run #", timeout=20_000)
            if tab == "controle":
                expect(page.locator("#opsSafetySummary")).to_be_visible()
                expect(page.locator("#opsAlertList")).to_be_visible()

        page.locator("#refreshButton").click()
        expect(page.locator("#lastUpdate")).to_contain_text("Atualizado em")

        blocked_errors = [
            error
            for error in console_errors
            if "EventSource" not in error and "ERR_" not in error
        ]
        assert not blocked_errors, "\n".join(blocked_errors)
        browser.close()


if __name__ == "__main__":
    main()
