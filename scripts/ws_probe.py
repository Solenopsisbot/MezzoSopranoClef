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

# Every {"event": ...} frame we see, in arrival order. `call` drains frames while waiting for its
# own response, so events would otherwise be lost before anything could assert on them.
EVENTS_SEEN = []


def call(s, cmd, timeout=30, **args):
    global _id
    _id += 1
    mid = str(_id)
    send_text(s, json.dumps({"id": mid, "cmd": cmd, "args": args}))
    deadline = time.time() + timeout
    # The socket still carries the handshake's short timeout, so without this a command that takes
    # longer than that fails with a bare "timed out" no matter what its own timeout says — which is
    # exactly what a loaded machine or a slow first screenshot hits.
    s.settimeout(timeout)
    while time.time() < deadline:
        msg = json.loads(recv_text(s))
        if msg.get("event"):
            print(f"[probe] event: {msg['event']} {msg.get('data')}")
            EVENTS_SEEN.append(msg)
            continue
        if msg.get("id") == mid:
            if not msg.get("ok"):
                raise RuntimeError(f"{cmd} failed: {msg.get('error')}")
            return msg.get("result")
    raise RuntimeError(f"timeout waiting for response to '{cmd}'")


def version_key(v):
    """Sortable tuple for a release id, so '1.20.1' < '1.21.8' < '26.2'."""
    import re as _re
    return tuple(int(x) for x in _re.findall(r"\d+", v or "0")) or (0,)


def wait_for_event(s, name, predicate, timeout=30):
    """Return the first buffered-or-incoming `name` event matching `predicate`, else raise."""
    for msg in EVENTS_SEEN:
        if msg.get("event") == name and predicate(msg.get("data") or {}):
            return msg["data"]
    deadline = time.time() + timeout
    previous = s.gettimeout()
    try:
        while time.time() < deadline:
            s.settimeout(max(1, deadline - time.time()))
            try:
                msg = json.loads(recv_text(s))
            except (socket.timeout, OSError):
                break
            if msg.get("event"):
                print(f"[probe] event: {msg['event']} {msg.get('data')}")
                EVENTS_SEEN.append(msg)
                if msg["event"] == name and predicate(msg.get("data") or {}):
                    return msg["data"]
    finally:
        # Restore the socket's original timeout. Leaving the short per-wait deadline in place made
        # the NEXT request fail with a bare "timed out" the moment a command took longer than the
        # leftover slice — which is what broke 26.2.
        s.settimeout(previous)
    raise RuntimeError(f"no '{name}' event matching predicate within {timeout}s")


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

    # The player-info packet lands a little after the world does, so poll rather than sample once:
    # a bare assert here fails intermittently on a slow join even though the bot is fine.
    deadline = time.time() + 60
    names = []
    while time.time() < deadline:
        names = [p["name"] for p in call(s, "players")]
        if name in names:
            break
        time.sleep(2)
    print(f"[probe] tab-list: {names}")
    assert name in names, f"expected '{name}' in player list {names}"

    shot = call(s, "screenshot", timeout=60, width=320, height=180)
    raw = base64.b64decode(shot["base64"])
    assert raw[:8] == PNG_SIG, "screenshot is not a valid PNG"
    path = os.path.join(outdir, "e2e-shot.png")
    with open(path, "wb") as f:
        f.write(raw)
    print(f"[probe] screenshot OK backend={shot.get('backend')} bytes={len(raw)} -> {path}")

    # Chat round-trip. Sending proves the outbound path; the server echoes the message back to
    # us, so requiring the inbound event proves the receive path too — which on 1.19.2 and older
    # is a packet mixin rather than a Fabric API event, and is otherwise untested.
    marker = "MezzoSopranoClef e2e: PASS"
    assert call(s, "subscribe", events=["chat", "blockUpdate"]) is not None
    call(s, "chat", message=marker)
    got = wait_for_event(s, "chat", lambda d: marker in (d.get("text") or ""), timeout=30)
    assert got.get("kind") == "chat", f"echoed message had kind={got.get('kind')!r}, want 'chat'"
    print(f"[probe] chat round-trip OK (sender={got.get('sender')!r})")

    # Packet-derived events (blockUpdate/itemPickup/entityHurt/explosion) come from a mixin on
    # ClientPacketListener. If that mixin is not listed in the module's mixin config it is simply
    # absent — `defaultRequire` never trips — and `subscribe` still answers {subscribed:[...]}
    # while nothing is ever delivered. So provoke one and require it, rather than trusting the
    # subscribe ack. The mixin only exists from 1.20.1 up; older targets skip with a note.
    native = (st.get("protocol") or {}).get("native") or ""
    if version_key(native) >= version_key("1.20.1"):
        # Read the position fresh, and only once the bot has stopped falling. The world check can
        # pass while it is still descending to the flat-world floor, and a stale position puts the
        # target block a hundred blocks away — outside events.blockUpdateRadius, so the client
        # correctly suppresses the event and the assertion wrongly blames the mixin.
        settled = time.time() + 30
        pos = st["player"]
        while time.time() < settled:
            pos = (call(s, "status").get("player") or pos)
            if pos.get("onGround"):
                break
            time.sleep(1)
        bx, by, bz = int(pos["x"]) + 2, int(pos["y"]) - 1, int(pos["z"])
        # Retry with a different block each time. A client starved of CPU (a full matrix run has a
        # server, a game and Gradle competing) can be slow enough to miss a 30s window, and reusing
        # the same block would make the retry a no-op the server never broadcasts.
        seen = None
        for block in ("minecraft:stone", "minecraft:dirt", "minecraft:cobblestone"):
            call(s, "chat", message=f"/setblock {bx} {by} {bz} {block}")
            try:
                seen = wait_for_event(s, "blockUpdate", lambda d: True, timeout=20)
                break
            except RuntimeError:
                continue
        assert seen is not None, (
            f"no blockUpdate event after three setblock attempts — the packet mixin is not wired "
            f"on {native} (subscribe succeeds either way, so this is the only thing that proves it)")
        print(f"[probe] blockUpdate event OK (packet mixin is wired on {native})")
    else:
        print(f"[probe] blockUpdate check skipped: no packet mixin on {native} (needs 1.20.1+)")

    print("[probe] PASS")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # noqa: BLE001
        print(f"[probe] FAIL: {e}", file=sys.stderr)
        sys.exit(1)
