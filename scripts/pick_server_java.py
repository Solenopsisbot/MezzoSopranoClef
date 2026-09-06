#!/usr/bin/env python3
"""
Pick a JDK that can run a vanilla Minecraft *server* of a given version, and print its `java`.

Every Minecraft release has a Java floor (and the oldest ones a practical ceiling), so a
multi-version test matrix needs several JDKs. We look in the usual places (JAVA_HOME_<N> env
vars, Gradle's auto-provisioned toolchains in ~/.gradle/jdks, macOS java_home, /usr/lib/jvm)
and pick the best fit. Exits 1 with a hint if nothing usable is installed.

Usage: pick_server_java.py <minecraft-version>    e.g. 1.12.2, 1.20.4, 26.2
"""
import glob
import os
import re
import subprocess
import sys


def required_range(mc: str):
    """(min_major, max_major) the vanilla server of `mc` runs on."""
    parts = [int(p) for p in re.findall(r"\d+", mc)]
    if parts[0] >= 26:                    # 26.1+ (year-based versions)
        return 25, 99
    minor = parts[1] if len(parts) > 1 else 0
    patch = parts[2] if len(parts) > 2 else 0
    if minor <= 16:                       # 1.12.2 .. 1.16.5: Java 8 era (stay <=16 to be safe)
        return 8, 16
    if minor == 17:                       # 1.17.x
        return 16, 25
    if minor <= 19 or (minor == 20 and patch <= 4):   # 1.18 .. 1.20.4
        return 17, 25
    return 21, 25                         # 1.20.5 .. 1.21.x


def java_major(java_bin: str):
    try:
        out = subprocess.run([java_bin, "-version"], capture_output=True, text=True, timeout=20)
    except Exception:
        return None
    m = re.search(r'version "(\d+)(?:\.(\d+))?', out.stderr + out.stdout)
    if not m:
        return None
    major = int(m.group(1))
    if major == 1 and m.group(2):          # "1.8.0_xxx"
        major = int(m.group(2))
    return major


def candidates():
    seen = []
    for key, val in os.environ.items():
        if key.startswith("JAVA_HOME") and val:
            seen.append(os.path.join(val, "bin", "java"))
    home = os.path.expanduser("~")
    for pat in (
        f"{home}/.gradle/jdks/*/Contents/Home/bin/java",   # macOS toolchains
        f"{home}/.gradle/jdks/*/*/Contents/Home/bin/java", # (Gradle nests the extracted JDK one level down)
        f"{home}/.gradle/jdks/*/bin/java",                  # linux toolchains
        f"{home}/.gradle/jdks/*/*/bin/java",
        "/Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java",
        f"{home}/Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java",
        "/usr/lib/jvm/*/bin/java",
        "/opt/hostedtoolcache/Java_*/*/x64/bin/java",       # GitHub Actions setup-java
        "/opt/hostedtoolcache/Java_*/*/arm64/bin/java",
    ):
        seen.extend(sorted(glob.glob(pat)))
    seen.append("java")                                      # whatever is on PATH, last resort
    out, dedup = [], set()
    for c in seen:
        real = os.path.realpath(c) if c != "java" else c
        if real not in dedup and (c == "java" or os.access(c, os.X_OK)):
            dedup.add(real)
            out.append(c)
    return out


def main():
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    lo, hi = required_range(sys.argv[1])
    best = None
    for c in candidates():
        major = java_major(c)
        if major is None or major < lo or major > hi:
            continue
        # Prefer the OLDEST allowed Java: it is the one that release was actually tested on, so
        # it is the conservative choice for a compatibility matrix. Ties: discovery order.
        if best is None or major < best[0]:
            best = (major, c)
    if not best:
        print(f"no JDK in [{lo},{hi}] found for Minecraft {sys.argv[1]}; install one (Gradle can: "
              f"./gradlew -q javaToolchains) or set JAVA_HOME_{lo}", file=sys.stderr)
        return 1
    print(best[1])
    return 0


if __name__ == "__main__":
    sys.exit(main())
