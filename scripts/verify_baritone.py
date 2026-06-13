#!/usr/bin/env python3
"""Live test of Baritone integration + the higher-level inventory helpers."""
import base64, json, os, socket, struct, sys, time


def hs(h, p, timeout=10):
    s = socket.create_connection((h, p), timeout=timeout)
    k = base64.b64encode(os.urandom(16)).decode()
    s.sendall((f"GET / HTTP/1.1\r\nHost:x\r\nUpgrade:websocket\r\nConnection:Upgrade\r\n"
               f"Sec-WebSocket-Key:{k}\r\nSec-WebSocket-Version:13\r\n\r\n").encode())
    d = b""
    while b"\r\n\r\n" not in d:
        d += s.recv(1024)
    s.settimeout(timeout)
    return s


def send(s, t):
    p = t.encode(); m = os.urandom(4)
    mk = bytes(b ^ m[i % 4] for i, b in enumerate(p))
    h = bytearray([0x81]); n = len(p)
    if n < 126: h.append(0x80 | n)
    else: h.append(0x80 | 126); h += struct.pack(">H", n)
    s.sendall(bytes(h) + m + mk)


def recv(s):
    b0, b1 = s.recv(1)[0], s.recv(1)[0]; n = b1 & 0x7F
    if n == 126: n = struct.unpack(">H", s.recv(2))[0]
    elif n == 127: n = struct.unpack(">Q", s.recv(8))[0]
    data = b""
    while len(data) < n: data += s.recv(n - len(data))
    return data.decode()


_id = 0
def call(s, cmd, timeout=30, **a):
    global _id; _id += 1; mid = str(_id)
    send(s, json.dumps({"id": mid, "cmd": cmd, "args": a}))
    end = time.time() + timeout
    while time.time() < end:
        m = json.loads(recv(s))
        if m.get("event"): continue
        if m.get("id") == mid:
            if not m.get("ok"): raise RuntimeError(f"{cmd}: {m.get('error')}")
            return m.get("result")
    raise RuntimeError("timeout " + cmd)


def dist(a, b): return ((a["x"] - b["x"]) ** 2 + (a["z"] - b["z"]) ** 2) ** 0.5


def main():
    s = hs(os.environ.get("CLEF_WS_HOST", "127.0.0.1"), int(os.environ.get("CLEF_WS_PORT", "8731")))
    print("[baritone] connected")
    token = os.environ.get("CLEF_WS_TOKEN")
    if token:
        assert call(s, "hello", token=token)["authed"]
        print("[baritone] authenticated")
    end = time.time() + 90; st = None
    while time.time() < end:
        st = call(s, "status")
        if st.get("inWorld"): break
        time.sleep(2)
    assert st and st["inWorld"], "never entered world"

    # 1. detected?
    ns = call(s, "nav.status")
    detected = ns.get("available") and ns.get("backend") == "baritone"
    print(f"[baritone] nav.status: {ns}  detected={detected}")

    # 2. higher-level inventory helper (Baritone can't do this)
    call(s, "chat", message="/give @s minecraft:cooked_beef 8")
    time.sleep(1)
    fi = call(s, "findItem", item="cooked_beef")
    find_ok = fi.get("total", 0) > 0
    print(f"[baritone] findItem cooked_beef total={fi.get('total')}  ok={find_ok}")

    # 3. goto via Baritone — should physically move the bot toward the goal
    goto_ok = False
    if detected:
        p0 = call(s, "status")["player"]
        gx, gy, gz = int(p0["x"]) + 18, int(p0["y"]), int(p0["z"])
        print(f"[baritone] goto {gx} {gy} {gz} (from {p0['x']:.1f},{p0['z']:.1f})")
        call(s, "goto", x=gx, y=gy, z=gz)
        moved = 0.0
        for _ in range(12):
            time.sleep(1.5)
            p1 = call(s, "status")["player"]
            moved = dist(p0, p1)
            if moved > 6: break
        goto_ok = moved > 6
        print(f"[baritone] moved {moved:.1f} blocks  ok={goto_ok}")
        call(s, "nav.stop")

        # 4. raw passthrough command (full feature set)
        try:
            r = call(s, "baritone", command=f"goto {gx} {gy} {gz - 12}")
            print(f"[baritone] raw command ok: {r}")
            time.sleep(3)
            call(s, "nav.stop")
            raw_ok = True
        except Exception as e:
            print(f"[baritone] raw command failed: {e}"); raw_ok = False
    else:
        raw_ok = False

    print(f"\nRESULTS: detected={detected} goto_moved={goto_ok} rawCommand={raw_ok} findItem={find_ok}")
    ok = detected and goto_ok and find_ok
    print("[baritone] " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # noqa: BLE001
        print(f"[baritone] ERROR: {e}", file=sys.stderr)
        sys.exit(1)
