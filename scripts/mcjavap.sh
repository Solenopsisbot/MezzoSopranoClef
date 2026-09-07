#!/usr/bin/env bash
# Print the mojmap-remapped signatures of a Minecraft class for a given release.
#
# Porting a target to an older release is a compile loop: the compiler names a missing symbol, and
# you need that release's actual signature rather than a recollection of it. This reads the
# Loom-remapped merged jar that the module already built against, so the answer is ground truth.
#
# Usage: scripts/mcjavap.sh <mcVersion> <fqcn> [grep-pattern]
#   scripts/mcjavap.sh 1.18.2 net.minecraft.client.player.LocalPlayer chat
#   scripts/mcjavap.sh 1.18.2 --list Chat.*Packet      # list matching class names instead
set -euo pipefail

mc="${1:?usage: mcjavap.sh <mcVersion> <fqcn|--list> [pattern]}"
what="${2:?}"
pattern="${3:-}"

# Obfuscated releases are remapped to layered Mojang mappings; 26.x ships unobfuscated, so Loom
# only deobfuscates and names the artifact differently. Try both.
jar=$(find ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged \
        -name "minecraft-merged-${mc}-loom.mappings*.jar" 2>/dev/null | grep -v sources | head -1 || true)
[[ -n "$jar" ]] || jar=$(find ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf \
        -name "minecraft-merged-deobf-${mc}.jar" 2>/dev/null | grep -v sources | head -1 || true)
[[ -n "$jar" ]] || { echo "no remapped jar for $mc — build that module once first" >&2; exit 1; }

if [[ "$what" == "--list" ]]; then
  unzip -l "$jar" | awk '{print $4}' | grep -E '\.class$' | sed 's|/|.|g; s|\.class$||' | grep -E "$pattern"
else
  if [[ -n "$pattern" ]]; then javap -cp "$jar" "$what" | grep -iE "$pattern"
  else javap -cp "$jar" "$what"; fi
fi
