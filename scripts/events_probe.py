#!/usr/bin/env python3
"""
Stdlib-only WebSocket event tailer for the MezzoSopranoClef control plane.

Connects to the control-plane WebSocket, subscribes to a set of game events,
and then pretty-prints every frame it receives forever -- the subscribe ack
first, followed by the {"event": ..., "data": ...} broadcast frames as they
arrive -- until you press Ctrl-C.

This reuses the exact handshake + frame send/recv helper style from ws_probe.py
and depends on nothing outside the Python standard library.

Env:
  CLEF_WS_HOST (default 127.0.0.1)
  CLEF_WS_PORT (default 8731)
  CLEF_WS_TOKEN (optional)
"""
import base64
import hashlib
import json
import os
import socket
import struct
import sys

# Game events we ask the server to start streaming to us. The "subscribe"
# command is part of the control-plane protocol.
EVENTS = ["chat", "join", "leave", "health", "death"]


def _handshake(host, port, timeout):
    # Identical to ws_probe.py: open the socket, send the HTTP Upgrade request,
    # then verify the 101 status line and the Sec-WebSocket-Accept key.
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
    # We are tailing events with no idea when the next one arrives, so block
    # indefinitely on recv (Ctrl-C still interrupts it cleanly).
    s.settimeout(None)
    return s


def _send_frame(s, opcode, payload=b""):
    # Client frames MUST be masked (RFC 6455). Same masking + length encoding as
    # ws_probe.py's send_text, just generalised over the opcode so we can also
    # emit pong control frames.
    mask = os.urandom(4)
    masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    header = bytearray([0x80 | opcode])  # FIN bit + opcode
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


def send_text(s, text):
    # 0x1 == text frame
    _send_frame(s, 0x1, text.encode())


def recv_text(s):
    # Read one full frame and return its text payload. Control frames are
    # handled transparently: ping is answered with a pong, pong is ignored, and
    # both then recurse to read the next (data) frame. A close frame raises.
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
    if op == 0x8:  # close
        raise RuntimeError("server sent close")
    if op == 0x9:  # ping -> echo a pong, then keep reading
        _send_frame(s, 0xA, data)
        return recv_text(s)
    if op == 0xA:  # pong -> nothing to do, keep reading
        return recv_text(s)
    return data.decode("utf-8", "replace")


def _dump(raw):
    # Pretty-print a frame as JSON when possible; fall back to the raw text.
    # Status/log lines go to stderr (above), so stdout stays a clean JSON stream.
    try:
        print(json.dumps(json.loads(raw), indent=2))
    except ValueError:
        print(raw)
    sys.stdout.flush()


def main():
    host = os.environ.get("CLEF_WS_HOST", "127.0.0.1")
    port = int(os.environ.get("CLEF_WS_PORT", "8731"))

    s = _handshake(host, port, 10)
    print(f"[events] connected to ws://{host}:{port}", file=sys.stderr)

    token = os.environ.get("CLEF_WS_TOKEN")
    if token:
        send_text(s, json.dumps({"id": "0", "cmd": "hello", "args": {"token": token}}))
        while True:
            raw = recv_text(s)
            _dump(raw)
            try:
                if json.loads(raw).get("id") == "0":
                    break
            except ValueError:
                pass

    # Ask the server to start pushing the events we care about.
    send_text(s, json.dumps({
        "id": "1",
        "cmd": "subscribe",
        "args": {"events": EVENTS},
    }))
    print(f"[events] subscribed to {EVENTS}; tailing (Ctrl-C to quit)...", file=sys.stderr)

    # Loop forever: the first frame is the subscribe response, then every
    # subsequent {"event": ..., "data": ...} broadcast as it comes in.
    while True:
        _dump(recv_text(s))


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n[events] bye", file=sys.stderr)
        sys.exit(0)
    except Exception as e:  # noqa: BLE001
        print(f"[events] error: {e}", file=sys.stderr)
        sys.exit(1)
