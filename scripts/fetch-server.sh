#!/usr/bin/env bash
# Downloads the vanilla Minecraft server jar for $MC_VERSION and writes an offline-mode
# server.properties suitable for the e2e tests (offline so our offline bot can join).
# Works for every release in the support matrix (1.12.2 .. 26.2): the only version-specific bit
# is the flat-world level-type spelling, which changed in 1.16.
set -euo pipefail

MC_VERSION="${MC_VERSION:-26.2}"
DIR="${1:-e2e/server}"
PORT="${SERVER_PORT:-25565}"
mkdir -p "$DIR"

if [[ ! -f "$DIR/server.jar" ]]; then
  echo "[server] resolving $MC_VERSION ..."
  VURL=$(curl -fsSL --connect-timeout 30 --max-time 120 --retry 3 https://piston-meta.mojang.com/mc/game/version_manifest_v2.json \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print(next(v['url'] for v in d['versions'] if v['id']=='$MC_VERSION'))")
  SURL=$(curl -fsSL --connect-timeout 30 --max-time 120 --retry 3 "$VURL" \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['downloads']['server']['url'])")
  echo "[server] downloading $SURL"
  # Retry, then check what actually landed. A transient curl failure (HTTP/2 PROTOCOL_ERROR shows
  # up often enough) used to leave no jar while the script still said "ready", so the run failed
  # later with a baffling "Unable to access jarfile server.jar" instead of naming the download.
  # --speed-limit/--speed-time is the important pair: a stalled transfer produces no error and
  # no bytes, so without it curl waits forever and one target hangs the whole matrix run.
  if ! curl -fsSL --retry 3 --retry-delay 2 --retry-all-errors \
          --connect-timeout 30 --max-time 900 --speed-limit 1024 --speed-time 60 \
          "$SURL" -o "$DIR/server.jar.part"; then
    rm -f "$DIR/server.jar.part"
    echo "[server] FAILED to download the $MC_VERSION server jar from $SURL" >&2
    exit 1
  fi
  # A server jar is tens of MB; anything tiny is an error page or a truncated transfer.
  SIZE=$(wc -c < "$DIR/server.jar.part" | tr -d ' ')
  if [[ "$SIZE" -lt 1000000 ]]; then
    rm -f "$DIR/server.jar.part"
    echo "[server] FAILED: downloaded $MC_VERSION server jar is only ${SIZE} bytes" >&2
    exit 1
  fi
  mv "$DIR/server.jar.part" "$DIR/server.jar"
else
  echo "[server] reusing cached $DIR/server.jar"
fi

# 1.12 .. 1.15 spell the flat preset "FLAT"; 1.16+ uses the registry id.
LEVEL_TYPE='minecraft\:flat'
if python3 - "$MC_VERSION" <<'PY'
import re,sys
p=[int(x) for x in re.findall(r"\d+", sys.argv[1])]
sys.exit(0 if (p[0]==1 and len(p)>1 and p[1]<16) else 1)
PY
then LEVEL_TYPE='FLAT'; fi

echo "eula=true" > "$DIR/eula.txt"
cat > "$DIR/server.properties" <<EOP
online-mode=false
server-port=$PORT
gamemode=creative
force-gamemode=true
level-type=$LEVEL_TYPE
generate-structures=false
spawn-protection=0
view-distance=6
simulation-distance=6
max-players=5
allow-flight=true
enable-command-block=false
sync-chunk-writes=false
motd=MezzoSopranoClef E2E $MC_VERSION
EOP

# Op the bot so the e2e probe can drive a server command (/setblock) and assert that the
# packet-derived event stream actually delivers — subscribing succeeds even when a mixin was never
# registered, so the events have to be provoked to be proven. Offline UUIDs are deterministic:
# Java's UUID.nameUUIDFromBytes("OfflinePlayer:<name>"), i.e. MD5 with the v3/IETF bits set.
BOT_NAME="${BOT_NAME:-ClefBot}"
python3 - "$DIR" "$BOT_NAME" <<'PY'
import hashlib, json, pathlib, sys, uuid
out, name = pathlib.Path(sys.argv[1]), sys.argv[2]
b = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest())
b[6] = (b[6] & 0x0f) | 0x30
b[8] = (b[8] & 0x3f) | 0x80
(out / "ops.json").write_text(json.dumps(
    [{"uuid": str(uuid.UUID(bytes=bytes(b))), "name": name, "level": 4,
      "bypassesPlayerLimit": False}], indent=2) + "\n")
PY

echo "[server] ready in $DIR (Minecraft $MC_VERSION, online-mode=false, port $PORT, $BOT_NAME opped)"
