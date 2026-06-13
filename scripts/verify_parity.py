#!/usr/bin/env python3
"""
Live verification of the player-parity features added for UI/inventory/entities/respawn.
The bot is opped on the test server so it can /give, /summon and /kill to set up scenarios.

Asserts: container reading (items show up), villager/merchant UI (interactEntity opens trades),
and death + auto-respawn. Exit 0 on success.
"""
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
def call(s, cmd, timeout=30, **args):
    global _id; _id += 1; mid = str(_id)
    send(s, json.dumps({"id": mid, "cmd": cmd, "args": args}))
    end = time.time() + timeout
    while time.time() < end:
        m = json.loads(recv(s))
        if m.get("event"): continue
        if m.get("id") == mid:
            if not m.get("ok"): raise RuntimeError(f"{cmd}: {m.get('error')}")
            return m.get("result")
    raise RuntimeError("timeout " + cmd)


def main():
    host = os.environ.get("CLEF_WS_HOST", "127.0.0.1"); port = int(os.environ.get("CLEF_WS_PORT", "8731"))
    s = hs(host, port)
    print("[parity] connected")
    token = os.environ.get("CLEF_WS_TOKEN")
    if token:
        assert call(s, "hello", token=token)["authed"]
        print("[parity] authenticated")
    end = time.time() + 90; st = None
    while time.time() < end:
        st = call(s, "status")
        if st.get("inWorld"): break
        time.sleep(2)
    assert st and st["inWorld"], "never entered world"
    print("[parity] in world (op via ops.json)")

    # setup via op commands
    call(s, "chat", message="/gamemode survival")
    call(s, "chat", message="/give @s minecraft:cooked_beef 16")
    call(s, "chat", message="/give @s minecraft:chest 1")
    time.sleep(1.2)

    # CONTAINER: the player inventory is always a ScreenHandler — items should appear
    cont = call(s, "container")
    items = [sl["item"] for sl in cont["slots"]]
    container_ok = any("cooked_beef" in i for i in items)
    print(f"[parity] container handler={cont['handler']} slots={len(cont['slots'])} cooked_beef? {container_ok}")

    # VILLAGER / MERCHANT UI: summon a wandering trader, interact, read trades
    call(s, "chat", message="/summon minecraft:wandering_trader ~ ~ ~2")
    time.sleep(1.5)
    ents = call(s, "entities", radius=12)
    trader = next((e for e in ents if "trader" in e["type"] or "villager" in e["type"]), None)
    trade_ok = False
    if trader:
        print(f"[parity] trader id={trader['id']} dist={trader['distance']:.1f}")
        call(s, "look", yaw=0, pitch=0)
        call(s, "interactEntity", entityId=trader["id"])
        time.sleep(1.5)
        c2 = call(s, "container")
        trade_ok = ("Merchant" in c2.get("handler", "")) or len(c2.get("trades", [])) > 0
        print(f"[parity] after interact: handler={c2.get('handler')} trades={len(c2.get('trades', []))}")
        call(s, "closeScreen")
    else:
        print("[parity] no trader found (summon may have failed)")

    # RESPAWN: kill the bot, expect auto-respawn back to alive
    call(s, "chat", message="/kill @s")
    time.sleep(2.5)
    st2 = call(s, "status")
    respawn_ok = st2.get("inWorld") and st2.get("player", {}).get("health", 0) > 0
    print(f"[parity] after /kill: inWorld={st2.get('inWorld')} health={st2.get('player', {}).get('health')}")

    # serverui smoke (structure returns)
    ui = call(s, "serverui")
    print(f"[parity] serverui keys: {list(ui.keys())}")

    print(f"\nRESULTS: container={container_ok} villagerUI={trade_ok} respawn={respawn_ok}")
    ok = container_ok and respawn_ok and trade_ok
    print("[parity] " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # noqa: BLE001
        print(f"[parity] ERROR: {e}", file=sys.stderr)
        sys.exit(1)
