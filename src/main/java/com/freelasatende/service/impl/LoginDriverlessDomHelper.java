package com.freelasatende.service.impl;

import io.github.selenium.javaDriverless.JavaDriverless;
import io.github.selenium.javaDriverless.types.By;
import io.github.selenium.javaDriverless.types.WebElement;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
final class LoginDriverlessDomHelper {

    private LoginDriverlessDomHelper() {
    }

    /**
     * Le a URL atual sem deixar um timeout de CDP derrubar o fluxo de login.
     *
     * <p>{@code getCurrentUrl()} faz uma ida ao Chrome e, sob carga no container
     * de 1GB, estoura com "Erro ao obter URL". Como a URL aqui so alimenta
     * heuristicas (ja autenticado? tela de OTP?), uma leitura falha deve virar
     * string vazia e nao excecao: quem chama trata a ausencia como "ainda nao
     * concluiu" e segue para a proxima etapa.</p>
     */
    static String urlAtualSegura(JavaDriverless driver) {
        try {
            String url = driver.getCurrentUrl();
            return url == null ? "" : url;
        } catch (Exception e) {
            log.warn("Nao foi possivel ler a URL atual do Chrome: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Extrai o texto de erro visivel na pagina de login para diferenciar as
     * causas de falha (senha rejeitada, captcha recusado, conta bloqueada...).
     *
     * <p>Roda JS que junta o innerText dos elementos visiveis com papel/classe
     * de alerta e, se nada aparecer, faz fallback nas linhas do body cujo texto
     * contenha palavras-chave de erro. E-mails sao mascarados antes do retorno:
     * o texto vai para log em WARN e pode carregar dado pessoal da pagina.
     * Qualquer falha vira string vazia: quem chama apenas registra a ausencia
     * de mensagem e segue.</p>
     */
    static String extrairMensagemErroLogin(JavaDriverless driver) {
        if (driver == null) {
            return "";
        }
        try {
            Object resultado = driver.executeScript(
                "(() => {" +
                    "const sel = \"[role='alert'], .alert, [class*='alert'], [class*='error'], [class*='erro'], [class*='flash'], .toast, .invalid-feedback, .help-block\";" +
                    "const vistos = new Set();" +
                    "const partes = [];" +
                    "for (const el of document.querySelectorAll(sel)) {" +
                    "  if (el.offsetParent === null) continue;" +
                    "  const t = (el.innerText || '').trim();" +
                    "  if (t && !vistos.has(t)) { vistos.add(t); partes.push(t); }" +
                    "}" +
                    "if (!partes.length) {" +
                    "  const palavras = ['incorret', 'invalid', 'invalido', 'invalida', 'erro', 'captcha', 'bloquead', 'negad', 'falha'];" +
                    "  const linhas = (((document.body || {}).innerText) || '').split('\\n');" +
                    "  for (const linha of linhas) {" +
                    "    const l = linha.trim();" +
                    "    if (!l) continue;" +
                    "    const baixo = l.toLowerCase();" +
                    "    if (palavras.some((p) => baixo.includes(p)) && !vistos.has(l)) { vistos.add(l); partes.push(l); }" +
                    "  }" +
                    "}" +
                    "return partes.join(' | ').slice(0, 400);" +
                "})()"
            );
            return resultado != null ? mascararEmails(resultado.toString().trim()) : "";
        } catch (Exception e) {
            log.debug("Nao foi possivel extrair mensagem de erro da pagina de login: {}", e.getMessage());
            return "";
        }
    }

    /** Dado pessoal nao vai para log: o endereco completo vira marcador. */
    private static String mascararEmails(String texto) {
        return texto.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "***@***");
    }

    static boolean clicarTurnstileAutomaticamente(JavaDriverless driver, String iframeSelector, String hostSelector) {
        try {
            scrollTurnstileParaCentro(driver, iframeSelector, hostSelector);
            Thread.sleep(350);

            TurnstileFrame alvo = encontrarAlvoTurnstileVisivel(driver, iframeSelector, hostSelector);
            if (alvo == null || alvo.width <= 0 || alvo.height <= 0) {
                log.debug("   Turnstile ainda não visível para clique automático.");
                return false;
            }

            int scrollX = alvo.viewportCoordinates ? getScrollX(driver) : 0;
            int scrollY = alvo.viewportCoordinates ? getScrollY(driver) : 0;
            int offsetX = "host".equals(alvo.kind)
                ? (int) Math.round(Math.min(90.0, Math.max(65.0, alvo.width * 0.19)))
                : (int) Math.round(Math.min(34.0, Math.max(18.0, alvo.width * 0.10)));
            int x = (int) Math.round(scrollX + alvo.left + offsetX);
            int y = (int) Math.round(scrollY + alvo.top + (alvo.height / 2.0));

            marcarPontoDeClique(driver, x, y);
            driver.getPointer().click(x, y, true, 0.8, 2.0, 18.0).get();
            log.info(
                "   ✅ Click automático no Turnstile via Pointer CDP em ({}, {}) [kind={}, size={}x{}]",
                x,
                y,
                alvo.kind,
                Math.round(alvo.width),
                Math.round(alvo.height)
            );
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.debug("   Click automático no Turnstile falhou: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Clique de sistema operacional (xdotool) no checkbox do Turnstile.
     * O Turnstile detecta eventos sinteticos do CDP (Input.dispatchMouseEvent
     * nao e confiavel para ele) e o desafio nunca completa; o clique via X11
     * chega como evento real do servidor X. A geometria vem do viewport
     * (getBoundingClientRect) convertida para coordenadas de tela com
     * screenX/screenY/outerHeight/innerHeight da janela do Chrome.
     */
    static boolean clicarTurnstilePorXdotool(JavaDriverless driver, String iframeSelector, String hostSelector) {
        try {
            TurnstileFrame alvo = encontrarAlvoTurnstileVisivel(driver, iframeSelector, hostSelector);
            if (alvo == null || alvo.width <= 0 || alvo.height <= 0) {
                return false;
            }
            int ajusteScrollX = alvo.viewportCoordinates ? 0 : getScrollX(driver);
            int ajusteScrollY = alvo.viewportCoordinates ? 0 : getScrollY(driver);
            int offsetX = "host".equals(alvo.kind)
                ? (int) Math.round(Math.min(90.0, Math.max(65.0, alvo.width * 0.19)))
                : (int) Math.round(Math.min(34.0, Math.max(18.0, alvo.width * 0.10)));
            int vx = (int) Math.round(alvo.left + offsetX) - ajusteScrollX;
            int vy = (int) Math.round(alvo.top + (alvo.height / 2.0)) - ajusteScrollY;
            String janela = safeGetString(driver,
                "window.screenX + ',' + window.screenY + ',' + window.outerHeight + ',' + window.innerHeight");
            String[] partes = janela == null ? new String[0] : janela.split(",");
            if (partes.length < 4) {
                return false;
            }
            int telaX = Integer.parseInt(partes[0].trim()) + vx;
            int telaY = Integer.parseInt(partes[1].trim()) + (Integer.parseInt(partes[2].trim())
                - Integer.parseInt(partes[3].trim())) + vy;
            new ProcessBuilder("xdotool", "mousemove", String.valueOf(telaX - 24), String.valueOf(telaY - 14))
                .inheritIO().start().waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            Thread.sleep(180);
            new ProcessBuilder("xdotool", "mousemove", String.valueOf(telaX), String.valueOf(telaY))
                .inheritIO().start().waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            Thread.sleep(120);
            new ProcessBuilder("xdotool", "click", "1").inheritIO().start().waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            log.info("   Click real (xdotool) no Turnstile em ({}, {}) [coordenadas de tela]", telaX, telaY);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.debug("Click xdotool no Turnstile falhou: {}", e.getMessage());
            return false;
        }
    }

    static void scrollTurnstileParaCentro(JavaDriverless driver, String iframeSelector, String hostSelector) {
        try {
            driver.executeScript(
                "(() => {" +
                    "const iframeSel = " + jsString(iframeSelector) + ";" +
                    "const hostSel = " + jsString(hostSelector) + ";" +
                    "const candidates = [];" +
                    "for (const el of Array.from(document.querySelectorAll(hostSel))) { candidates.push(el); }" +
                    "for (const el of Array.from(document.querySelectorAll(iframeSel))) { candidates.push(el); }" +
                    "if (!candidates.length) return;" +
                    "const pick = candidates.map((el) => {" +
                        "const r = el.getBoundingClientRect();" +
                        "const cs = getComputedStyle(el);" +
                        "const visible = r.width > 10 && r.height > 10 && cs.display !== 'none' && cs.visibility !== 'hidden' && parseFloat(cs.opacity || '1') > 0.05;" +
                        "return {el: el, r: r, visible: visible, area: r.width * r.height};" +
                    "}).filter(x => x.visible).sort((a,b) => b.area - a.area)[0];" +
                    "if (pick && pick.el) pick.el.scrollIntoView({block: 'center', inline: 'center'});" +
                "})()"
            );
        } catch (Exception e) {
            log.debug("Erro ao scrollIntoView do Turnstile: {}", e.getMessage());
        }
    }

    static String lerRespostaTurnstile(JavaDriverless driver) {
        try {
            Object token = executeScriptQuiet(driver,
                "(() => {" +
                    "const sel = \"input[name='cf-turnstile-response'], input[name='turnstile-response'], input[name='cf_challenge_response'], textarea[name='g-recaptcha-response']\";" +
                    "const el = document.querySelector(sel);" +
                    "if (!el) return '';" +
                    "try { return (el.value || ''); } catch (e) { return ''; }" +
                "})()"
            );
            return token != null ? token.toString() : null;
        } catch (Exception e) {
            log.debug("Erro ao ler token Turnstile: {}", e.getMessage());
            return null;
        }
    }

    static String safeGetString(JavaDriverless driver, String js) {
        try {
            Object val = driver.executeScript(js);
            return val != null ? val.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static WebElement findFirstUsableElement(JavaDriverless driver, String[] selectors) {
        for (String sel : selectors) {
            try {
                WebElement el = driver.findElement(By.css(sel));
                if (el == null) {
                    continue;
                }
                double[] size = el.getSize().get();
                if (size != null && size.length >= 2 && size[0] > 5 && size[1] > 5) {
                    return el;
                }
            } catch (Exception ignore) {
                // segue tentando outros seletores
            }
        }
        return null;
    }

    static boolean elementoVisivel(JavaDriverless driver, String cssSelector) {
        try {
            Object ok = driver.executeScript(
                "(() => {" +
                    "const els = document.querySelectorAll(" + jsString(cssSelector) + ");" +
                    "for (let i = 0; i < els.length; i++) {" +
                    "  const el = els[i];" +
                    "  const r = el.getBoundingClientRect();" +
                    "  const cs = getComputedStyle(el);" +
                    "  if (cs.display !== 'none' && cs.visibility !== 'hidden' && parseFloat(cs.opacity || '1') >= 0.05 && r.width > 5 && r.height > 5) {" +
                    "    return true;" +
                    "  }" +
                    "}" +
                    "return false;" +
                "})()"
            );
            return ok instanceof Boolean b && b;
        } catch (Exception e) {
            return false;
        }
    }

    static String jsString(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static TurnstileFrame encontrarAlvoTurnstileVisivel(JavaDriverless driver, String iframeSelector, String hostSelector) {
        try {
            Object result = driver.executeScript(
                "(() => {" +
                    "const iframeSel = " + jsString(iframeSelector) + ";" +
                    "const hostSel = " + jsString(hostSelector) + ";" +
                    "const candidates = [];" +
                    "for (const el of Array.from(document.querySelectorAll(hostSel))) { candidates.push({kind:'host', el: el}); }" +
                    "for (const el of Array.from(document.querySelectorAll(iframeSel))) { candidates.push({kind:'iframe', el: el}); }" +
                    "if (!candidates.length) return null;" +
                    "const items = candidates.map((c) => {" +
                        "const el = c.el;" +
                        "const r = el.getBoundingClientRect();" +
                        "const cs = getComputedStyle(el);" +
                        "const visible = r.width > 10 && r.height > 10 && cs.display !== 'none' && cs.visibility !== 'hidden' && parseFloat(cs.opacity || '1') > 0.05;" +
                        "return {kind: c.kind, src: (el.src || ''), left: r.left, top: r.top, width: r.width, height: r.height, visible: visible, area: r.width * r.height};" +
                    "});" +
                    "const vis = items.filter(i => i.visible);" +
                    "const best = (vis.length ? vis : items).sort((a,b) => {" +
                        "if (a.kind === 'iframe' && b.kind !== 'iframe') return -1;" +
                        "if (b.kind === 'iframe' && a.kind !== 'iframe') return 1;" +
                        "return b.area - a.area;" +
                    "})[0];" +
                    "if (!best) return null;" +
                    "best.dpr = window.devicePixelRatio || 1;" +
                    "return best;" +
                "})()"
            );

            if (result == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (result instanceof Map) ? (Map<String, Object>) result : null;
            if (map == null || map.isEmpty()) {
                return null;
            }
            return TurnstileFrame.fromMap(map);
        } catch (Exception e) {
            log.debug("Erro ao encontrar iframe Turnstile via JS: {}", e.getMessage());
            return encontrarAlvoTurnstilePorElemento(driver);
        }
    }

    private static TurnstileFrame encontrarAlvoTurnstilePorElemento(JavaDriverless driver) {
        WebElement iframe = findFirstUsableElement(driver, new String[]{
            "iframe[src*='challenges.cloudflare.com']",
            "iframe[src*='turnstile']"
        });
        if (iframe != null) {
            return criarTurnstileFrameDeElemento(iframe, "iframe");
        }

        WebElement host = findFirstUsableElement(driver, new String[]{
            ".cf-turnstile",
            "div[data-sitekey]"
        });
        if (host != null) {
            return criarTurnstileFrameDeElemento(host, "host");
        }

        return null;
    }

    private static TurnstileFrame criarTurnstileFrameDeElemento(WebElement el, String kind) {
        try {
            double[] loc = el.getLocation().get();
            double[] size = el.getSize().get();
            if (loc == null || size == null || loc.length < 2 || size.length < 2) {
                return null;
            }
            return new TurnstileFrame(kind, "", loc[0], loc[1], size[0], size[1], 1, false);
        } catch (Exception e) {
            log.debug("Erro ao mapear elemento Turnstile via CDP: {}", e.getMessage());
            return null;
        }
    }

    private static void marcarPontoDeClique(JavaDriverless driver, int x, int y) {
        try {
            driver.executeScript(
                "(() => {" +
                    "const id = 'freelas-click-marker';" +
                    "document.getElementById(id)?.remove();" +
                    "const marker = document.createElement('div');" +
                    "marker.id = id;" +
                    "marker.style.position = 'absolute';" +
                    "marker.style.left = '" + x + "px';" +
                    "marker.style.top = '" + y + "px';" +
                    "marker.style.width = '34px';" +
                    "marker.style.height = '34px';" +
                    "marker.style.marginLeft = '-17px';" +
                    "marker.style.marginTop = '-17px';" +
                    "marker.style.border = '4px solid #ff1f1f';" +
                    "marker.style.background = 'rgba(255, 31, 31, 0.25)';" +
                    "marker.style.borderRadius = '50%';" +
                    "marker.style.zIndex = '2147483647';" +
                    "marker.style.pointerEvents = 'none';" +
                    "marker.style.boxShadow = '0 0 0 6px rgba(255, 31, 31, 0.18)';" +
                    "document.body.appendChild(marker);" +
                    "setTimeout(() => marker.remove(), 4000);" +
                "})()"
            );
        } catch (Exception e) {
            log.debug("Nao foi possivel marcar ponto de clique: {}", e.getMessage());
        }
    }

    private static int getScrollX(JavaDriverless driver) {
        try {
            Object val = driver.executeScript("return Math.round(window.scrollX || window.pageXOffset || 0);");
            if (val instanceof Number n) return n.intValue();
            return Integer.parseInt(val.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private static int getScrollY(JavaDriverless driver) {
        try {
            Object val = driver.executeScript("return Math.round(window.scrollY || window.pageYOffset || 0);");
            if (val instanceof Number n) return n.intValue();
            return Integer.parseInt(val.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private static Object executeScriptQuiet(JavaDriverless driver, String script) {
        try {
            return driver.executeScript(script);
        } catch (Exception e) {
            return null;
        }
    }

    private static final class TurnstileFrame {
        final String kind;
        final String src;
        final double left;
        final double top;
        final double width;
        final double height;
        final double dpr;
        final boolean viewportCoordinates;

        private TurnstileFrame(String kind, String src, double left, double top, double width, double height, double dpr, boolean viewportCoordinates) {
            this.kind = kind;
            this.src = src;
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.dpr = dpr;
            this.viewportCoordinates = viewportCoordinates;
        }

        static TurnstileFrame fromMap(Map<String, Object> map) {
            String kind = map.get("kind") != null ? map.get("kind").toString() : "unknown";
            String src = map.get("src") != null ? map.get("src").toString() : "";
            double left = asDouble(map.get("left"));
            double top = asDouble(map.get("top"));
            double width = asDouble(map.get("width"));
            double height = asDouble(map.get("height"));
            double dpr = asDouble(map.get("dpr"));
            if (dpr <= 0) dpr = 1;
            return new TurnstileFrame(kind, src, left, top, width, height, dpr, true);
        }

        private static double asDouble(Object value) {
            if (value == null) return 0;
            if (value instanceof Number n) return n.doubleValue();
            try {
                return Double.parseDouble(value.toString());
            } catch (Exception ignore) {
                return 0;
            }
        }
    }
}
