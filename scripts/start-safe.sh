#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="$ROOT_DIR/target/runtime"
PID_FILE="$RUNTIME_DIR/freelas-safe.pid"
ENV_FILE="$RUNTIME_DIR/freelas-safe.env"
LOG_FILE="$RUNTIME_DIR/freelas-safe.log"
PORT="${PORT:-18080}"
SESSION_NAME="${SESSION_NAME:-freelas-safe}"

mkdir -p "$RUNTIME_DIR"

if tmux has-session -t "=$SESSION_NAME" 2>/dev/null; then
  echo "FreelasAtende ja rodando em tmux: sessao=$SESSION_NAME porta=$PORT"
  echo "Dashboard: http://127.0.0.1:$PORT/dashboard"
  echo "Logs: tmux attach -t $SESSION_NAME"
  exit 0
fi

if ss -ltn "sport = :$PORT" | grep -q ":$PORT"; then
  echo "Porta $PORT ocupada. Defina outra: PORT=18081 scripts/start-safe.sh"
  exit 1
fi

rm -f "$PID_FILE"
: > "$LOG_FILE"

tmux new-session -d -s "$SESSION_NAME" \
  "cd '$ROOT_DIR' && REAL_WRITE_ENABLED=false REAL_WRITE_SIMULATE=false TELEGRAM_SIMULATE=true MODO_DEFAULT=ENSINAMENTO SCHEDULING_ENABLED=false IA_FALLBACK_WHEN_MISSING_KEY=false ML_LAB_REAL_IA_ENABLED=true SERVER_PORT='$PORT' mvn spring-boot:run -Dspring-boot.run.profiles=local-h2 -Dspring-boot.run.arguments='--server.port=$PORT --scheduling.enabled=false --spring.jpa.hibernate.ddl-auto=none --spring.jpa.open-in-view=false --real.write.enabled=false --real.write.simulate=false --telegram.simulate=true --sistema.modo-padrao=ENSINAMENTO --ia.fallback-when-missing-key=false --ml-lab.real-ia-enabled=true' >> '$LOG_FILE' 2>&1"

tmux display-message -p -t "$SESSION_NAME" "#{pane_pid}" > "$PID_FILE"
{
  printf 'SESSION_NAME=%q\n' "$SESSION_NAME"
  printf 'PORT=%q\n' "$PORT"
  printf 'DASHBOARD_URL=%q\n' "http://127.0.0.1:$PORT/dashboard"
} > "$ENV_FILE"
echo "FreelasAtende iniciando: sessao=$SESSION_NAME porta=$PORT"
echo "Log: $LOG_FILE"
echo "Dashboard: http://127.0.0.1:$PORT/dashboard"
echo "Attach: tmux attach -t $SESSION_NAME"
