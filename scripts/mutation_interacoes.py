#!/usr/bin/env python3
"""Mutacao focal da consulta de erros tecnicos pendentes (25/09/2026).

Copia src/ e pom.xml para um diretorio temporario, aplica um mutante por vez e
roda InteracaoRepositoryErrosTecnicosTest offline. Mutante so conta como morto
quando o Maven termina com falha de TESTE (surefire), nao de compilacao.
Grava continuity/target/pit-reports/mutations.xml para o quality gate.
"""
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
REPO = "src/main/java/com/freelasatende/repository/InteracaoRepository.java"
TESTE = "InteracaoRepositoryErrosTecnicosTest"
MUTANTES = [
    ("limite de tentativas ignorado",
     "and erros.quantidade_erros < :maxTentativas", "and 1 = 1"),
    ("ultimo erro do projeto ignorado",
     "and i.data = erros.ultima_data", "and 1 = 1"),
    ("interacao conclusiva ignorada",
     "and t.tipo not in (:tiposNaoConclusivos)", "and 1 = 0"),
    ("janela de tempo ignorada",
     "and i.data >= :desde", "and (i.data >= :desde or 1 = 1)"),
    ("tipo enviado como enum (ordinal)",
     "            tipoErro.name(),", "            tipoErro,"),
]


def rodar(pasta: Path) -> tuple[bool, str]:
    r = subprocess.run(
        ["mvn", "-q", "-B", "-o", "-Djacoco.skip=true", f"-Dtest={TESTE}",
         "-Dsurefire.failIfNoSpecifiedTests=false", "test"],
        cwd=pasta, capture_output=True, text=True, timeout=900)
    saida = r.stdout + r.stderr
    return r.returncode == 0, saida


def main() -> int:
    relatorio = ET.Element("mutations")
    mortos = 0
    for descricao, original, mutante in MUTANTES:
        with tempfile.TemporaryDirectory(prefix="freelas-mut-") as tmp:
            copia = Path(tmp)
            shutil.copytree(RAIZ / "src", copia / "src")
            # O build roda scripts/check-file-size.sh (file-size-guard); sem ele o
            # Maven falha antes dos testes e nenhum mutante poderia morrer.
            shutil.copytree(RAIZ / "scripts", copia / "scripts",
                            ignore=shutil.ignore_patterns("__pycache__"))
            shutil.copy(RAIZ / "pom.xml", copia / "pom.xml")
            alvo = copia / REPO
            texto = alvo.read_text(encoding="utf-8")
            if texto.count(original) != 1:
                print(f"ERRO: trecho original nao unico para '{descricao}'")
                return 2
            if "String tipoErro" in texto and "enum" in descricao:
                texto = texto.replace("        String tipoErro,", "        Object tipoErro,", 1)
            alvo.write_text(texto.replace(original, mutante, 1), encoding="utf-8")
            passou, saida = rodar(copia)
            falha_de_teste = (not passou) and "Tests run:" in saida and "COMPILATION ERROR" not in saida
            morto = falha_de_teste
            mortos += morto
            ET.SubElement(relatorio, "mutation", detected=str(morto).lower(),
                          status="KILLED" if morto else "SURVIVED").text = descricao
            print(("MORTO  " if morto else "VIVO!  ") + descricao, flush=True)
    destino = RAIZ / "continuity/target/pit-reports"
    destino.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(relatorio).write(destino / "mutations.xml", encoding="utf-8",
                                    xml_declaration=True)
    print(f"{mortos}/{len(MUTANTES)} mutantes mortos")
    return 0 if mortos == len(MUTANTES) else 1


if __name__ == "__main__":
    raise SystemExit(main())
