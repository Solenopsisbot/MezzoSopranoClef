#!/usr/bin/env python3
"""
MezzoSopranoClef control-plane client — Python, standard library only (no pip install).

The full machine-readable contract (every command, its args, events, error codes) lives in
``clients/schema.json`` and is returned live by the ``schema`` command. This module is a thin,
typed wrapper over that protocol: it owns the WebSocket handshake, the ``hello`` auth step, request
id correlation, event buffering, and turns ``{"ok":false,"code":...}`` responses into a typed
:class:`ClefError` you can branch on.

    from clef import ClefClient, ClefError

    with ClefClient(token="...") as bot:       # connect + (optional) auth in one go
        print(bot.protocol)                    # negotiated protocol version (int)
        bot.connect_server("play.example.com")
        bot.chat("hello from a corpse")
        try:
            bot.mine(10, 64, -3)
        except ClefError as e:
            if e.code == "NOT_IN_WORLD":
                ...                            # branch on the stable code, not the message

        bot.subscribe("chat", "death")
        for name, data in bot.events():        # blocks, yields pushed events
            print(name, data)

Not thread-safe: use one client per thread (or one connection per worker).
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import socket
import struct
import sys
import time
from collections import deque
from typing import Any, Deque, Dict, Iterator, List, Optional, Tuple

__all__ = ["ClefClient", "ClefError", "SUPPORTED_PROTOCOL"]

# Highest control protocol this client was written against (see welcome.protocol / schema.protocol).
SUPPORTED_PROTOCOL = 1

_WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


class ClefError(RuntimeError):
    """Raised when the bot returns ``{"ok": false}``. ``code`` is a stable :class:`ErrorCode`
    string (e.g. ``"NOT_IN_WORLD"``, ``"BAD_ARGS"``); ``message`` is human-readable detail."""

    def __init__(self, code: str, message: str, cmd: Optional[str] = None):
        super().__init__(f"{cmd + ': ' if cmd else ''}[{code}] {message}")
        self.code = code
        self.message = message
        self.cmd = cmd


class ClefClient:
    def __init__(
        self,
        host: str = "127.0.0.1",
        port: int = 8731,
        token: Optional[str] = None,
        timeout: float = 30.0,
    ) -> None:
        self.host = host
        self.port = port
        self.token = token if token is not None else os.environ.get("CLEF_WS_TOKEN")
        self.timeout = timeout
        self.protocol: Optional[int] = None
        self.requires_auth: bool = False
        self._sock: Optional[socket.socket] = None
        self._events: Deque[Tuple[str, Any]] = deque()
        self._id = 0

    # ---- connection lifecycle -------------------------------------------------------

    def connect(self) -> "ClefClient":
        """Open the WebSocket, read the ``welcome`` event, and authenticate if a token is set."""
        s = socket.create_connection((self.host, self.port), timeout=self.timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        s.sendall(
            (
                "GET / HTTP/1.1\r\n"
                f"Host: {self.host}:{self.port}\r\n"
                "Upgrade: websocket\r\n"
                "Connection: Upgrade\r\n"
                f"Sec-WebSocket-Key: {key}\r\n"
                "Sec-WebSocket-Version: 13\r\n\r\n"
            ).encode()
        )
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = s.recv(1024)
            if not chunk:
                raise ConnectionError("server closed during handshake")
            data += chunk
        status_line = data.split(b"\r\n", 1)[0]
        if b"101" not in status_line:
            raise ConnectionError(f"bad handshake: {status_line!r}")
        expected = base64.b64encode(hashlib.sha1((key + _WS_MAGIC).encode()).digest()).decode()
        if expected.encode() not in data:
            raise ConnectionError("Sec-WebSocket-Accept mismatch")
        s.settimeout(self.timeout)
        self._sock = s

        # The server sends a welcome event first; capture the protocol version.
        name, payload = self._read_frame()
        if name == "welcome":
            self.protocol = payload.get("protocol")
            self.requires_auth = bool(payload.get("requiresAuth"))
            if self.protocol is not None and self.protocol != SUPPORTED_PROTOCOL:
                print(
                    f"[clef] warning: bot protocol {self.protocol} != client {SUPPORTED_PROTOCOL}",
                    file=sys.stderr,
                )
        else:
            self._events.append((name, payload))

        if self.token:
            self.hello(self.token)
        return self

    def connect_retry(
        self,
        attempts: int = 30,
        initial_delay: float = 0.5,
        max_delay: float = 5.0,
    ) -> "ClefClient":
        """Connect with exponential backoff. Useful when launching beside a bot process."""
        last: Optional[BaseException] = None
        delay = initial_delay
        for _ in range(max(1, attempts)):
            try:
                return self.connect()
            except (OSError, ConnectionError, ClefError) as e:
                last = e
                self.close()
                time.sleep(delay)
                delay = min(max_delay, delay * 2)
        raise ConnectionError(f"could not connect after {attempts} attempts: {last}")

    def reconnect(self, **retry_opts: Any) -> "ClefClient":
        """Close the current socket and reconnect, preserving host/port/token settings."""
        self.close()
        self._events.clear()
        return self.connect_retry(**retry_opts)

    def hello(self, token: str) -> Dict[str, Any]:
        """Authenticate this connection."""
        return self.call("hello", token=token)

    def close(self) -> None:
        if self._sock is not None:
            try:
                self._send_frame(0x8)  # close
            except OSError:
                pass
            try:
                self._sock.close()
            finally:
                self._sock = None

    def __enter__(self) -> "ClefClient":
        return self.connect()

    def __exit__(self, *exc: object) -> None:
        self.close()

    # ---- request / response ---------------------------------------------------------

    def call(self, cmd: str, timeout: Optional[float] = None, **args: Any) -> Any:
        """Send ``cmd`` with ``args`` and return its ``result``. Raises :class:`ClefError` on
        ``ok:false``. Event frames that arrive while waiting are buffered for :meth:`events`."""
        if self._sock is None:
            raise ConnectionError("not connected — call connect() first")
        self._id += 1
        mid = str(self._id)
        self._send_text(json.dumps({"id": mid, "cmd": cmd, "args": args}))
        while True:
            name, payload = self._read_frame()
            if name is not None:  # an event arrived first; stash and keep waiting
                self._events.append((name, payload))
                continue
            if payload.get("id") != mid:
                continue
            if not payload.get("ok"):
                raise ClefError(payload.get("code", "COMMAND_FAILED"), payload.get("error", ""), cmd)
            return payload.get("result")

    # ---- events ---------------------------------------------------------------------

    def subscribe(self, *events: str) -> Any:
        """Stream events to this connection. No args = everything."""
        return self.call("subscribe", events=list(events)) if events else self.call("subscribe")

    def unsubscribe(self) -> Any:
        return self.call("unsubscribe")

    def poll_event(self, timeout: Optional[float] = None) -> Optional[Tuple[str, Any]]:
        """Return the next ``(event_name, data)`` or ``None`` if none arrives before ``timeout``."""
        if self._events:
            return self._events.popleft()
        if self._sock is None:
            raise ConnectionError("not connected")
        prev = self._sock.gettimeout()
        if timeout is not None:
            self._sock.settimeout(timeout)
        try:
            name, payload = self._read_frame()
        except socket.timeout:
            return None
        finally:
            self._sock.settimeout(prev)
        if name is not None:
            return (name, payload)
        return self.poll_event(timeout)  # was a stray response; skip it

    def events(self) -> Iterator[Tuple[str, Any]]:
        """Block forever, yielding ``(event_name, data)`` as events are pushed."""
        while True:
            ev = self.poll_event(None)
            if ev is not None:
                yield ev

    # ---- typed convenience wrappers (everything else: use call()) -------------------

    def ping(self) -> Dict[str, Any]:
        return self.call("ping")

    def status(self) -> Dict[str, Any]:
        return self.call("status")

    def schema(self) -> Dict[str, Any]:
        return self.call("schema")

    def connect_server(self, host: str, port: int = 25565) -> Dict[str, Any]:
        return self.call("connect", host=host, port=port)

    def disconnect(self) -> Dict[str, Any]:
        return self.call("disconnect")

    def chat(self, message: str) -> Dict[str, Any]:
        return self.call("chat", message=message)

    def look(self, yaw: Optional[float] = None, pitch: Optional[float] = None) -> Dict[str, Any]:
        args = {k: v for k, v in (("yaw", yaw), ("pitch", pitch)) if v is not None}
        return self.call("look", **args)

    def players(self) -> List[Dict[str, Any]]:
        return self.call("players")

    def goto(self, x: int, z: int, y: Optional[int] = None) -> Dict[str, Any]:
        return self.call("goto", x=x, z=z, **({"y": y} if y is not None else {}))

    def baritone(self, command: str) -> Dict[str, Any]:
        return self.call("baritone", command=command)

    def nav_stop(self) -> Dict[str, Any]:
        return self.call("nav.stop")

    def move(self, **flags: Any) -> Dict[str, Any]:
        """e.g. move(forward=True, sprint=True, durationMs=500)."""
        return self.call("move", **flags)

    def stop_move(self) -> Dict[str, Any]:
        return self.call("stopMove")

    def mine(self, x: int, y: int, z: int, face: Optional[str] = None) -> Dict[str, Any]:
        return self.call("mine", x=x, y=y, z=z, **({"face": face} if face else {}))

    def place(self, x: int, y: int, z: int, face: Optional[str] = None) -> Dict[str, Any]:
        return self.call("place", x=x, y=y, z=z, **({"face": face} if face else {}))

    def break_block(self, x: int, y: int, z: int) -> Dict[str, Any]:
        return self.call("breakBlock", x=x, y=y, z=z)

    def use(self, hand: Optional[str] = None) -> Dict[str, Any]:
        return self.call("use", **({"hand": hand} if hand else {}))

    def attack(self, entity_id: Optional[int] = None) -> Dict[str, Any]:
        return self.call("attack", **({"entityId": entity_id} if entity_id is not None else {}))

    def set_slot(self, slot: int) -> Dict[str, Any]:
        return self.call("setSlot", slot=slot)

    def inventory(self) -> Dict[str, Any]:
        return self.call("inventory")

    def entities(self, radius: Optional[float] = None) -> List[Dict[str, Any]]:
        return self.call("entities", **({"radius": radius} if radius is not None else {}))

    def block_at(self, x: int, y: int, z: int) -> Dict[str, Any]:
        return self.call("blockAt", x=x, y=y, z=z)

    def screenshot(self, timeout: float = 60.0, **opts: Any) -> bytes:
        """Render a PNG and return its raw bytes. opts: x,y,z,yaw,pitch,width,height,fov."""
        res = self.call("screenshot", timeout=timeout, **opts)
        return base64.b64decode(res["base64"])

    # ---- WebSocket frame plumbing (RFC 6455, client frames masked) ------------------

    def _read_frame(self) -> Tuple[Optional[str], Dict[str, Any]]:
        """Read one text frame as JSON. Returns ``(event_name, data)`` for an event, or
        ``(None, response_object)`` for a command response. Handles ping/pong/close transparently."""
        raw = self._recv_text()
        msg = json.loads(raw)
        if isinstance(msg, dict) and "event" in msg:
            return (msg["event"], msg.get("data"))
        return (None, msg)

    def _recv_text(self) -> str:
        assert self._sock is not None
        sock = self._sock

        def readn(n: int) -> bytes:
            buf = b""
            while len(buf) < n:
                c = sock.recv(n - len(buf))
                if not c:
                    raise ConnectionError("connection closed")
                buf += c
            return buf

        while True:
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
                raise ConnectionError("server sent close")
            if op == 0x9:  # ping -> pong, then keep reading
                self._send_frame(0xA, data)
                continue
            if op == 0xA:  # pong
                continue
            return data.decode("utf-8", "replace")

    def _send_text(self, text: str) -> None:
        self._send_frame(0x1, text.encode())

    def _send_frame(self, opcode: int, payload: bytes = b"") -> None:
        if self._sock is None:
            raise ConnectionError("not connected")
        mask = os.urandom(4)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        header = bytearray([0x80 | opcode])
        n = len(payload)
        if n < 126:
            header.append(0x80 | n)
        elif n < 65536:
            header.append(0x80 | 126)
            header += struct.pack(">H", n)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", n)
        self._sock.sendall(bytes(header) + mask + masked)


if __name__ == "__main__":
    # Tiny smoke CLI: python3 clef.py [status|ping|schema]
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    with ClefClient() as _bot:
        print(json.dumps(_bot.call(cmd), indent=2))
