#!/usr/bin/env python3
"""
Live verification of the actuation + event-stream features against a running bot.
Asserts: in-world, movement actually moves the player, chat produces a 'chat' event,
inventory/entities/screenshot respond. Exit 0 on success.
"""
import base64
import hashlib
import json
import math
import os
import socket
import struct
import sys
import time

PNG_SIG = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])


def handshake(host, port, timeout):
    s = socket.create_connection((host, port), timeout=timeout)
    key = base64.b64encode(os.urandom(16)).decode()
    s.sendall((
        "GET / HTTP/1.1\r\n" f"Host: {host}:{port}\r\n"
        "Upgrade: websocket\r\nConnection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
    ).encode())
    data = b""
    while b"\r\n\r\n" not in data:
        c = s.recv(1024)
        if not c:
            raise RuntimeError("closed during handshake")
        data += c
    if b"101" not in data.split(b"\r\n", 1)[0]:
        raise RuntimeError("bad handshake")
    s.settimeout(timeout)
    return s


def connect(host, port, total):
    end = time.time() + total
    last = None
    while time.time() < end:
        try:
            return handshake(host, port, 10)
        except OSError as e:
            last = e
            time.sleep(2)
    raise RuntimeError(f"cannot connect: {last}")


def send(s, text):
    p = text.encode()
    m = os.urandom(4)
    masked = bytes(b ^ m[i % 4] for i, b in enumerate(p))
    h = bytearray([0x81])
    n = len(p)
    if n < 126:
        h.append(0x80 | n)
    elif n < 65536:
        h.append(0x80 | 126); h += struct.pack(">H", n)
    else:
        h.append(0x80 | 127); h += struct.pack(">Q", n)
    s.sendall(bytes(h) + m + masked)


def recv(s):
    def rd(n):
        b = b""
        while len(b) < n:
            c = s.recv(n - len(b))
            if not c:
                raise RuntimeError("closed")
            b += c
        return b
    b0, b1 = rd(2)
    op = b0 & 0x0F
    n = b1 & 0x7F
    if n == 126:
        n = struct.unpack(">H", rd(2))[0]
    elif n == 127:
        n = struct.unpack(">Q", rd(8))[0]
    data = rd(n) if n else b""
    if op == 0x9:
        return recv(s)
    return data.decode("utf-8", "replace")


_id = 0
_events = []


def call(s, cmd, timeout=30, **args):
    global _id
    _id += 1
    mid = str(_id)
    send(s, json.dumps({"id": mid, "cmd": cmd, "args": args}))
    end = time.time() + timeout
    while time.time() < end:
        msg = json.loads(recv(s))
        if msg.get("event"):
            _events.append(msg)
            continue
        if msg.get("id") == mid:
            if not msg.get("ok"):
                raise RuntimeError(f"{cmd}: {msg.get('error')}")
            return msg.get("result")
    raise RuntimeError(f"timeout: {cmd}")


def drain(s, seconds):
    end = time.time() + seconds
    s.settimeout(0.5)
    try:
        while time.time() < end:
            try:
                msg = json.loads(recv(s))
                if msg.get("event"):
                    _events.append(msg)
            except socket.timeout:
                pass
    finally:
        s.settimeout(10)


def main():
    host = os.environ.get("CLEF_WS_HOST", "127.0.0.1")
    port = int(os.environ.get("CLEF_WS_PORT", "8731"))
    s = connect(host, port, int(os.environ.get("CLEF_CONNECT_TIMEOUT", "60")))
    print("[verify] connected")
    token = os.environ.get("CLEF_WS_TOKEN")
    if token:
        assert call(s, "hello", token=token)["authed"]
        print("[verify] authenticated")
    assert call(s, "ping")["pong"]

    # wait for world
    end = time.time() + int(os.environ.get("CLEF_WORLD_TIMEOUT", "120"))
    st = None
    while time.time() < end:
        st = call(s, "status")
        if st.get("inWorld"):
            break
        time.sleep(2)
    assert st and st.get("inWorld"), "never entered world"
    p0 = st["player"]
    print(f"[verify] in world at ({p0['x']:.2f},{p0['y']:.2f},{p0['z']:.2f})")

    print("[verify] subscribe:", call(s, "subscribe", events=["chat", "health", "join", "leave", "death"]))

    # MOVEMENT: walk forward ~1.5s, expect horizontal displacement
    print("[verify] move forward:", call(s, "move", forward=True, durationMs=1500))
    drain(s, 2.5)
    p1 = call(s, "status")["player"]
    dx, dz = p1["x"] - p0["x"], p1["z"] - p0["z"]
    dist = math.hypot(dx, dz)
    print(f"[verify] moved {dist:.2f} blocks -> ({p1['x']:.2f},{p1['z']:.2f})")
    move_ok = dist > 0.5

    # EVENTS: send chat, expect a 'chat' event echoed back
    tag = "clef-verify-xyzzy"
    call(s, "chat", message=tag)
    drain(s, 2.0)
    chat_ok = any(e.get("event") == "chat" and tag in json.dumps(e.get("data", {})) for e in _events)
    health_ok = any(e.get("event") == "health" for e in _events)

    # other commands smoke
    inv = call(s, "inventory")
    ents = call(s, "entities", radius=16)
    shot = call(s, "screenshot", timeout=60, width=160, height=90)
    png_ok = base64.b64decode(shot["base64"])[:8] == PNG_SIG

    print(f"[verify] inventory slots={len(inv.get('items', []))} selected={inv.get('selectedSlot')}")
    print(f"[verify] entities nearby={len(ents)}")
    print(f"[verify] screenshot backend={shot.get('backend')} png={png_ok}")
    print(f"[verify] events captured: {[e['event'] for e in _events]}")

    # HOTBAR: select slot 3, confirm via inventory
    call(s, "setSlot", slot=3)
    slot_ok = call(s, "inventory").get("selectedSlot") == 3
    print(f"[verify] setSlot -> selectedSlot=3? {slot_ok}")

    # BREAK: instantly break the block under the bot's feet, confirm it becomes air
    pp = call(s, "status")["player"]
    bx, by, bz = math.floor(pp["x"]), math.floor(pp["y"]) - 1, math.floor(pp["z"])
    before = call(s, "blockAt", x=bx, y=by, z=bz)
    call(s, "breakBlock", x=bx, y=by, z=bz)
    time.sleep(0.6)
    after = call(s, "blockAt", x=bx, y=by, z=bz)
    break_ok = (not before["air"]) and after["air"]
    print(f"[verify] breakBlock {before['block']}@({bx},{by},{bz}) -> air? {after['air']} (ok={break_ok})")

    # MINE: dig an adjacent block over ticks, confirm it becomes air
    mx, mz = bx + 1, bz
    mbefore = call(s, "blockAt", x=mx, y=by, z=mz)
    mine_ok = False
    if not mbefore["air"]:
        call(s, "mine", x=mx, y=by, z=mz, face="up")
        time.sleep(1.5)
        mine_ok = call(s, "blockAt", x=mx, y=by, z=mz)["air"]
        call(s, "stopMine")
    print(f"[verify] mine ({mx},{by},{mz}) -> air? {mine_ok}")

    print(f"\nRESULTS: move={move_ok} chat_event={chat_ok} png={png_ok} "
          f"setSlot={slot_ok} break={break_ok} mine={mine_ok} (health_event={health_ok})")
    ok = move_ok and chat_ok and png_ok and slot_ok and break_ok
    print("[verify] " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # noqa: BLE001
        print(f"[verify] ERROR: {e}", file=sys.stderr)
        sys.exit(1)
