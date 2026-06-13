#!/usr/bin/env python3
"""
Stdlib-only WebSocket probe for the MezzoSopranoClef control plane.

Drives an end-to-end assertion against a running bot: waits for it to join the world,
checks the player list, takes a (GPU-free) screenshot and verifies it's a real PNG.

Env:
  CLEF_WS_HOST (default 127.0.0.1)
  CLEF_WS_PORT (default 8731)
  CLEF_WS_TOKEN (optional)              - control-plane token for default secured configs
  CLEF_BOT_NAME (default ClefBot)   - username we expect to see in the player list
  CLEF_OUT (default .)              - where to save the screenshot
  CLEF_CONNECT_TIMEOUT (default 90) - seconds to wait for the control plane to come up
  CLEF_WORLD_TIMEOUT (default 120)  - seconds to wait for the bot to enter a world

Exit code 0 = all assertions passed.
"""
import base64
import hashlib
import json
import os
import socket
import struct
import sys
import time

PNG_SIG = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])


def _handshake(host, port, timeout):
    s = socket.create_connection((host, port), timeout=timeout)
    key = base64.b64encode(os.urandom(16)).decode()
    req = (
        "GET / HTTP/1.1\r\n"
        f"Host: {host}:{port}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n\r\n"
    )
    s.sendall(req.encode())
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = s.recv(1024)
        if not chunk:
            raise RuntimeError("server closed during handshake")
        data += chunk
    status = data.split(b"\r\n", 1)[0]
    if b"101" not in status:
        raise RuntimeError(f"bad handshake: {status!r}")
    # verify accept key
    expected = base64.b64encode(
        hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()
    ).decode()
    if expected.encode() not in data:
        raise RuntimeError("Sec-WebSocket-Accept mismatch")
    s.settimeout(timeout)
    return s


def connect(host, port, total_timeout):
    """Retry the handshake until the control plane is up."""
    deadline = time.time() + total_timeout
    last = None
    while time.time() < deadline:
        try:
            return _handshake(host, port, 10)
        except OSError as e:
            last = e
            time.sleep(2)
    raise RuntimeError(f"could not connect to ws://{host}:{port}: {last}")


def send_text(s, text):
    payload = text.encode()
    mask = os.urandom(4)
    masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    header = bytearray([0x81])
    n = len(payload)
    if n < 126:
        header.append(0x80 | n)
    elif n < 65536:
        header.append(0x80 | 126)
        header += struct.pack(">H", n)
    else:
        header.append(0x80 | 127)
        header += struct.pack(">Q", n)
    s.sendall(bytes(header) + mask + masked)


def recv_text(s):
    def readn(n):
        buf = b""
        while len(buf) < n:
            c = s.recv(n - len(buf))
            if not c:
                raise RuntimeError("connection closed")
            buf += c
        return buf

    b0, b1 = readn(2)
    op = b0 & 0x0F
    masked = b1 & 0x80
    n = b1 & 0x7F
    if n == 126:
        n = struct.unpack(">H", readn(2))[0]
    elif n == 127:
        n = struct.unpack(">Q", readn(8))[0]
    mask = readn(4) if masked else None
    data = readn(n) if n else b""
    if mask:
        data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
    if op == 0x8:
        raise RuntimeError("server sent close")
    if op == 0x9:  # ping -> just read the next frame
        return recv_text(s)
    return data.decode("utf-8", "replace")


_id = 0


def call(s, cmd, timeout=30, **args):
    global _id
    _id += 1
    mid = str(_id)
    send_text(s, json.dumps({"id": mid, "cmd": cmd, "args": args}))
    deadline = time.time() + timeout
    while time.time() < deadline:
        msg = json.loads(recv_text(s))
        if msg.get("event"):
            print(f"[probe] event: {msg['event']} {msg.get('data')}")
            continue
        if msg.get("id") == mid:
            if not msg.get("ok"):
                raise RuntimeError(f"{cmd} failed: {msg.get('error')}")
            return msg.get("result")
    raise RuntimeError(f"timeout waiting for response to '{cmd}'")


def main():
    host = os.environ.get("CLEF_WS_HOST", "127.0.0.1")
    port = int(os.environ.get("CLEF_WS_PORT", "8731"))
    name = os.environ.get("CLEF_BOT_NAME", "ClefBot")
    outdir = os.environ.get("CLEF_OUT", ".")
    connect_timeout = int(os.environ.get("CLEF_CONNECT_TIMEOUT", "90"))
    world_timeout = int(os.environ.get("CLEF_WORLD_TIMEOUT", "120"))

    s = connect(host, port, connect_timeout)
    print(f"[probe] connected to ws://{host}:{port}")

    token = os.environ.get("CLEF_WS_TOKEN")
    if token:
        assert call(s, "hello", token=token).get("authed") is True
        print("[probe] authenticated")

    assert call(s, "ping").get("pong") is True
    print("[probe] ping OK")

    deadline = time.time() + world_timeout
    st = None
    while time.time() < deadline:
        st = call(s, "status")
        if st.get("inWorld"):
            break
        time.sleep(2)
    assert st and st.get("inWorld"), f"bot never entered a world: {st}"
    print(f"[probe] in world at {st['player']} (dim={st['player']['dimension']})")

    names = [p["name"] for p in call(s, "players")]
    print(f"[probe] tab-list: {names}")
    assert name in names, f"expected '{name}' in player list {names}"

    shot = call(s, "screenshot", timeout=60, width=320, height=180)
    raw = base64.b64decode(shot["base64"])
    assert raw[:8] == PNG_SIG, "screenshot is not a valid PNG"
    path = os.path.join(outdir, "e2e-shot.png")
    with open(path, "wb") as f:
        f.write(raw)
    print(f"[probe] screenshot OK backend={shot.get('backend')} bytes={len(raw)} -> {path}")

    call(s, "chat", message="MezzoSopranoClef e2e: PASS")
    print("[probe] PASS")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # noqa: BLE001
        print(f"[probe] FAIL: {e}", file=sys.stderr)
        sys.exit(1)
