#!/usr/bin/env python3
"""
Fold a scripts/verify_native.sh report into minecraft-versions.json.

A native client target is promoted to `runtime-verified` only when that version's own build booted
and joined a real server of that version. Failures are recorded as `jar-built` again (the jar still
compiles) plus an `error`, so the file never over-claims.

Usage: update_native_matrix.py [report.json]   (default e2e/native-report.json)
"""
import datetime
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def main():
    report_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "e2e", "native-report.json")
    matrix_path = os.path.join(ROOT, "minecraft-versions.json")
    report = json.load(open(report_path))
    matrix = json.load(open(matrix_path))
    today = datetime.date.today().isoformat()
    # Keyed by (minecraft, loader): 1.21.8 has both a Fabric and a NeoForge target, and they are
    # separate pieces of evidence. Reports predating the loader field are treated as Fabric.
    by_target = {(r["minecraft"], r.get("loader", "fabric")): r for r in report["results"]}
    for entry in matrix["nativeClients"]:
        r = by_target.get((entry["minecraft"], entry.get("loader", "fabric")))
        if not r:
            continue
        entry.pop("error", None)
        if r.get("ok"):
            entry["status"] = "runtime-verified"
            entry["verifiedOn"] = today
        else:
            entry["status"] = "jar-built"
            entry.pop("verifiedOn", None)
            entry["error"] = "did not join a real server on the last native matrix run"
    with open(matrix_path, "w") as f:
        json.dump(matrix, f, indent=2)
        f.write("\n")
    verified = sum(1 for e in matrix["nativeClients"] if e["status"] == "runtime-verified")
    print(f"native clients: {verified}/{len(matrix['nativeClients'])} runtime-verified")


if __name__ == "__main__":
    main()
