#!/usr/bin/env bash
# Runtime-verify the NATIVE per-version client builds.
#
# For each target this boots a real vanilla server of that exact Minecraft version AND that
# version's own build of the bot (a real mod jar for that release, which is what lets that
# release's mods load), then runs the WebSocket probe: join the world, check the tab list, take a
# GPU-free screenshot. This is the evidence behind `nativeClients[].status` in
# minecraft-versions.json — `jar-built` becomes `runtime-verified` only after passing here.
#
# Contrast with scripts/verify_versions.sh, which proves the opposite axis: ONE newest client
# reaching many SERVER versions through ViaFabricPlus (no per-version mod support).
#
# Usage: scripts/verify_native.sh [version ...]     (default: every entry in nativeClients)
# Env:   WS_PORT (8731), BOT_NAME (ClefBot), CLEF_WORLD_TIMEOUT (240)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
REPORT="$ROOT/e2e/native-report.json"
mkdir -p "$ROOT/e2e"

if [[ $# -gt 0 ]]; then
  VERSIONS=("$@")
else
  mapfile -t VERSIONS < <(python3 -c "
import json
for e in json.load(open('minecraft-versions.json'))['nativeClients']:
    print(e['minecraft'])")
fi

pass=0; total=0; results=""
for v in "${VERSIONS[@]}"; do
  total=$((total+1))
  # The newest release is the root project; every other target is its own module.
  if [[ "$v" == "$(sed -n 's/^minecraft_version=//p' gradle.properties)" ]]; then
    # NOTE the leading colon: every version module also defines runClient, so the unqualified
    # name would launch all 16 clients instead of just the root project's.
    task=":runClient"; rundir="run"
  else
    task=":versions:fabric-${v}:runClient"; rundir="versions/fabric-${v}/run"
  fi
  echo "[native] ===== $v (task $task) ====="
  pkill -f "mezzoclef.headless=true" 2>/dev/null || true
  pkill -f "server.jar nogui" 2>/dev/null || true
  sleep 2
  rm -f "$ROOT/e2e/bot.log"
  if MC_VERSION="$v" CLEF_RUN_TASK="$task" CLEF_RUN_DIR="$rundir" \
     CLEF_WORLD_TIMEOUT="${CLEF_WORLD_TIMEOUT:-240}" CLEF_CONNECT_TIMEOUT=300 \
     bash "$ROOT/scripts/e2e.sh" > "$ROOT/e2e/native-$v.out" 2>&1; then
    echo "[native] $v PASS"; pass=$((pass+1)); ok=true
  else
    echo "[native] $v FAIL — see e2e/native-$v.out and e2e/bot.log"
    # Surface the usual suspect (a mixin whose target moved in this release).
    grep -m1 -oE "InvalidInjectionException.*|InvalidMixinException.*" "$ROOT/e2e/bot.log" 2>/dev/null | cut -c1-200 || true
    cp "$ROOT/e2e/bot.log" "$ROOT/e2e/native-$v-bot.log" 2>/dev/null || true
    ok=false
  fi
  [[ -f "$ROOT/e2e/e2e-shot.png" ]] && mv "$ROOT/e2e/e2e-shot.png" "$ROOT/e2e/native-shot-$v.png"
  # Drop the downloaded server + generated world before moving on. A full matrix run otherwise
  # leaves a server jar and world per version sitting around, which is gigabytes across the
  # matrix; the logs and screenshots we actually want as evidence are already copied out above.
  if [[ "${KEEP_SERVERS:-0}" != "1" ]]; then
    rm -rf "$ROOT/e2e/servers/$v"
  fi
  results="${results}{\"minecraft\":\"$v\",\"ok\":$ok},"
done

printf '{"passed":%d,"total":%d,"results":[%s]}\n' "$pass" "$total" "${results%,}" > "$REPORT"
echo "[native] $pass/$total native client builds joined a real server of their own version -> $REPORT"
[[ $pass -eq $total ]]
