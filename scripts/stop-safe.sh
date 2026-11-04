#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="$ROOT_DIR/target/runtime"
PID_FILE="$RUNTIME_DIR/freelas-safe.pid"
ENV_FILE="$RUNTIME_DIR/freelas-safe.env"
REQUESTED_SESSION_NAME="${SESSION_NAME:-}"

if [[ -f "$ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$ENV_FILE"
fi

SESSION_NAME="${REQUESTED_SESSION_NAME:-${SESSION_NAME:-freelas-safe}}"

session_from_pid() {
  local pid="$1"
  tmux list-panes -a -F "#{session_name} #{pane_pid}" 2>/dev/null \
    | awk -v pid="$pid" '$2 == pid { print $1; exit }'
}

stop_tmux_session() {
  local session="$1"
  tmux send-keys -t "=$session" C-c
  for _ in {1..20}; do
    if ! tmux has-session -t "=$session" 2>/dev/null; then
      rm -f "$PID_FILE"
      rm -f "$ENV_FILE"
      echo "FreelasAtende parado."
      exit 0
    fi
    sleep 1
  done
  tmux kill-session -t "=$session"
  rm -f "$PID_FILE"
  rm -f "$ENV_FILE"
  echo "FreelasAtende parado com kill-session."
  exit 0
}

if tmux has-session -t "=$SESSION_NAME" 2>/dev/null; then
  stop_tmux_session "$SESSION_NAME"
fi

if [[ ! -f "$PID_FILE" ]]; then
  echo "Sem pidfile. Nada para parar."
  exit 0
fi

PID="$(cat "$PID_FILE" 2>/dev/null || true)"
if [[ -z "${PID:-}" ]] || ! kill -0 "$PID" 2>/dev/null; then
  rm -f "$PID_FILE"
  rm -f "$ENV_FILE"
  echo "Processo ja parado."
  exit 0
fi

TMUX_SESSION="$(session_from_pid "$PID")"
if [[ -n "${TMUX_SESSION:-}" ]]; then
  stop_tmux_session "$TMUX_SESSION"
fi

kill "$PID"
for _ in {1..30}; do
  if ! kill -0 "$PID" 2>/dev/null; then
    rm -f "$PID_FILE"
    rm -f "$ENV_FILE"
    echo "FreelasAtende parado."
    exit 0
  fi
  sleep 1
done

echo "Processo ainda vivo apos 30s: pid=$PID"
echo "Use kill manual se precisar."
exit 1
