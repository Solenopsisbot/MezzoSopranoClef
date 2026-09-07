#!/usr/bin/env python3
"""
Check every @Accessor in a version module against that release's real Minecraft classes.

A mismatched @Accessor type — or a field that was renamed or moved between releases — compiles
perfectly and then kills the game at class-load time with InvalidAccessorException. Finding that by
booting costs a full end-to-end run per mistake, so this reads the Loom-remapped jar with `javap -p`
and compares declared against actual up front.

Getters are checked on their return type, setters (void) on their single parameter type.

Usage: check_accessors.py <mcVersion> [...]     e.g. check_accessors.py 1.15.2 1.16.5
Exit status is 1 if anything mismatched.
"""
import glob
import os
import re
import subprocess
import sys

# `@Accessor("f")`, any further annotations (@Mutable, ...), then the method declaration.
ACCESSOR = re.compile(
    r'@Accessor\(\s*"(?P<field>\w+)"\s*\)'
    r'(?:\s*@\w+)*'
    r'\s*(?P<ret>[\w.$]+(?:\s*<[^>]*>)?)\s+[\w$]+\s*\((?P<params>[^)]*)\)',
    re.S)


def strip_generics(text):
    """Remove balanced <...> groups. javap prints generics inline, and they contain spaces."""
    out, depth = [], 0
    for ch in text:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth = max(0, depth - 1)
        elif depth == 0:
            out.append(ch)
    return "".join(out)


def leaf(type_name):
    """Comparable leaf of a type: drop generics/package, treat Outer$Inner and Outer.Inner alike."""
    t = re.sub(r"<.*", "", type_name).strip().replace("$", ".")
    return t.split(".")[-1]


def jar_for(mc):
    base = os.path.expanduser("~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft")
    pats = [
        # Obfuscated releases: Loom remaps to the layered Mojang mappings.
        f"{base}/minecraft-merged/*/minecraft-merged-{mc}-loom.mappings*.jar",
        # 26.x ships unobfuscated, so Loom only deobfuscates rather than remapping.
        f"{base}/minecraft-merged-deobf/{mc}/minecraft-merged-deobf-{mc}.jar",
    ]
    for pat in pats:
        hits = [p for p in glob.glob(pat) if "sources" not in p]
        if hits:
            return hits[0]
    return None


def source_root(mc):
    """Where that release's mixins live: its own module, or the root project for the newest one."""
    module = f"versions/fabric-{mc}/src/main/java/dev/mezzo/clef/mixin"
    if os.path.isdir(module):
        return module
    # The newest release is the root project rather than a versions/ module.
    try:
        props = open("gradle.properties").read()
    except OSError:
        return module
    newest = re.search(r"^minecraft_version=(.+)$", props, re.M)
    if newest and newest.group(1).strip() == mc:
        return "src/main/java/dev/mezzo/clef/mixin"
    return module


def check(mc):
    jar = jar_for(mc)
    if not jar:
        print(f"{mc}: no remapped jar yet — build that module once first")
        return 0
    bad = 0
    for path in sorted(glob.glob(source_root(mc) + "/**/*.java", recursive=True)):
        src = open(path).read()
        m = re.search(r"@Mixin\((\w+)\.class\)", src)
        if not m:
            continue
        simple = m.group(1)
        imp = re.search(r"^import ([\w.]*\." + re.escape(simple) + r");", src, re.M)
        fq = imp.group(1) if imp else simple
        out = subprocess.run(["javap", "-p", "-cp", jar, fq],
                             capture_output=True, text=True).stdout
        if not out.strip():
            print(f"MISSING  {os.path.basename(path)}: class {fq} not in {mc}")
            bad += 1
            continue
        for a in ACCESSOR.finditer(src):
            field, ret, params = a.group("field"), a.group("ret"), a.group("params").strip()
            # a void accessor is a setter: the field type is its single parameter's type
            declared = ret if ret != "void" else params.split()[0] if params else "void"
            hit = [l.strip() for l in out.splitlines() if re.search(r"\b" + field + r"\s*;", l)]
            if not hit:
                print(f"MISSING  {os.path.basename(path)}: field '{field}' not on {fq}")
                bad += 1
                continue
            # Mixin matches on the erased type, so compare erasures.
            actual = strip_generics(hit[0]).rstrip(";").split()[-2]
            if leaf(actual) != leaf(declared):
                print(f"MISMATCH {os.path.basename(path)}: {field} declared={declared} actual={actual}")
                bad += 1
    print(f"{mc}: {'OK' if not bad else str(bad) + ' problem(s)'}")
    return bad


if __name__ == "__main__":
    sys.exit(1 if sum(check(v) for v in sys.argv[1:]) else 0)
