#!/usr/bin/env bash
# Boots the headless bot ONCE (offline mode, no auto-connect), then runs scripts/verify_versions.py
# to walk it through a real vanilla server of every requested Minecraft version. This is the
# proof behind the support matrix in minecraft-versions.json: one newest-version client joining
# 1.12.2 .. current servers via ViaFabricPlus.
#
# Usage: scripts/verify_versions.sh [--auto] [--all] [version ...]      (see verify_versions.py)
# Env:   WS_PORT (8731), BOT_NAME (ClefBot), SERVER_PORT (25565), CLEF_WORLD_TIMEOUT (120)
# Needs a JDK per server era (8 for <=1.16, 17 for 1.18-1.20.4, 21 for 1.20.5+, 25 for 26.x);
# scripts/pick_server_java.py finds them (Gradle's ~/.gradle/jdks toolchains count).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
WS_PORT="${WS_PORT:-8731}"
BOT_NAME="${BOT_NAME:-ClefBot}"
mkdir -p "$ROOT/e2e"

BOT_PID=""
cleanup() {
  echo "[versions] cleaning up..."
  [[ -n "$BOT_PID" ]] && kill "$BOT_PID" 2>/dev/null || true
  # gradlew runClient forks the game JVM; make sure it goes too.
  pkill -f "mezzoclef.headless=true" 2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup EXIT

mkdir -p "$ROOT/run/config"
cat > "$ROOT/run/config/mezzoclef.json" <<EOF2
{
  "auth": { "mode": "offline", "offlineUsername": "$BOT_NAME" },
  "connection": { "autoConnect": false, "serverHost": "127.0.0.1", "serverPort": ${SERVER_PORT:-25565}, "serverVersion": "auto" },
  "control": { "enabled": true, "host": "127.0.0.1", "port": $WS_PORT, "authToken": "" },
  "screenshot": { "backend": "software", "defaultWidth": 640, "defaultHeight": 360, "maxRayDistance": 96 },
  "headless": true,
  "headlessLoopSleepMs": 5
}
EOF2

echo "[versions] launching bot (gradlew runClient)... logs -> e2e/bot-versions.log"
# Leading colon: every version module also defines runClient, so the unqualified name
# would launch all of them instead of just the root project's client.
./gradlew :runClient --console=plain > "$ROOT/e2e/bot-versions.log" 2>&1 &
BOT_PID=$!

CLEF_WS_HOST=127.0.0.1 CLEF_WS_PORT="$WS_PORT" CLEF_BOT_NAME="$BOT_NAME" \
  python3 "$ROOT/scripts/verify_versions.py" "$@"
RC=$?
echo "[versions] result: $([ $RC -eq 0 ] && echo PASS || echo FAIL) (rc=$RC) — report: e2e/versions-report.json"
exit $RC
