#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAX_LINES="${FILE_SIZE_GUARD_MAX_LINES:-500}"

if ! [[ "$MAX_LINES" =~ ^[0-9]+$ ]] || [[ "$MAX_LINES" -lt 1 ]]; then
  echo "FILE SIZE GUARD: FILE_SIZE_GUARD_MAX_LINES deve ser um inteiro positivo." >&2
  exit 2
fi

violations=0

while IFS= read -r -d '' file; do
  lines="$(wc -l < "$file")"
  if [[ "$lines" -gt "$MAX_LINES" ]]; then
    rel="${file#"$ROOT_DIR"/}"
    printf 'FILE SIZE GUARD: %s tem %s linhas; limite=%s\n' "$rel" "$lines" "$MAX_LINES" >&2
    violations=$((violations + 1))
  fi
done < <(
  for root in src scripts docs .planning; do
    if [[ -e "$ROOT_DIR/$root" ]]; then
      find "$ROOT_DIR/$root" -type f \
        \( \
          -name '*.java' -o \
          -name '*.xml' -o \
          -name '*.properties' -o \
          -name '*.yml' -o \
          -name '*.yaml' -o \
          -name '*.md' -o \
          -name '*.sh' -o \
          -name '*.py' -o \
          -name '*.html' -o \
          -name '*.css' -o \
          -name '*.js' -o \
          -name '*.json' \
        \) \
        -not -iname '*auth.json' \
        -not -iname '*cookies*' \
        -not -iname '*token*' \
        -not -iname '*secret*' \
        -not -iname '.env*' \
        -print0
    fi
  done

  find "$ROOT_DIR" -maxdepth 1 -type f \
    \( \
      -name 'pom.xml' -o \
      -name '*.xml' -o \
      -name '*.yml' -o \
      -name '*.yaml' -o \
      -name '*.md' \
    \) \
    -not -iname '*auth.json' \
    -not -iname '*cookies*' \
    -not -iname '*token*' \
    -not -iname '*secret*' \
    -not -iname '.env*' \
    -print0

  if [[ -e "$ROOT_DIR/data" ]]; then
    find "$ROOT_DIR/data" -type f -name '*.md' -print0
  fi
)

if [[ "$violations" -gt 0 ]]; then
  echo "FILE SIZE GUARD: ${violations} arquivo(s) acima do limite. Divida por responsabilidade antes de seguir." >&2
  exit 1
fi

echo "FILE SIZE GUARD: todos os arquivos monitorados estao dentro do limite de ${MAX_LINES} linhas."
