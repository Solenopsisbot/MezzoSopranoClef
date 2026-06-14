#!/usr/bin/env bash
# A/B benchmark of the muted-sound short-circuit, end-to-end against a REAL Minecraft 1.21.8
# server + the real headless bot. Boots the server, boots the bot once, then bench_subsystems.py
# applies a fixed in-world load (a mob horde whose ambient sounds drive the load) and toggles the
# sound disable on/off/on IN-PROCESS, comparing process CPU over equal windows (same world + JIT).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
WS_PORT="${WS_PORT:-8731}"; BOT="${BOT_NAME:-ClefBot}"; SERVER_JAVA="${SERVER_JAVA:-java}"
SDIR="$ROOT/e2e/server"; WINDOW="${WINDOW:-60}"; SETTLE="${SETTLE:-10}"; SUMMON="${SUMMON:-48}"
FIFO="$SDIR/console.in"
mkdir -p "$ROOT/run/config"; : > "$ROOT/e2e/bench.out"

SRV=""
cleanup() {
  echo "[bench] cleanup..."
  pkill -f 'knot.KnotClient' 2>/dev/null || true
  exec 3>&- 2>/dev/null || true
  [[ -n "$SRV" ]] && kill "$SRV" 2>/dev/null || true
  rm -f "$FIFO"
  wait 2>/dev/null || true
}
trap cleanup EXIT

# 1) server with a console FIFO so we can `op` the bot
rm -f "$FIFO"; mkfifo "$FIFO"
echo "[bench] starting server..."
( cd "$SDIR" && exec "$SERVER_JAVA" -Xmx2G -jar server.jar nogui ) < "$FIFO" > "$SDIR/server.log" 2>&1 &
SRV=$!
exec 3>"$FIFO"   # hold the write end open so the server's stdin never hits EOF
for _ in $(seq 1 180); do
  grep -q 'Done (' "$SDIR/server.log" 2>/dev/null && break
  kill -0 "$SRV" 2>/dev/null || { echo "[bench] server died"; tail -20 "$SDIR/server.log"; exit 1; }
  sleep 1
done
grep -q 'Done (' "$SDIR/server.log" 2>/dev/null || { echo "[bench] server start timeout"; exit 1; }
echo "[bench] server up."

# 2) bot config: offline, auto-connect, software screenshots
cat > "$ROOT/run/config/mezzoclef.json" <<EOF
{
  "auth": { "mode": "offline", "offlineUsername": "$BOT" },
  "connection": { "autoConnect": true, "serverHost": "127.0.0.1", "serverPort": 25565 },
  "control": { "enabled": true, "host": "127.0.0.1", "port": $WS_PORT, "authToken": "" },
  "screenshot": { "backend": "software" },
  "headless": true,
  "headlessLoopSleepMs": 5
}
EOF

run_bot() {  # $1=label  $2=extra gradle args
  local label="$1" extra="$2"
  local before; before=$(grep -c 'joined the game' "$SDIR/server.log" 2>/dev/null || true); before=${before:-0}
  echo "[bench] launching bot run '$label' (args: ${extra:-none})..."
  ( ./gradlew runClient --console=plain --offline $extra > "$ROOT/e2e/bot-$label.log" 2>&1 ) &
  local gpid=$!
  for _ in $(seq 1 200); do
    local now; now=$(grep -c 'joined the game' "$SDIR/server.log" 2>/dev/null || true); now=${now:-0}
    [[ "$now" -gt "$before" ]] && { echo "[bench] '$label' joined the world."; break; }
    kill -0 "$gpid" 2>/dev/null || { echo "[bench] bot '$label' exited early"; tail -15 "$ROOT/e2e/bot-$label.log"; break; }
    sleep 2
  done
  echo "op $BOT" >&3; sleep 2     # opped server-side so the bot's /summon etc. execute
  CLEF_WS_PORT="$WS_PORT" CLEF_LABEL="$label" CLEF_WINDOW="$WINDOW" CLEF_SETTLE="$SETTLE" \
    CLEF_SUMMON="$SUMMON" python3 "$ROOT/scripts/bench_subsystems.py" 2>&1 | tee -a "$ROOT/e2e/bench.out"
  pkill -f 'knot.KnotClient' 2>/dev/null || true
  kill "$gpid" 2>/dev/null || true
  sleep 6
}

# Single boot; the sampler does the in-process on/off/on A/B itself.
run_bot optimized ""

echo ""; echo "================= SUBSYSTEM-DISABLE E2E BENCHMARK ================="
python3 - <<'PY'
import json
r = None
for line in open("e2e/bench.out"):
    if line.startswith("RESULT "): r = json.loads(line[7:])
if not r:
    print("  no RESULT captured — see e2e/bot-optimized.log"); raise SystemExit
print(f"  load: {r['entities']} entities, window {r['windowSec']}s x3 (same process/world/JIT)")
print(f"  suppressed while ON: {r['soundsSuppressedPerSec_ON']} sounds/s")
print(f"  client CPU  ON (sound disabled) : {r['ON_cores']} cores  (runs: {r['ON1_cores']}, {r['ON2_cores']})")
print(f"  client CPU  OFF (vanilla)       : {r['OFF_cores']} cores")
print(f"  => saved {r['savedCores']} cores ({r['savedPct']}%) by disabling muted sound")
PY
echo "=================================================================="
