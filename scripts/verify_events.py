#!/usr/bin/env python3
"""Live test of the expanded event stream: tick, entitySpawn, screenOpen, damage."""
import base64, json, os, socket, struct, sys, time


def hs(h, p, t=10):
    s = socket.create_connection((h, p), timeout=t)
    k = base64.b64encode(os.urandom(16)).decode()
    s.sendall((f"GET / HTTP/1.1\r\nHost:x\r\nUpgrade:websocket\r\nConnection:Upgrade\r\n"
               f"Sec-WebSocket-Key:{k}\r\nSec-WebSocket-Version:13\r\n\r\n").encode())
    d = b""
    while b"\r\n\r\n" not in d:
        d += s.recv(1024)
    s.settimeout(t)
    return s


def send(s, txt):
    p = txt.encode(); m = os.urandom(4)
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


EVENTS = []
_id = 0
def call(s, cmd, t=30, **a):
    global _id; _id += 1; mid = str(_id)
    send(s, json.dumps({"id": mid, "cmd": cmd, "args": a}))
    end = time.time() + t
    while time.time() < end:
        m = json.loads(recv(s))
        if m.get("event"): EVENTS.append(m); continue
        if m.get("id") == mid:
            if not m.get("ok"): raise RuntimeError(f"{cmd}: {m.get('error')}")
            return m.get("result")
    raise RuntimeError("timeout " + cmd)


def drain(s, seconds):
    end = time.time() + seconds
    s.settimeout(0.5)
    try:
        while time.time() < end:
            try:
                m = json.loads(recv(s))
                if m.get("event"): EVENTS.append(m)
            except socket.timeout:
                pass
    finally:
        s.settimeout(10)


def main():
    s = hs(os.environ.get("CLEF_WS_HOST", "127.0.0.1"), int(os.environ.get("CLEF_WS_PORT", "8731")))
    token = os.environ.get("CLEF_WS_TOKEN")
    if token:
        assert call(s, "hello", token=token)["authed"]
        print("[events] authenticated")
    end = time.time() + 90; st = None
    while time.time() < end:
        st = call(s, "status")
        if st.get("inWorld"): break
        time.sleep(2)
    assert st and st["inWorld"], "never entered world"
    print("[events] in world")

    print("[events] event catalog has", len(call(s, "events")["events"]), "types")
    call(s, "chat", message="/gamemode survival")
    call(s, "subscribe")  # all events
    drain(s, 0.5); EVENTS.clear()

    # tick (throttled state) — just wait
    drain(s, 3.0)
    tick_ok = any(e["event"] == "tick" for e in EVENTS)

    # entitySpawn — summon a cow nearby
    call(s, "chat", message="/summon minecraft:cow ~ ~ ~3")
    drain(s, 2.5)
    spawn_ok = any(e["event"] == "entitySpawn" for e in EVENTS)

    # damage — deal damage to self (op)
    call(s, "chat", message="/damage @s 4")
    drain(s, 1.5)
    dmg_ok = any(e["event"] in ("damage", "health") for e in EVENTS)

    # screenOpen — open a wandering trader's UI
    call(s, "chat", message="/summon minecraft:wandering_trader ~ ~ ~2")
    drain(s, 1.5)
    ents = call(s, "entities", radius=12)
    trader = next((e for e in ents if "trader" in e["type"]), None)
    if trader:
        call(s, "interactEntity", entityId=trader["id"])
        drain(s, 1.5)
    screen_ok = any(e["event"] == "screenOpen" for e in EVENTS)

    kinds = sorted(set(e["event"] for e in EVENTS))
    print(f"[events] captured event types: {kinds}")
    print(f"\nRESULTS: tick={tick_ok} entitySpawn={spawn_ok} screenOpen={screen_ok} damage={dmg_ok}")
    ok = tick_ok and spawn_ok and screen_ok
    print("[events] " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # noqa: BLE001
        print(f"[events] ERROR: {e}", file=sys.stderr)
        sys.exit(1)
