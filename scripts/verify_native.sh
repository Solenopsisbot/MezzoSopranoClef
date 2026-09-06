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
  # Targets are named "<mc>" for Fabric (the default loader) and "<loader>-<mc>" otherwise.
  # Only targets that are expected to boot. An entry recorded `jar-built` is one the matrix file
  # itself says does not run yet (forge-1.20.1: "mixins never apply"), so running it by default
  # would fail the nightly job on every scheduled run regardless of any real regression. Name a
  # target explicitly to run it anyway.
  mapfile -t VERSIONS < <(python3 -c "
import json
for e in json.load(open('minecraft-versions.json'))['nativeClients']:
    if e.get('status') == 'jar-built':
        continue
    loader = e.get('loader', 'fabric')
    print(e['minecraft'] if loader == 'fabric' else loader + '-' + e['minecraft'])")
fi

pass=0; total=0; results=""
for v in "${VERSIONS[@]}"; do
  total=$((total+1))
  # Split "<loader>-<mc>" into its parts; a bare version means Fabric.
  if [[ "$v" == *-* ]]; then loader="${v%%-*}"; mc="${v#*-}"; else loader="fabric"; mc="$v"; fi
  # The newest Fabric release is the root project; every other target is its own module.
  if [[ "$loader" == "fabric" && "$mc" == "$(sed -n 's/^minecraft_version=//p' gradle.properties)" ]]; then
    # NOTE the leading colon: every version module also defines runClient, so the unqualified
    # name would launch every client instead of just the root project's.
    task=":runClient"; rundir="run"
  else
    task=":versions:${loader}-${mc}:runClient"; rundir="versions/${loader}-${mc}/run"
  fi
  echo "[native] ===== $v (task $task) ====="
  # Deliberately no pkill-by-name here: e2e.sh already kills the PIDs it started, and killing by
  # process name would take out an unrelated Minecraft on the same machine (fixed on main in
  # "Stop the test harnesses killing other people's Minecraft processes").
  # Wait for the previous target's client JVM to actually exit. pkill only signals it; a client
  # still shutting down while the next one boots means two Minecraft JVMs competing for CPU, and
  # the new one then misses keep-alives and gets dropped by its server ("lost connection: Timed
  # out"). That is what made older targets fail intermittently in long serial runs.
  # Match only clients launched from THIS checkout, so a wait can never be held up by — or
  # confused with — somebody else's bot running on the same machine.
  for _ in $(seq 1 60); do
    pgrep -af "mezzoclef.headless=true" 2>/dev/null | grep -q "$ROOT" || break
    sleep 1
  done
  # Wait for the previous target's server to actually release the port. A blind sleep is not
  # enough on a long serial run: if the old server still holds 25565 the next one cannot
  # bind, and the client ends up talking to the wrong version (or nothing).
  for _ in $(seq 1 30); do
    lsof -nP -iTCP:"${CLEF_SERVER_PORT:-25565}" -sTCP:LISTEN >/dev/null 2>&1 || break
    sleep 1
  done
  rm -f "$ROOT/e2e/bot.log"
  if MC_VERSION="$mc" CLEF_RUN_TASK="$task" CLEF_RUN_DIR="$rundir" \
     CLEF_WORLD_TIMEOUT="${CLEF_WORLD_TIMEOUT:-240}" CLEF_CONNECT_TIMEOUT=300 \
     bash "$ROOT/scripts/e2e.sh" > "$ROOT/e2e/native-$v.out" 2>&1; then
    echo "[native] $v PASS"; pass=$((pass+1)); ok=true
  else
    echo "[native] $v FAIL — see e2e/native-$v.out and e2e/bot.log"
    # Surface the usual suspect (a mixin whose target moved in this release).
    grep -m1 -oE "InvalidInjectionException.*|InvalidMixinException.*" "$ROOT/e2e/bot.log" 2>/dev/null | cut -c1-200 || true
    cp "$ROOT/e2e/bot.log" "$ROOT/e2e/native-$v-bot.log" 2>/dev/null || true
    # Keep the SERVER log too — without it a join failure is undiagnosable, and the cleanup
    # below is about to delete the whole server directory.
    cp "$ROOT/e2e/servers/$mc/server.log" "$ROOT/e2e/native-$v-server.log" 2>/dev/null || true
    ok=false
  fi
  [[ -f "$ROOT/e2e/e2e-shot.png" ]] && mv "$ROOT/e2e/e2e-shot.png" "$ROOT/e2e/native-shot-$v.png"
  # Drop the downloaded server + generated world before moving on. A full matrix run otherwise
  # leaves a server jar and world per version sitting around, which is gigabytes across the
  # matrix; the logs and screenshots we actually want as evidence are already copied out above.
  if [[ "${KEEP_SERVERS:-0}" != "1" ]]; then
    rm -rf "$ROOT/e2e/servers/$mc"
  fi
  results="${results}{\"minecraft\":\"$mc\",\"loader\":\"$loader\",\"ok\":$ok},"
done

printf '{"passed":%d,"total":%d,"results":[%s]}\n' "$pass" "$total" "${results%,}" > "$REPORT"
echo "[native] $pass/$total native client builds joined a real server of their own version -> $REPORT"
[[ $pass -eq $total ]]
