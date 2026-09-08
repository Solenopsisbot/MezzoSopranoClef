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

__all__ = ["ClefClient", "ClefError", "SUPPORTED_PROTOCOL", "decode_blocks_in"]

# Highest control protocol this client was written against (see welcome.protocol / schema.protocol).
SUPPORTED_PROTOCOL = 2

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
        # Bytes read past the end of the HTTP handshake. The server sends `welcome` the instant it
        # upgrades, so on a local socket the response headers and the first frame routinely arrive
        # in the same read — and anything not kept here is a frame silently thrown away.
        self._rx = b""

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
        self._rx = data.split(b"\r\n\r\n", 1)[1]

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
                self._rx = b""

    def __enter__(self) -> "ClefClient":
        return self.connect()

    def __exit__(self, *exc: object) -> None:
        self.close()

    # ---- request / response ---------------------------------------------------------

    def call(self, cmd: str, timeout: Optional[float] = None, **args: Any) -> Any:
        """Send ``cmd`` with ``args`` and return its ``result``. Raises :class:`ClefError` on
        ``ok:false``. Event frames that arrive while waiting are buffered for :meth:`events`.

        ``timeout`` raises the socket read timeout for this call only, which the commands that
        deliberately take a while (``screenshot``, ``mine wait``, ``shootAt``, ``meleeWhile``) need:
        the connection default is 30 s and a volley of arrows is longer than that. It bounds each
        read, not the whole call, same as :meth:`poll_event`."""
        if self._sock is None:
            raise ConnectionError("not connected — call connect() first")
        self._id += 1
        mid = str(self._id)
        self._send_text(json.dumps({"id": mid, "cmd": cmd, "args": args}))
        previous = self._sock.gettimeout()
        if timeout is not None:
            self._sock.settimeout(timeout)
        try:
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
        finally:
            if timeout is not None and self._sock is not None:
                self._sock.settimeout(previous)

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

    def connect_server(self, host: str, port: int = 25565, version: Optional[str] = None) -> Dict[str, Any]:
        """Join a server. `version` picks the protocol ViaFabricPlus speaks: "auto" (ping and match,
        the server default), "native", or a release such as "1.12.2"; None uses the bot's config."""
        args: Dict[str, Any] = {"host": host, "port": port}
        if version is not None:
            args["version"] = version
        return self.call("connect", **args)

    def protocol_info(self) -> Dict[str, Any]:
        """ViaFabricPlus state: native version, current target, and every joinable server release.

        Named ``protocol_info`` rather than ``protocol`` because ``self.protocol`` already holds the
        control-plane protocol version from the welcome frame; a method of the same name would be
        shadowed by that attribute and raise ``TypeError: 'int' object is not callable``.
        """
        return self.call("protocol")

    def disconnect(self) -> Dict[str, Any]:
        return self.call("disconnect")

    def chat(self, message: str) -> Dict[str, Any]:
        return self.call("chat", message=message)

    def look(self, yaw: Optional[float] = None, pitch: Optional[float] = None) -> Dict[str, Any]:
        args = {k: v for k, v in (("yaw", yaw), ("pitch", pitch)) if v is not None}
        return self.call("look", **args)

    def players(self) -> List[Dict[str, Any]]:
        return self.call("players")

    def goto(self, x: int, z: int, y: Optional[int] = None, reach: int = 1) -> Dict[str, Any]:
        """Start pathing. Returns as soon as Baritone accepts the goal — completion arrives as a
        ``nav.done`` or ``nav.failed`` event, so subscribe to those instead of polling."""
        args: Dict[str, Any] = {"x": x, "z": z, "reach": reach}
        if y is not None:
            args["y"] = y
        return self.call("goto", **args)

    def baritone(self, command: str, collect_ms: int = 250) -> Dict[str, Any]:
        """Run any Baritone command and return ``{"ran", "backend", "output": [lines]}``.

        ``output`` is what Baritone printed within ``collect_ms`` — the only way to read the result
        of ``find``, ``eta`` or "No known locations of ...", which otherwise go to a chat HUD a
        headless bot doesn't have. Commands naming a block are safe; the bot refuses them outright
        if it couldn't pre-initialise Baritone's block-argument support off the client thread."""
        return self.call("baritone", command=command, collectMs=collect_ms)

    def nav_stop(self) -> Dict[str, Any]:
        return self.call("nav.stop")

    def move(self, **flags: Any) -> Dict[str, Any]:
        """e.g. move(forward=True, sprint=True, durationMs=500)."""
        return self.call("move", **flags)

    def stop_move(self) -> Dict[str, Any]:
        return self.call("stopMove")

    def mine(self, x: int, y: int, z: int, face: Optional[str] = None,
             wait: bool = False) -> Dict[str, Any]:
        """Break a block. With ``wait`` the call blocks until it is broken or refused and returns
        ``{"broken": bool, "reason": ...}``; otherwise subscribe to the ``mineDone`` event."""
        args: Dict[str, Any] = {"x": x, "y": y, "z": z, "wait": wait}
        if face:
            args["face"] = face
        return self.call("mine", **args)

    def place(self, x: int, y: int, z: int, face: Optional[str] = None,
              item: Optional[str] = None, confirm: bool = True) -> Dict[str, Any]:
        """Place against a block face. ``item`` selects it first (swapping it onto the hotbar if
        needed); ``placed`` is verified against the world, not assumed from the click."""
        args: Dict[str, Any] = {"x": x, "y": y, "z": z, "confirm": confirm}
        if face:
            args["face"] = face
        if item:
            args["item"] = item
        return self.call("place", **args)

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

    def entities(self, radius: Optional[float] = None,
                 kinds: Optional[List[str]] = None) -> List[Dict[str, Any]]:
        """Nearby entities with health, hostility, held item and trade data. ``kinds`` filters by
        entity type id (bare names allowed), e.g. ``kinds=["zombie", "skeleton"]``."""
        args: Dict[str, Any] = {}
        if radius is not None:
            args["radius"] = radius
        if kinds is not None:
            args["kinds"] = kinds
        return self.call("entities", **args)

    def block_at(self, x: int, y: int, z: int) -> Dict[str, Any]:
        return self.call("blockAt", x=x, y=y, z=z)

    # ---- combat loops that run on the bot --------------------------------------------

    def shoot_at(self, entity_id: int, shots: int = 1, lead: bool = True, charge: int = 25,
                 max_range: float = 64.0, wait: bool = True,
                 timeout: Optional[float] = None) -> Dict[str, Any]:
        """Loose arrows at an entity, with the tracking loop running on the bot at 20 Hz.

        Blocks until the last arrow has landed and returns
        ``{fired, hits, damage, killed, stopped, ...}``. Aiming from out here instead costs about
        1.5 s a shot, which is enough for anything mobile to have left. Pass ``wait=False`` to get
        control straight back and take the outcome from the ``combatDone`` event, or use a second
        connection so ``combat_stop`` can interrupt it."""
        if timeout is None:
            timeout = (shots * (charge + 25) + 80) * 0.05 + 10
        return self.call("shootAt", timeout=timeout, entityId=entity_id, shots=shots, lead=lead,
                         charge=charge, maxRange=max_range, wait=wait)

    def melee_while(self, entity_id: int, max_ms: int = 5000, reach: float = 3.5,
                    stop_below_health: Optional[float] = None, wait: bool = True,
                    timeout: Optional[float] = None) -> Dict[str, Any]:
        """Swing at an entity on the attack-cooldown cadence while it stays in reach.

        Resolves the damageable part of a multi-part entity, which is the only way to hurt an ender
        dragon — the parent entity ignores damage entirely."""
        args: Dict[str, Any] = {"entityId": entity_id, "maxMs": max_ms, "reach": reach, "wait": wait}
        if stop_below_health is not None:
            args["stopBelowHealth"] = stop_below_health
        return self.call("meleeWhile", timeout=timeout if timeout is not None else max_ms / 1000 + 10,
                         **args)

    def combat_stop(self) -> Dict[str, Any]:
        """Take the body back from a running ``shoot_at``/``melee_while``."""
        return self.call("combat.stop")

    def combat_status(self) -> Dict[str, Any]:
        return self.call("combat.status")

    def equip(self, item: str) -> Dict[str, Any]:
        """Wear an item. Already in its equipment slot is a no-op (``changed: False``) —
        re-equipping worn armour does not take it off.

        ``changed`` is read back from the equipment slot rather than assumed from the click, so
        it is ``False`` both when the item was already worn (``worn: True``) and when the
        shift-click could not equip it at all — a sword, or a full armour slot (``worn: False``,
        with ``detail`` saying which). ``moved`` reports whether the stack went anywhere."""
        return self.call("equip", item=item)

    def screenshot(self, timeout: float = 60.0, **opts: Any) -> bytes:
        """Render a PNG and return its raw bytes.

        opts: x,y,z,yaw,pitch,width,height,fov for the free camera; mode="topdown" with
        centerX/centerZ/radius for an orthographic map; annotate=True to also get boxes
        (use :meth:`screenshot_annotated` if you want them)."""
        res = self.call("screenshot", timeout=timeout, **opts)
        return base64.b64decode(res["base64"])

    def screenshot_annotated(self, timeout: float = 60.0, **opts: Any) -> Tuple[bytes, Dict[str, Any]]:
        """Render a PNG and also return ``{"camera": ..., "entities": [...]}`` — the camera
        parameters and the screen-space box of every visible entity, for drawing labels."""
        res = self.call("screenshot", timeout=timeout, annotate=True, **opts)
        meta = {"camera": res.get("camera"), "entities": res.get("entities", [])}
        return base64.b64decode(res["base64"]), meta

    def map(self, radius: int = 32, timeout: float = 60.0, **opts: Any) -> bytes:
        """Orthographic top-down map PNG, east-right and north-up, centred on the bot."""
        return self.screenshot(timeout=timeout, mode="topdown", radius=radius, **opts)

    # ---- world queries -------------------------------------------------------------

    def find_blocks(
        self,
        ids: List[str],
        radius: int = 32,
        max: int = 32,
        sort: str = "nearest",
    ) -> List[Dict[str, Any]]:
        """Nearest matching blocks in the loaded chunks. ``ids`` accepts block ids and
        ``#tag`` names, e.g. ``["#minecraft:logs"]``."""
        return self.call("findBlocks", ids=ids, radius=radius, max=max, sort=sort)

    def blocks_in(self, min_xyz: Tuple[int, int, int], max_xyz: Tuple[int, int, int],
                  palette: bool = True) -> Dict[str, Any]:
        """Dense readout of a block cuboid. With ``palette`` (the default) the result carries a
        palette plus base64 varint indices; see :func:`decode_blocks_in`."""
        return self.call(
            "blocksIn",
            minX=min_xyz[0], minY=min_xyz[1], minZ=min_xyz[2],
            maxX=max_xyz[0], maxY=max_xyz[1], maxZ=max_xyz[2],
            palette=palette,
        )

    def target(self, max_distance: float = 4.5, fluids: bool = False) -> Dict[str, Any]:
        return self.call("target", maxDistance=max_distance, fluids=fluids)

    def registry(self, *kinds: str, tags: bool = False) -> Dict[str, Any]:
        args: Dict[str, Any] = {"tags": tags}
        if kinds:
            args["kinds"] = list(kinds)
        return self.call("registry", **args)

    # ---- navigation ----------------------------------------------------------------

    def nav_check(self, x: int, y: int, z: int, reach: int = 1,
                  max_nodes: Optional[int] = None) -> Dict[str, Any]:
        """Cheap walkability **estimate** — a walk-only A* over loaded chunks, not Baritone's
        planner. Nothing moves. Branch on ``verdict`` (``reachable`` / ``unreachable`` /
        ``unknown``): ``unknown`` means the search hit its node budget, which is not the same as
        proving there is no route."""
        args: Dict[str, Any] = {"x": x, "y": y, "z": z, "reach": reach}
        if max_nodes is not None:
            args["maxNodes"] = max_nodes
        return self.call("nav.check", **args)

    def look_at(self, x: Optional[float] = None, y: Optional[float] = None,
                z: Optional[float] = None, entity_id: Optional[int] = None) -> Dict[str, Any]:
        if entity_id is not None:
            return self.call("lookAt", entityId=entity_id)
        return self.call("lookAt", x=x, y=y, z=z)

    # ---- crafting ------------------------------------------------------------------

    def craft(self, item: str, count: int = 1, all: bool = False) -> Dict[str, Any]:
        """Craft using the open crafting screen, else the 2x2 player grid. Blocks until done."""
        return self.call("craft", item=item, count=count, all=all)

    def recipes(self, item: str) -> List[Dict[str, Any]]:
        return self.call("recipes", item=item)

    def craftable(self) -> Dict[str, Any]:
        return self.call("craftable")

    def move_to_hotbar(self, item: str, slot: Optional[int] = None) -> Dict[str, Any]:
        return self.call("moveToHotbar", item=item, **({"slot": slot} if slot is not None else {}))

    # ---- chat ----------------------------------------------------------------------

    def chat_history(self, limit: int = 50) -> Dict[str, Any]:
        return self.call("chatHistory", limit=limit)

    def whisper(self, player: str, text: str) -> Dict[str, Any]:
        """Private-message a player using whichever of msg/tell/w/whisper this server has."""
        return self.call("whisper", player=player, text=text)

    # ---- replay recording (ReplayMod .mcpr) -----------------------------------------

    def replay_record(self, enabled: bool = True) -> Dict[str, Any]:
        """Arm or disarm packet capture.

        This takes effect on the **next** connection, not this one: a replay has to begin at a
        connection's login or it cannot be played back at all. Arm it, then ``connect``.
        """
        return self.call("replay.record", enabled=enabled)

    def replay_save(self, name: Optional[str] = None) -> Dict[str, Any]:
        """Seal everything recorded so far into a finished ``.mcpr`` and keep recording.

        Safe to call repeatedly mid-run — each call produces a complete replay ending at that
        moment, which is how you watch a bot that has not stopped playing.
        """
        return self.call("replay.save", **({"name": name} if name else {}))

    def replay_marker(self, name: Optional[str] = None) -> Dict[str, Any]:
        """Drop a named pip on the replay timeline at the bot's current position."""
        return self.call("replay.marker", **({"name": name} if name else {}))

    def replay_status(self) -> Dict[str, Any]:
        return self.call("replay.status")

    def replay_list(self) -> Dict[str, Any]:
        return self.call("replay.list")

    def batch(self, commands: List[Dict[str, Any]], continue_on_error: bool = False) -> Dict[str, Any]:
        """Run several commands in order over one round-trip:
        ``bot.batch([{"cmd": "setSlot", "args": {"slot": 0}}, {"cmd": "use", "args": {}}])``"""
        return self.call("batch", commands=commands, continueOnError=continue_on_error)

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
            buf = self._rx[:n]
            self._rx = self._rx[len(buf):]
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


def decode_blocks_in(result: Dict[str, Any]) -> List[str]:
    """Expands a palette-encoded :meth:`ClefClient.blocks_in` result into a flat list of block ids.

    The order is x-major — ``i = ((x-minX)*sizeY + (y-minY))*sizeZ + (z-minZ)`` — matching the
    ``order`` field the bot returns. Blocks in chunks the server has not sent read as
    ``"unloaded"``, which is not the same thing as air."""
    if "blocks" in result:
        return list(result["blocks"])
    palette = result["palette"]
    size_x, size_y, size_z = result["size"]
    data = base64.b64decode(result["data"])
    out: List[str] = []
    value = 0
    shift = 0
    for byte in data:
        value |= (byte & 0x7F) << shift
        if byte & 0x80:
            shift += 7
            continue
        out.append(palette[value])
        value = 0
        shift = 0
    expected = size_x * size_y * size_z
    if len(out) != expected:
        raise ValueError(f"decoded {len(out)} blocks, expected {expected}")
    return out


if __name__ == "__main__":
    # Tiny smoke CLI: python3 clef.py [status|ping|schema]
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    with ClefClient() as _bot:
        print(json.dumps(_bot.call(cmd), indent=2))
