#!/usr/bin/env python3
"""Mutacao focal dos indices de acoes_pendentes (18/10/2026).

Aplica um mutante por vez no DatabaseSchemaInitializer e roda o teste offline. Mutante so conta
como morto quando o surefire registra falha de TESTE, nunca de compilacao/infra.
Grava continuity/target/pit-reports/mutations.xml para o quality gate.
"""
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
ALVO = "src/main/java/com/freelasatende/config/DatabaseSchemaInitializer.java"
TESTE = "DatabaseSchemaInitializerTest"
MUTANTES = [
    ("indice do projeto sem data_criacao",
     "(plataforma, projeto_id, tipo, status, data_criacao)", "(plataforma, projeto_id, tipo, status)"),
    ("indice da conversa sem status",
     "(plataforma, conversa_id, tipo, status, data_criacao)", "(plataforma, conversa_id, tipo, data_criacao)"),
    ("indice de status removido",
     '        criarIndiceSeAusente("acoes_pendentes", "idx_acoes_status",',
     '        if (false) criarIndiceSeAusente("acoes_pendentes", "idx_acoes_status",'),
    ("tabela fixa em interacoes",
     '"WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",\n            String.class, tabela, nome);',
     '"WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",\n            String.class, "interacoes", nome);'),
    ("indice criado com lock",
     '"ALTER TABLE acoes_pendentes ADD INDEX idx_acoes_status (status), "\n                + "ALGORITHM=INPLACE, LOCK=NONE");',
     '"ALTER TABLE acoes_pendentes ADD INDEX idx_acoes_status (status)");'),
]


def rodar():
    relatorios = RAIZ / "target/surefire-reports"
    for xml in relatorios.glob("TEST-*.xml"):
        xml.unlink()
    r = subprocess.run(["mvn", "-q", "-B", "-o", "-Djacoco.skip=true", f"-Dtest={TESTE}",
                        "-Dsurefire.failIfNoSpecifiedTests=false", "test"],
                       cwd=RAIZ, capture_output=True, text=True, timeout=900)
    casos = []
    for xml in relatorios.glob("TEST-*.xml"):
        casos.extend(ET.parse(xml).getroot().findall("testcase"))
    falhou = any(c.find("failure") is not None for c in casos)
    erro = any(c.find("error") is not None for c in casos)
    if not casos or erro or "COMPILATION ERROR" in r.stdout + r.stderr:
        raise RuntimeError("compilacao/infra ou nenhum teste: " + (r.stdout + r.stderr)[-1500:])
    return r.returncode, falhou


def main():
    original = (RAIZ / ALVO).read_text()
    relatorio = ET.Element("mutations")
    try:
        if rodar()[0]:
            raise RuntimeError("baseline vermelho")
        for nome, antes, depois in MUTANTES:
            if original.count(antes) != 1:
                raise RuntimeError("ponto de mutacao ausente/ambiguo: " + nome)
            try:
                (RAIZ / ALVO).write_text(original.replace(antes, depois, 1))
                codigo, falhou = rodar()
                morto = codigo != 0 and falhou
                ET.SubElement(relatorio, "mutation", detected=str(morto).lower(),
                              status="KILLED" if morto else "SURVIVED").text = nome
                print(nome, "KILLED" if morto else "SURVIVED", flush=True)
            finally:
                (RAIZ / ALVO).write_text(original)
        if rodar()[0]:
            raise RuntimeError("restauracao nao passou")
        return 0 if all(m.get("detected") == "true" for m in relatorio) else 1
    finally:
        (RAIZ / ALVO).write_text(original)
        saida = RAIZ / "continuity/target/pit-reports/mutations.xml"
        saida.parent.mkdir(parents=True, exist_ok=True)
        ET.ElementTree(relatorio).write(saida, encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    raise SystemExit(main())
