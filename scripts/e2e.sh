#!/usr/bin/env bash
# Full end-to-end test: boots a real Minecraft 1.21.8 server, launches the headless bot
# (auto-connecting in offline mode), and runs the WebSocket probe to assert it joined the
# world and can take a GPU-free screenshot.
#
# Requires: a JDK able to run the MC server (21+), Python 3, network access. The bot client
# is launched via `gradlew runClient`; first run downloads MC assets and can take a while.
#
# Env: MC_VERSION (1.21.8), WS_PORT (8731), MC_PORT (25565), BOT_NAME (ClefBot),
#      SERVER_DIR (e2e/server), SERVER_JAVA (java binary used to run the server; default `java`).
#
# Set MC_PORT + WS_PORT + SERVER_DIR to run this beside another instance (or beside somebody
# else's bot) without fighting over ports or a world directory. Nothing here kills by process
# name — only the PIDs it started — so a stray run can't take out an unrelated client.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
MC_VERSION="${MC_VERSION:-1.21.8}"
WS_PORT="${WS_PORT:-8731}"
MC_PORT="${MC_PORT:-25565}"
BOT_NAME="${BOT_NAME:-ClefBot}"
SERVER_JAVA="${SERVER_JAVA:-java}"
SERVER_DIR="${SERVER_DIR:-$ROOT/e2e/server}"
mkdir -p "$ROOT/e2e"

SRV_PID=""
BOT_PID=""
cleanup() {
  echo "[e2e] cleaning up..."
  [[ -n "$BOT_PID" ]] && kill "$BOT_PID" 2>/dev/null || true
  [[ -n "$SRV_PID" ]] && kill "$SRV_PID" 2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup EXIT

# 1) server jar
[[ -f "$SERVER_DIR/server.jar" ]] || MC_VERSION="$MC_VERSION" bash "$ROOT/scripts/fetch-server.sh" "$SERVER_DIR"

# 2) boot server on the requested port
if [[ -f "$SERVER_DIR/server.properties" ]]; then
  # Rewrite in place rather than appending, so repeated runs don't stack duplicate keys.
  grep -v '^server-port=' "$SERVER_DIR/server.properties" > "$SERVER_DIR/server.properties.tmp" || true
  echo "server-port=$MC_PORT" >> "$SERVER_DIR/server.properties.tmp"
  mv "$SERVER_DIR/server.properties.tmp" "$SERVER_DIR/server.properties"
fi

echo "[e2e] starting server ($SERVER_JAVA) on port $MC_PORT..."
( cd "$SERVER_DIR" && "$SERVER_JAVA" -Xmx2G -jar server.jar nogui ) > "$SERVER_DIR/server.log" 2>&1 &
SRV_PID=$!
echo "[e2e] server pid=$SRV_PID, waiting for 'Done'..."
for _ in $(seq 1 180); do
  grep -q 'Done (' "$SERVER_DIR/server.log" 2>/dev/null && break
  kill -0 "$SRV_PID" 2>/dev/null || { echo "[e2e] server died:"; tail -30 "$SERVER_DIR/server.log"; exit 1; }
  sleep 1
done
grep -q 'Done (' "$SERVER_DIR/server.log" 2>/dev/null || { echo "[e2e] server start timed out"; tail -30 "$SERVER_DIR/server.log"; exit 1; }
echo "[e2e] server is up."

# 3) bot config: offline, auto-connect, software screenshots
#
# The control plane gets a per-run token even though it only listens on localhost. A test bot with
# no token is an open, fully-actuating body that anything else on the machine can drive — which has
# happened: another agent's runtime found one of these on its default port and walked it across the
# test world. Localhost is not an authorization boundary on a shared machine.
CLEF_WS_TOKEN="${CLEF_WS_TOKEN:-$(head -c 18 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=')}"
export CLEF_WS_TOKEN
mkdir -p "$ROOT/run/config"
cat > "$ROOT/run/config/mezzoclef.json" <<EOF
{
  "auth": { "mode": "offline", "offlineUsername": "$BOT_NAME" },
  "connection": { "autoConnect": true, "serverHost": "127.0.0.1", "serverPort": $MC_PORT },
  "control": { "enabled": true, "host": "127.0.0.1", "port": $WS_PORT, "authToken": "$CLEF_WS_TOKEN" },
  "screenshot": { "backend": "software", "defaultWidth": 640, "defaultHeight": 360, "maxRayDistance": 96 },
  "headless": true,
  "headlessLoopSleepMs": 5
}
EOF

# 4) launch bot
echo "[e2e] launching bot (gradlew runClient)... logs -> e2e/bot.log"
./gradlew runClient --console=plain > "$ROOT/e2e/bot.log" 2>&1 &
BOT_PID=$!

# 5) probe
echo "[e2e] running probe..."
CLEF_WS_HOST=127.0.0.1 CLEF_WS_PORT="$WS_PORT" CLEF_BOT_NAME="$BOT_NAME" CLEF_OUT="$ROOT/e2e" \
  CLEF_WS_TOKEN="$CLEF_WS_TOKEN" python3 "$ROOT/scripts/ws_probe.py"
RC=$?

echo "[e2e] result: $([ $RC -eq 0 ] && echo PASS || echo FAIL) (rc=$RC)"
echo "[e2e] artifacts: e2e/e2e-shot.png, e2e/bot.log, e2e/server/server.log"
exit $RC
