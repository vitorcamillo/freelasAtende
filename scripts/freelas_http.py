#!/usr/bin/env python3
import argparse
import base64
import json
import sys

from curl_cffi import requests


def _chrome_major() -> str:
    """Major do Chrome/Chromium instalado (client hints tem que casar com o UA)."""
    import re
    import subprocess

    for binario in ("/usr/bin/google-chrome", "/usr/bin/chromium"):
        try:
            saida = subprocess.check_output([binario, "--version"], text=True, timeout=10)
            m = re.search(r"(\d+)\.", saida)
            if m:
                return m.group(1)
        except Exception:
            continue
    return "153"


MAJOR = _chrome_major()
CHROME_CLIENT_HINTS = {
    "sec-ch-ua": f'"Google Chrome";v="{MAJOR}", "Chromium";v="{MAJOR}", "Not?A_Brand";v="24"',
    "sec-ch-ua-mobile": "?0",
    "sec-ch-ua-platform": '"Linux"',
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--method", required=True)
    parser.add_argument("--url", required=True)
    parser.add_argument("--cookie-file", default="data/cookies.json")
    parser.add_argument(
        "--cookie-domain",
        default="www.99freelas.com.br",
        help="Dominio usado para registrar os cookies na sessao.",
    )
    parser.add_argument(
        "--cookie-jar-out",
        default="",
        help="Se definido, regrava o arquivo de cookies com os valores rotacionados pelo servidor.",
    )
    parser.add_argument("--referer", default="")
    parser.add_argument("--accept", default="text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
    parser.add_argument("--content-type", default="")
    parser.add_argument("--impersonate", default="chrome124")
    parser.add_argument("--user-agent", default="")
    parser.add_argument("--no-redirect", action="store_true")
    args = parser.parse_args()

    try:
        with open(args.cookie_file, "r", encoding="utf-8") as fh:
            cookies = json.load(fh)

        session = requests.Session(impersonate=args.impersonate)
        for name, value in cookies.items():
            session.cookies.set(name, value, domain=args.cookie_domain, path="/")

        headers = {
            "Accept": args.accept,
            "Accept-Language": "pt-BR,pt;q=0.9,en;q=0.8",
            "Sec-Fetch-Site": "same-origin",
        }
        headers.update(CHROME_CLIENT_HINTS)
        if args.user_agent:
            headers["User-Agent"] = args.user_agent
        if args.referer:
            headers["Referer"] = args.referer

        method = args.method.upper()
        data = sys.stdin.buffer.read()
        allow_redirects = not args.no_redirect
        if method == "GET":
            headers.update({
                "Upgrade-Insecure-Requests": "1",
                "Sec-Fetch-Dest": "document",
                "Sec-Fetch-Mode": "navigate",
                "Sec-Fetch-User": "?1",
            })
            response = session.get(
                args.url, headers=headers, timeout=30, allow_redirects=allow_redirects
            )
        elif method == "POST":
            headers.update({
                "X-Requested-With": "XMLHttpRequest",
                "Sec-Fetch-Dest": "empty",
                "Sec-Fetch-Mode": "cors",
            })
            if args.content_type:
                headers["Content-Type"] = args.content_type
            response = session.post(
                args.url, headers=headers, data=data, timeout=30, allow_redirects=allow_redirects
            )
        elif method == "POSTFORM":
            # Envio de formulario navegado (ex.: "Estou interessado"): o servidor
            # responde com redirect de volta para a pagina do projeto.
            headers.update({
                "Upgrade-Insecure-Requests": "1",
                "Sec-Fetch-Dest": "document",
                "Sec-Fetch-Mode": "navigate",
                "Sec-Fetch-User": "?1",
                "Content-Type": args.content_type or "application/x-www-form-urlencoded",
            })
            response = session.post(
                args.url, headers=headers, data=data, timeout=30, allow_redirects=allow_redirects
            )
        else:
            raise ValueError(f"Metodo nao suportado: {method}")

        if args.cookie_jar_out:
            # O Laravel do Freelancer.com.br ROTACIONA o cookie de sessao a cada
            # resposta. Sem regravar, a sessao morre no Max-Age original (30 min)
            # e o sistema volta a pedir login manual.
            atualizados = dict(cookies)
            for name in session.cookies.keys():
                valor = session.cookies.get(name)
                if valor:
                    atualizados[name] = valor
            if atualizados != cookies:
                with open(args.cookie_jar_out, "w", encoding="utf-8") as fh:
                    json.dump(atualizados, fh, ensure_ascii=False)

        body = response.content or b""
        print(json.dumps({
            "status": response.status_code,
            "url": str(response.url),
            "body_base64": base64.b64encode(body).decode("ascii"),
        }))
        return 0
    except Exception as exc:
        print(json.dumps({
            "status": 0,
            "error": f"{type(exc).__name__}: {exc}",
            "body_base64": "",
        }))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
