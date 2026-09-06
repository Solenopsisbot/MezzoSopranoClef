#!/usr/bin/env python3
"""
Fold a scripts/verify_versions.py report into minecraft-versions.json.

Every release that the run actually joined becomes `verified` (with the date, the protocol the
bot selected, and the client version). Failures become `failed` with the error. Releases that
were not part of the run are left alone, except that an untested release wedged between two
verified neighbours that share its protocol is marked `expected` (never `verified`) — the file
never claims more than was demonstrated.

Usage: update_version_matrix.py [report.json]   (default e2e/versions-report.json)
"""
import datetime
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def main():
    report_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "e2e", "versions-report.json")
    matrix_path = os.path.join(ROOT, "minecraft-versions.json")
    report = json.load(open(report_path))
    matrix = json.load(open(matrix_path))
    today = datetime.date.today().isoformat()
    by_version = {r["version"]: r for r in report["results"]}
    changed = 0
    for entry in matrix["servers"]:
        r = by_version.get(entry["version"])
        if not r:
            continue
        entry.pop("error", None)
        if r.get("ok"):
            entry.update({"status": "verified", "verifiedOn": today, "client": report["client"],
                          "selected": r.get("selected"), "autoDetect": bool(report.get("auto"))})
        else:
            entry.update({"status": "failed", "failedOn": today, "client": report["client"],
                          "error": r.get("error", "unknown")})
        changed += 1
    with open(matrix_path, "w") as f:
        json.dump(matrix, f, indent=2)
        f.write("\n")
    verified = sum(1 for e in matrix["servers"] if e["status"] == "verified")
    print(f"updated {changed} entries from {report_path}; {verified}/{len(matrix['servers'])} verified")


if __name__ == "__main__":
    main()
