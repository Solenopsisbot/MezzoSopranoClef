#!/usr/bin/env python3
"""
Multi-version server matrix: prove ONE running bot (built for the newest Minecraft) can join
servers on many older releases through ViaFabricPlus.

For each requested version this script downloads the vanilla server jar (cached under
e2e/servers/<version>), boots it on the matching JDK, tells the already-running bot to
`connect` with that protocol version, waits until it is in the world, checks the tab list and a
GPU-free screenshot, disconnects, stops the server, and moves on. One client boot for the whole
matrix — the slow part — instead of one per version.

The bot must already be up with the control plane on CLEF_WS_HOST:CLEF_WS_PORT and autoConnect
off; scripts/verify_versions.sh does that for you.

Usage:
  verify_versions.py [--auto] [--report FILE] [version ...]
    versions   server releases to test; default = one per protocol era (see DEFAULT_VERSIONS)
    --auto     let ViaFabricPlus auto-detect the server version (ping + match) instead of
               forcing it explicitly — exercises the bot's default "serverVersion": "auto" path
    --all      every official release from 1.12.2 through the client's own version (slow)
    --report   write a JSON report (default e2e/versions-report.json)

Env: CLEF_WS_HOST (127.0.0.1), CLEF_WS_PORT (8731), CLEF_WS_TOKEN (optional), CLEF_BOT_NAME
     (ClefBot), SERVER_PORT (25565), CLEF_WORLD_TIMEOUT (120s per version).
Exit code 0 = every version passed.
"""
import base64
import json
import os
import re
import signal
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ws_probe import PNG_SIG, call, connect  # noqa: E402  (stdlib-only WebSocket helpers)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# One release per protocol era / world-format epoch. Everything in between shares its wire
# format with a neighbour here, so this is the cheap set that still covers every ViaVersion
# translation stage from 1.12.2 to the current client.
DEFAULT_VERSIONS = [
    "1.12.2", "1.13.2", "1.14.4", "1.15.2", "1.16.5", "1.17.1", "1.18.2", "1.19.4",
    "1.20.1", "1.20.4", "1.20.6", "1.21.1", "1.21.4", "1.21.8", "1.21.11", "26.1", "26.2",
]


def log(msg):
    print(f"[versions] {msg}", flush=True)


def version_key(v):
    return [int(x) for x in re.findall(r"\d+", v)]


def all_releases(client_version):
    """Every official release id from 1.12.2 through client_version, from Mojang's manifest."""
    import urllib.request
    with urllib.request.urlopen("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json") as r:
        manifest = json.load(r)
    ids = [v["id"] for v in manifest["versions"] if v["type"] == "release"]
    lo, hi = version_key("1.12.2"), version_key(client_version)
    return sorted([i for i in ids if lo <= version_key(i) <= hi], key=version_key)


def fetch_server(version, port):
    d = os.path.join(ROOT, "e2e", "servers", version)
    env = dict(os.environ, MC_VERSION=version, SERVER_PORT=str(port))
    subprocess.run(["bash", os.path.join(ROOT, "scripts", "fetch-server.sh"), d], check=True, env=env,
                   stdout=subprocess.DEVNULL)
    return d


def pick_java(version):
    out = subprocess.run([sys.executable, os.path.join(ROOT, "scripts", "pick_server_java.py"), version],
                         capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip())
    return out.stdout.strip()


def start_server(version, port, timeout=240):
    d = fetch_server(version, port)
    java = pick_java(version)
    logf = open(os.path.join(d, "server.log"), "w")
    proc = subprocess.Popen([java, "-Xmx2G", "-jar", "server.jar", "nogui"], cwd=d,
                            stdout=logf, stderr=subprocess.STDOUT, stdin=subprocess.PIPE)
    log(f"{version}: server starting (java={java}, pid={proc.pid})")
    end = time.time() + timeout
    while time.time() < end:
        if proc.poll() is not None:
            raise RuntimeError(f"server exited early (rc={proc.returncode}); see {d}/server.log")
        with open(os.path.join(d, "server.log"), errors="replace") as f:
            if "Done (" in f.read():
                return proc, d
        time.sleep(1)
    proc.kill()
    raise RuntimeError(f"server did not finish booting in {timeout}s")


def stop_server(proc):
    if proc.poll() is not None:
        return
    try:
        proc.stdin.write(b"stop\n")
        proc.stdin.flush()
    except Exception:
        pass
    end = time.time() + 30
    while time.time() < end and proc.poll() is None:
        time.sleep(0.5)
    if proc.poll() is None:
        proc.send_signal(signal.SIGTERM)
        try:
            proc.wait(15)
        except subprocess.TimeoutExpired:
            proc.kill()


def wait_status(s, predicate, timeout):
    end = time.time() + timeout
    st = None
    while time.time() < end:
        st = call(s, "status")
        if predicate(st):
            return st
        time.sleep(1.5)
    return None


def test_version(s, version, port, auto, world_timeout, bot_name, outdir):
    result = {"version": version, "selected": None, "ok": False}
    t0 = time.time()
    proc = None
    try:
        proc, d = start_server(version, port)
        result["serverDir"] = d
        want = "auto" if auto else version
        r = call(s, "connect", host="127.0.0.1", port=port, version=want)
        result["selected"] = r.get("version")
        log(f"{version}: connect requested (protocol '{want}' -> '{r.get('version')}')")
        st = wait_status(s, lambda x: x.get("inWorld"), world_timeout)
        if not st:
            raise RuntimeError("bot never entered the world")
        result["protocol"] = st.get("protocol")
        names = [p["name"] for p in call(s, "players")]
        if bot_name not in names:
            raise RuntimeError(f"bot '{bot_name}' missing from tab list {names}")
        shot = call(s, "screenshot", timeout=60, width=320, height=180)
        raw = base64.b64decode(shot["base64"])
        if raw[:8] != PNG_SIG:
            raise RuntimeError("screenshot is not a PNG")
        with open(os.path.join(outdir, f"shot-{version}.png"), "wb") as f:
            f.write(raw)
        call(s, "chat", message=f"MezzoSopranoClef matrix: {version} OK")
        result["ok"] = True
        result["players"] = names
    except Exception as e:  # noqa: BLE001
        result["error"] = str(e)
        log(f"{version}: FAIL — {e}")
    finally:
        try:
            call(s, "disconnect")
            wait_status(s, lambda x: not x.get("inWorld"), 30)
        except Exception:
            pass
        if proc is not None:
            stop_server(proc)
        result["seconds"] = round(time.time() - t0, 1)
    return result


def main(argv):
    auto = "--auto" in argv
    use_all = "--all" in argv
    report = os.path.join(ROOT, "e2e", "versions-report.json")
    if "--report" in argv:
        report = argv[argv.index("--report") + 1]
    versions = [a for a in argv if not a.startswith("--") and a != report]
    client_version = re.search(r"^minecraft_version=(.+)$",
                               open(os.path.join(ROOT, "gradle.properties")).read(), re.M).group(1).strip()
    if use_all:
        versions = all_releases(client_version)
    elif not versions:
        versions = DEFAULT_VERSIONS
    versions = sorted(set(versions), key=version_key)

    host = os.environ.get("CLEF_WS_HOST", "127.0.0.1")
    ws_port = int(os.environ.get("CLEF_WS_PORT", "8731"))
    port = int(os.environ.get("SERVER_PORT", "25565"))
    bot_name = os.environ.get("CLEF_BOT_NAME", "ClefBot")
    world_timeout = int(os.environ.get("CLEF_WORLD_TIMEOUT", "120"))
    outdir = os.path.join(ROOT, "e2e")
    os.makedirs(outdir, exist_ok=True)

    s = connect(host, ws_port, int(os.environ.get("CLEF_CONNECT_TIMEOUT", "240")))
    token = os.environ.get("CLEF_WS_TOKEN")
    if token:
        assert call(s, "hello", token=token).get("authed") is True
    proto = call(s, "protocol")
    log(f"client native={proto.get('native')} viafabricplus={'yes' if proto.get('available') else 'NO: ' + str(proto.get('reason'))}")
    if not proto.get("available"):
        log("ViaFabricPlus is not loaded in the bot; only the native version can pass.")
    known = {v["name"] for v in proto.get("versions", [])}
    log(f"testing {len(versions)} server version(s), protocol selection = {'auto-detect' if auto else 'explicit'}")

    results = []
    for v in versions:
        if not auto and known and v not in known and v != proto.get("native"):
            results.append({"version": v, "ok": False, "error": "ViaFabricPlus does not know this release"})
            log(f"{v}: SKIP — not a ViaFabricPlus-known release")
            continue
        results.append(test_version(s, v, port, auto, world_timeout, bot_name, outdir))
        # Give the bot a moment to settle back on the title screen between servers.
        time.sleep(2)

    passed = sum(1 for r in results if r["ok"])
    with open(report, "w") as f:
        json.dump({"client": client_version, "auto": auto, "viafabricplus": proto.get("available"),
                   "results": results, "passed": passed, "total": len(results)}, f, indent=2)
    log("")
    log(f"{'version':<10} {'result':<6} {'protocol':<14} {'secs':>6}  detail")
    for r in results:
        log(f"{r['version']:<10} {'PASS' if r['ok'] else 'FAIL':<6} {str(r.get('selected') or '-'):<14} "
            f"{r.get('seconds', 0):>6}  {r.get('error', '')}")
    log(f"{passed}/{len(results)} server versions joinable by the {client_version} client -> {report}")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except Exception as e:  # noqa: BLE001
        print(f"[versions] FAIL: {e}", file=sys.stderr)
        sys.exit(1)
