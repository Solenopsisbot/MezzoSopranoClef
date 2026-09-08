#!/usr/bin/env bash
# Full end-to-end test: boots a real Minecraft server (any version in the support matrix,
# default = the client's own version), launches the headless bot (auto-connecting in offline
# mode, speaking the server's protocol through ViaFabricPlus), and runs the WebSocket probe to
# assert it joined the world and can take a GPU-free screenshot.
#
# Replay recording is armed in the generated config, so every run of this script also proves the
# .mcpr recorder end to end — and, when MC_VERSION names an old release, that the tap really does
# sit downstream of ViaFabricPlus's translation. autoSave is ON so the probe can also exercise the
# asynchronous path: ending a recording hands the seal and its events to the clef-replay worker
# rather than doing them on whichever thread happened to stop it.
#
# Requires: a JDK able to run the MC server for that version (see scripts/pick_server_java.py),
# Python 3, network access. The bot client is launched via `gradlew :runClient`; first run
# downloads MC assets and can take a while. For the whole version matrix in one client boot,
# use scripts/verify_versions.sh instead.
#
# Env: MC_VERSION (server version; default = minecraft_version in gradle.properties),
#      SERVER_VERSION (protocol the bot selects: "auto", "native", or a release; default = MC_VERSION),
#      WS_PORT (8731), BOT_NAME (ClefBot),
#      SERVER_JAVA (java binary used to run the server; default = auto-picked for MC_VERSION),
#      CLEF_RUN_TASK (gradle task that boots the bot; default `:runClient` = the newest-version
#        client. Use e.g. `:versions:fabric-1.21.11:runClient` to test a native older build),
#      CLEF_RUN_DIR (that task's run directory; default `run`),
#      MC_PORT (25565), SERVER_DIR (default e2e/servers/$MC_VERSION).
#
# Set MC_PORT + WS_PORT + SERVER_DIR to run this beside another instance (or beside somebody
# else's bot) without fighting over ports or a world directory. Nothing here kills by process
# name — only the PIDs it started — so a stray run can't take out an unrelated client.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
CLIENT_VERSION="$(sed -n 's/^minecraft_version=//p' "$ROOT/gradle.properties")"
MC_VERSION="${MC_VERSION:-$CLIENT_VERSION}"
SERVER_VERSION="${SERVER_VERSION:-$MC_VERSION}"
WS_PORT="${WS_PORT:-8731}"
MC_PORT="${MC_PORT:-25565}"
BOT_NAME="${BOT_NAME:-ClefBot}"
SERVER_JAVA="${SERVER_JAVA:-$(python3 "$ROOT/scripts/pick_server_java.py" "$MC_VERSION")}"
# NOTE the leading colon: every version module also defines runClient, so the unqualified name
# launches all 24 clients at once. The first one to boot claims the control port with its own
# config, and the probe then fails the handshake against a bot from a different module — which
# reads as "bad token", an auth bug that is not there. verify_versions.sh and verify_native.sh
# already qualify the task; this one did not.
CLEF_RUN_TASK="${CLEF_RUN_TASK:-:runClient}"
CLEF_RUN_DIR="${CLEF_RUN_DIR:-run}"
SERVER_DIR="${SERVER_DIR:-$ROOT/e2e/servers/$MC_VERSION}"
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
[[ -f "$SERVER_DIR/server.jar" ]] || MC_VERSION="$MC_VERSION" SERVER_PORT="$MC_PORT" bash "$ROOT/scripts/fetch-server.sh" "$SERVER_DIR"

# 2) boot server on the requested port
if [[ -f "$SERVER_DIR/server.properties" ]]; then
  grep -v '^server-port=' "$SERVER_DIR/server.properties" > "$SERVER_DIR/server.properties.tmp" || true
  echo "server-port=$MC_PORT" >> "$SERVER_DIR/server.properties.tmp"
  mv "$SERVER_DIR/server.properties.tmp" "$SERVER_DIR/server.properties"
fi
echo "[e2e] starting Minecraft $MC_VERSION server ($SERVER_JAVA) on port $MC_PORT; bot will speak protocol '$SERVER_VERSION'..."
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
# The control plane gets a per-run token even though it only listens on localhost. A test bot with
# no token is an open, fully-actuating body that anything else on the machine can drive — which has
# happened: another agent's runtime found one of these on its default port and walked it across the
# test world. Localhost is not an authorization boundary on a shared machine.
CLEF_WS_TOKEN="${CLEF_WS_TOKEN:-$(head -c 18 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=')}"
export CLEF_WS_TOKEN
mkdir -p "$ROOT/$CLEF_RUN_DIR/config"
cat > "$ROOT/$CLEF_RUN_DIR/config/mezzoclef.json" <<EOF
{
  "auth": { "mode": "offline", "offlineUsername": "$BOT_NAME" },
  "connection": { "autoConnect": true, "serverHost": "127.0.0.1", "serverPort": $MC_PORT, "serverVersion": "$SERVER_VERSION" },
  "control": { "enabled": true, "host": "127.0.0.1", "port": $WS_PORT, "authToken": "$CLEF_WS_TOKEN" },
  "screenshot": { "backend": "software", "defaultWidth": 640, "defaultHeight": 360, "maxRayDistance": 96 },
  "replay": { "enabled": true, "dir": "replay-e2e", "autoSave": true, "maxSizeMb": 64 },
  "headless": true,
  "headlessLoopSleepMs": 5
}
EOF

# 4) launch bot
echo "[e2e] launching bot (gradlew $CLEF_RUN_TASK)... logs -> e2e/bot.log"
./gradlew $CLEF_RUN_TASK --console=plain > "$ROOT/e2e/bot.log" 2>&1 &
BOT_PID=$!

# 5) probe
echo "[e2e] running probe..."
# CLEF_EXPECT_REPLAY: this script arms recording in the config above, so the probe must require
# the replay checks rather than skipping them. A standalone `python3 scripts/ws_probe.py` against a
# default client skips them instead, because there recording is legitimately off.
CLEF_WS_HOST=127.0.0.1 CLEF_WS_PORT="$WS_PORT" CLEF_BOT_NAME="$BOT_NAME" CLEF_OUT="$ROOT/e2e" \
  CLEF_EXPECT_REPLAY=1 python3 "$ROOT/scripts/ws_probe.py"
RC=$?

echo "[e2e] result: $([ $RC -eq 0 ] && echo PASS || echo FAIL) (rc=$RC)"
echo "[e2e] artifacts: e2e/e2e-shot.png, e2e/bot.log, $SERVER_DIR/server.log"
exit $RC
