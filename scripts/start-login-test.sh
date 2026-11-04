#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
mkdir -p target/runtime
: > target/runtime/login-test.log

tmux kill-session -t freelas-login 2>/dev/null || true

tmux new-session -d -s freelas-login \
  "cd '$ROOT_DIR' && JAVA_DRIVERLESS_UNLOCK_STALE_PROFILE_LOCKS=true REAL_WRITE_ENABLED=false REAL_WRITE_SIMULATE=true TELEGRAM_SIMULATE=true SCHEDULING_ENABLED=false SERVER_PORT=18080 mvn spring-boot:run -Dspring-boot.run.profiles=local-h2 -Dspring-boot.run.arguments='--server.port=18080 --scheduling.enabled=false --real.write.enabled=false --real.write.simulate=true --telegram.simulate=true' >> target/runtime/login-test.log 2>&1"

echo "FreelasAtende (login-test) iniciando na porta 18080"
echo "Log: $ROOT_DIR/target/runtime/login-test.log"
