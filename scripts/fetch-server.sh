#!/usr/bin/env bash
# Downloads the vanilla Minecraft server jar for $MC_VERSION and writes an offline-mode
# server.properties suitable for the e2e test (offline so our offline bot can join).
set -euo pipefail

MC_VERSION="${MC_VERSION:-1.21.8}"
DIR="${1:-e2e/server}"
mkdir -p "$DIR"

echo "[server] resolving $MC_VERSION ..."
VURL=$(curl -fsSL https://launchermeta.mojang.com/mc/game/version_manifest_v2.json \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print(next(v['url'] for v in d['versions'] if v['id']=='$MC_VERSION'))")
SURL=$(curl -fsSL "$VURL" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['downloads']['server']['url'])")

echo "[server] downloading $SURL"
curl -fsSL "$SURL" -o "$DIR/server.jar"

echo "eula=true" > "$DIR/eula.txt"
cat > "$DIR/server.properties" <<'EOF'
online-mode=false
server-port=25565
gamemode=creative
force-gamemode=true
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
view-distance=6
simulation-distance=6
max-players=5
allow-flight=true
enable-command-block=false
motd=MezzoSopranoClef E2E
EOF

echo "[server] ready in $DIR (online-mode=false)"
