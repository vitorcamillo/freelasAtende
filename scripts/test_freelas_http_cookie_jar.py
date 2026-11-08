#!/usr/bin/env python3
"""Testes da gravacao atomica do cookie jar de scripts/freelas_http.py.

Sem rede e sem curl_cffi: o modulo e importado por caminho de arquivo e o
import de curl_cffi e preguicoso (dentro de main()).
"""
import importlib.util
import json
import os
import stat
from pathlib import Path

import pytest

_SCRIPT = Path(__file__).resolve().parent / "freelas_http.py"


@pytest.fixture()
def frelas_http():
    spec = importlib.util.spec_from_file_location("freelas_http", _SCRIPT)
    modulo = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(modulo)
    return modulo


def test_grava_json_valido_com_cookies(frelas_http, tmp_path):
    destino = tmp_path / "cookies.json"
    cookies = {"freelancer_live_session": "abc", "XSRF-TOKEN": "xyz", "cf_clearance": "12"}

    frelas_http.salvar_cookie_jar(str(destino), cookies)

    assert json.loads(destino.read_text(encoding="utf-8")) == cookies


def test_nao_deixa_arquivo_temporario_sobrando(frelas_http, tmp_path):
    destino = tmp_path / "cookies.json"

    frelas_http.salvar_cookie_jar(str(destino), {"session": "1"})

    sobras = [p.name for p in tmp_path.iterdir() if p.name != "cookies.json"]
    assert sobras == []


def test_preserva_modo_0600_do_original(frelas_http, tmp_path):
    destino = tmp_path / "cookies.json"
    destino.write_text('{"antigo": "1"}', encoding="utf-8")
    os.chmod(destino, 0o600)

    frelas_http.salvar_cookie_jar(str(destino), {"novo": "2"})

    assert stat.S_IMODE(destino.stat().st_mode) == 0o600
    assert json.loads(destino.read_text(encoding="utf-8")) == {"novo": "2"}


def test_apaga_temporario_se_a_escrita_falha(frelas_http, tmp_path, monkeypatch):
    destino = tmp_path / "cookies.json"

    def replace_quebrado(src, dst):
        raise OSError("rename simulado falhou")

    monkeypatch.setattr(frelas_http.os, "replace", replace_quebrado)

    with pytest.raises(OSError):
        frelas_http.salvar_cookie_jar(str(destino), {"session": "1"})

    assert list(tmp_path.iterdir()) == []


def test_novo_arquivo_nasce_com_modo_0600(frelas_http, tmp_path):
    destino = tmp_path / "cookies.json"

    frelas_http.salvar_cookie_jar(str(destino), {"session": "1"})

    assert stat.S_IMODE(destino.stat().st_mode) == 0o600
