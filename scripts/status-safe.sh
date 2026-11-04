#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="$ROOT_DIR/target/runtime"
PID_FILE="$RUNTIME_DIR/freelas-safe.pid"
ENV_FILE="$RUNTIME_DIR/freelas-safe.env"
REQUESTED_PORT="${PORT:-}"
REQUESTED_SESSION_NAME="${SESSION_NAME:-}"

if [[ -f "$ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$ENV_FILE"
fi

PORT="${REQUESTED_PORT:-${PORT:-18080}}"
SESSION_NAME="${REQUESTED_SESSION_NAME:-${SESSION_NAME:-freelas-safe}}"

session_from_pid() {
  local pid="$1"
  tmux list-panes -a -F "#{session_name} #{pane_pid}" 2>/dev/null \
    | awk -v pid="$pid" '$2 == pid { print $1; exit }'
}

port_from_pid() {
  local pid="$1"
  local args
  args="$(ps -p "$pid" -o args= 2>/dev/null || true)"
  if [[ "$args" =~ --server\.port=([0-9]+) ]]; then
    echo "${BASH_REMATCH[1]}"
    return
  fi
  if [[ "$args" =~ SERVER_PORT=\'?([0-9]+) ]]; then
    echo "${BASH_REMATCH[1]}"
  fi
}

if [[ -z "$REQUESTED_PORT" && -f "$PID_FILE" ]]; then
  PID_FOR_PORT="$(cat "$PID_FILE" 2>/dev/null || true)"
  if [[ -n "${PID_FOR_PORT:-}" ]] && kill -0 "$PID_FOR_PORT" 2>/dev/null; then
    DETECTED_PORT="$(port_from_pid "$PID_FOR_PORT")"
    PORT="${DETECTED_PORT:-$PORT}"
  fi
fi

BASE_URL="http://127.0.0.1:$PORT"

if tmux has-session -t "=$SESSION_NAME" 2>/dev/null; then
  echo "processo=rodando tmux=$SESSION_NAME"
elif [[ -f "$PID_FILE" ]]; then
  PID="$(cat "$PID_FILE" 2>/dev/null || true)"
  if [[ -n "${PID:-}" ]] && kill -0 "$PID" 2>/dev/null; then
    TMUX_SESSION="$(session_from_pid "$PID")"
    if [[ -n "${TMUX_SESSION:-}" ]]; then
      echo "processo=rodando tmux=$TMUX_SESSION pid=$PID"
    else
      echo "processo=rodando pid=$PID"
    fi
  else
    echo "processo=parado pidfile_obsoleto"
  fi
else
  echo "processo=sem_pidfile"
fi

echo "porta=$PORT"

check() {
  local path="$1"
  local status
  local body="$RUNTIME_DIR/status-body.json"
  status="$(curl -sS -o "$body" -w "%{http_code}" "$BASE_URL$path" || true)"
  echo "$path http=$status"
  if [[ "$status" == "200" ]]; then
    if [[ "$path" == "/api/ml-lab/runs" ]]; then
      python3 - "$body" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
print(json.dumps({
    "runs": len(data),
    "ids": [item.get("id") for item in data[:5]],
    "best": data[0].get("bestAgentKey") if data else None,
    "status": data[0].get("status") if data else None,
}, ensure_ascii=False))
PY
    else
      python3 - "$body" <<'PY'
import json, sys
print(json.dumps(json.load(open(sys.argv[1], encoding="utf-8")), ensure_ascii=False))
PY
    fi
  fi
}

check "/api/dashboard/health"
check "/api/modos/status"
check "/api/ml-lab/runs"

echo "dashboard=$BASE_URL/dashboard"
