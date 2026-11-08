#!/usr/bin/env sh
set -eu

# Perfil do Chrome persistente: sobrevive a redeploys, preserva cookies e
# reputacao com o Cloudflare (perfil efemero reiniciava o desafio do zero).
mkdir -p /app/data/chrome-profiles
rm -rf /app/profiles
ln -s /app/data/chrome-profiles /app/profiles

materialize_secret_file() {
  var_name="$1"
  target="$2"
  label="$3"

  value="$(printenv "$var_name" || true)"
  if [ -z "$value" ]; then
    if [ ! -f "$target" ]; then
      echo "[entrypoint] $label nao informado em $var_name e arquivo ausente: $target"
    fi
    return 0
  fi

  mkdir -p "$(dirname "$target")"
  tmp="${target}.tmp"
  printf '%s' "$value" | base64 -d > "$tmp"
  mv "$tmp" "$target"
  chmod 600 "$target"
  echo "[entrypoint] $label carregado em $target"
}

append_codex_account_home() {
  home="$1"
  if [ -z "${IA_CODEX_ACCOUNT_HOMES:-}" ]; then
    IA_CODEX_ACCOUNT_HOMES="$home"
  else
    IA_CODEX_ACCOUNT_HOMES="${IA_CODEX_ACCOUNT_HOMES}:$home"
  fi
}

materialize_codex_bundle() {
  var_name="$1"
  target_dir="$2"
  value="$(printenv "$var_name" || true)"
  if [ -z "$value" ]; then
    return 0
  fi

  mkdir -p "$target_dir"
  tmp="$(mktemp)"
  printf '%s' "$value" | base64 -d > "$tmp"
  tar -xzf "$tmp" -C "$target_dir"
  rm -f "$tmp"
  find "$target_dir/accounts" -name auth.json -type f -exec chmod 600 {} \; 2>/dev/null || true
  echo "[entrypoint] Codex auth bundle carregado em $target_dir/accounts"
}

CODEX_HOME_BASE="${CODEX_HOME:-/var/lib/codex}"
materialize_codex_bundle "CODEX_AUTH_JSON_B64_BUNDLE" "$CODEX_HOME_BASE"
materialize_secret_file "CODEX_AUTH_JSON_B64" "$CODEX_HOME_BASE/auth.json" "Codex auth.json"
append_codex_account_home "$CODEX_HOME_BASE"

i=1
while [ "$i" -le 5 ]; do
  var_name="CODEX_AUTH_JSON_B64_$i"
  value="$(printenv "$var_name" || true)"
  account_home="$CODEX_HOME_BASE/accounts/$i"
  if [ -n "$value" ]; then
    materialize_secret_file "$var_name" "$account_home/auth.json" "Codex auth.json perfil $i"
  fi
  if [ -f "$account_home/auth.json" ]; then
    append_codex_account_home "$account_home"
  fi
  i=$((i + 1))
done
export IA_CODEX_ACCOUNT_HOMES

materialize_secret_file "FREELAS_COOKIES_JSON_B64" "${FREELAS_COOKIES_PATH:-/app/data/cookies.json}" "99Freelas cookies.json"
materialize_secret_file "WORKANA_COOKIES_JSON_B64" "${WORKANA_COOKIES_PATH:-/app/data/workana-cookies.json}" "Workana cookies.json"
materialize_secret_file "FREELANCERBR_COOKIES_JSON_B64" "${FREELANCERBR_COOKIES_PATH:-/app/data/freelancerbr-cookies.json}" "Freelancer.com.br cookies.json"
materialize_secret_file "FREELANCER_COOKIES_JSON_B64" "${FREELANCER_COOKIES_PATH:-/app/data/freelancer-cookies.json}" "Freelancer.com cookies.json"
materialize_secret_file "UPWORK_COOKIES_JSON_B64" "${UPWORK_COOKIES_PATH:-/app/data/upwork-cookies.json}" "Upwork cookies.json"

# O /tmp do container persiste entre recriacoes. Um Xvfb de execucao anterior
# morre deixando lock stale e o Xvfb novo aborta com "Server is already active
# for display 99" - nenhum Chrome sobe e o discovery do .com.br fica morto
# (medido 22/09/2026: container inteiro sem display desde o boot). No boot
# nao ha Xvfb deste container vivo, entao qualquer lock e resto: remover.
rm -f /tmp/.X99-lock /tmp/.X11-unix/X99 2>/dev/null || true

# Forca saida IPv4 para o Freelancer: o servidor tem IPv6 com prefixo
# dinamico e o cf_clearance fica atrelado ao IP de saida. O clearance nasce
# em IPv4 (mesma rede, mesmo roteador) mas o Chrome e o transporte HTTP
# saiam pelo IPv6 -> IP diferente -> clearance invalido -> o Managed
# Challenge de /projetos nunca passava. Pinar o IPv4 no /etc/hosts alinha
# o container na mesma origem do navegador que gera o clearance.
for host_freelas in www.freelancer.com.br freelancer.com.br; do
  ip_freelas=$(getent ahostsv4 "$host_freelas" 2>/dev/null | awk '{print $1; exit}')
  if [ -n "$ip_freelas" ] && ! grep -q " $host_freelas" /etc/hosts 2>/dev/null; then
    echo "$ip_freelas $host_freelas" >> /etc/hosts 2>/dev/null || true
    echo "[entrypoint] $host_freelas pinado em IPv4 ($ip_freelas)"
  fi
done

# Alinha o User-Agent padrao do Freelancer.com.br com o Chrome realmente
# instalado na imagem (google-chrome-stable, sem pin de versao). A major do
# token Chrome/ do UA precisa acompanhar o binario. O cf_clearance real
# continua sendo exportado pelo navegador (cookies.json.ua); isto corrige
# apenas o fallback usado quando esse arquivo nao existe.
if [ -z "${FREELANCERBR_USER_AGENT:-}" ] && [ -n "${FREELANCERBR_BROWSER_USER_AGENT:-}" ]; then
  if [ -n "${CHROME_BIN:-}" ] && [ -x "$CHROME_BIN" ]; then
    chrome_version="$("$CHROME_BIN" --version 2>/dev/null || true)"
    # Casar "Chromium 153.0..." e "Google Chrome 153.0...": a major e o
    # primeiro numero da saida, independente do prefixo textual.
    chrome_major="$(printf '%s' "$chrome_version" | sed -n 's/^[^0-9]*\([0-9][0-9]*\)\..*/\1/p')"
    if [ -n "$chrome_major" ]; then
      FREELANCERBR_USER_AGENT="$(printf '%s' "$FREELANCERBR_BROWSER_USER_AGENT" | sed "s/Chrome\/[0-9][0-9]*/Chrome\/$chrome_major/")"
      export FREELANCERBR_USER_AGENT
      echo "[entrypoint] UA Freelancer.com.br alinhado ao Chrome $chrome_major"
    fi
  else
    echo "[entrypoint] aviso: CHROME_BIN ${CHROME_BIN:-vazio} nao executavel; UA padrao mantido"
  fi
fi

exec "$@"
