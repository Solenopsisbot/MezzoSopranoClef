#!/usr/bin/env python3
"""
In-process A/B benchmark of the muted-sound short-circuit against a LIVE bot.

One bot, one world, one warmed-up JVM. We drive a deterministic sound load (a mob horde whose
ambient sounds the muted client would otherwise keep spinning up OpenAL sources for), then TOGGLE
the sound disable at runtime via the `optimize` command and sample the `stats` command's
process-CPU counter across equal windows:

    phase 1: sound disable ON   (plays suppressed, per-tick pump skipped)
    phase 2: sound disable OFF  (vanilla sound engine)
    phase 3: sound disable ON   (repeat, to gauge noise)

Because the world, entity set and JIT state are identical across phases, the CPU delta is the work
the disable actually removes — no boot-to-boot confounds.

Env: CLEF_WS_PORT(8731) CLEF_WORLD_TIMEOUT(150) CLEF_SETTLE(6) CLEF_WINDOW(45) CLEF_SUMMON(48)
"""
import base64, json, os, socket, struct, sys, time

def handshake(host, port, t):
    s = socket.create_connection((host, port), timeout=t)
    k = base64.b64encode(os.urandom(16)).decode()
    s.sendall(("GET / HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
               f"Sec-WebSocket-Key: {k}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
    d = b""
    while b"\r\n\r\n" not in d:
        c = s.recv(1024)
        if not c: raise RuntimeError("closed in handshake")
        d += c
    if b"101" not in d.split(b"\r\n", 1)[0]: raise RuntimeError("bad handshake")
    s.settimeout(t); return s

def send(s, text):
    p = text.encode(); m = os.urandom(4)
    mk = bytes(b ^ m[i % 4] for i, b in enumerate(p))
    h = bytearray([0x81]); n = len(p)
    if n < 126: h.append(0x80 | n)
    elif n < 65536: h.append(0x80 | 126); h += struct.pack(">H", n)
    else: h.append(0x80 | 127); h += struct.pack(">Q", n)
    s.sendall(bytes(h) + m + mk)

def recv(s):
    def rd(n):
        b = b""
        while len(b) < n:
            c = s.recv(n - len(b))
            if not c: raise RuntimeError("closed")
            b += c
        return b
    b0, b1 = rd(2); n = b1 & 0x7F
    if n == 126: n = struct.unpack(">H", rd(2))[0]
    elif n == 127: n = struct.unpack(">Q", rd(8))[0]
    data = rd(n) if n else b""
    if (b0 & 0x0F) == 0x9: return recv(s)
    return data.decode("utf-8", "replace")

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
    raise RuntimeError(f"timeout: {cmd}")

def sample(s, settle, window):
    """Returns (cpuCores, dSounds_per_s, dWallSec) over `window`, after `settle`."""
    time.sleep(settle)
    a = call(s, "stats"); time.sleep(window); b = call(s, "stats")
    dw = (b["uptimeMs"] - a["uptimeMs"]) / 1000.0
    cores = (b.get("cpuMs", 0) - a.get("cpuMs", 0)) / 1000.0 / dw
    ds = (b["soundsSuppressed"] - a["soundsSuppressed"]) / dw
    return cores, ds, dw

def main():
    port = int(os.environ.get("CLEF_WS_PORT", "8731"))
    settle = float(os.environ.get("CLEF_SETTLE", "6"))
    window = float(os.environ.get("CLEF_WINDOW", "45"))
    summon = int(os.environ.get("CLEF_SUMMON", "48"))
    mobs = os.environ.get("CLEF_MOBS", "zombie,skeleton,creeper,spider,cow,sheep,pig,chicken").split(",")
    s = handshake("127.0.0.1", port, 15)
    call(s, "ping")

    end = time.time() + float(os.environ.get("CLEF_WORLD_TIMEOUT", "150"))
    st = None
    while time.time() < end:
        st = call(s, "status")
        if st.get("inWorld"): break
        time.sleep(2)
    if not (st and st.get("inWorld")):
        raise RuntimeError("bot never entered world")
    print("[bench] in world; applying load...", flush=True)

    for c in ["/gamerule doDaylightCycle false", "/gamerule doWeatherCycle false",
              "/gamerule doMobSpawning false", "/gamerule sendCommandFeedback false",
              "/gamerule doMobLoot false", "/gamerule doTileDrops false",
              "/difficulty hard", "/time set midnight", "/weather rain 1000000",
              "/kill @e[type=!minecraft:player]"]:
        call(s, "chat", message=c); time.sleep(0.1)
    time.sleep(0.5)
    call(s, "chat", message="/kill @e[type=item]")  # clear any loot so the entity set stays fixed
    time.sleep(0.3)
    for i in range(summon):
        call(s, "chat", message=f"/summon minecraft:{mobs[i % len(mobs)]} ~ ~ ~"); time.sleep(0.03)
    # keep the horde healthy so it stays put and keeps emitting ambient sounds (the load we measure)
    call(s, "chat", message="/effect give @e minecraft:regeneration 99999 4 true")
    ents = len(call(s, "entities", radius=48))
    print(f"[bench] load active: {ents} entities nearby", flush=True)

    # In-process A/B: same world + JIT, just flip the sound short-circuit.
    call(s, "optimize", sound=True)
    on1 = sample(s, settle, window)
    call(s, "optimize", sound=False)
    off = sample(s, settle, window)
    call(s, "optimize", sound=True)
    on2 = sample(s, settle, window)

    on_cores = (on1[0] + on2[0]) / 2.0
    res = {
        "entities": ents, "windowSec": round(window, 1),
        "ON_cores": round(on_cores, 4), "OFF_cores": round(off[0], 4),
        "ON1_cores": round(on1[0], 4), "ON2_cores": round(on2[0], 4),
        "soundsSuppressedPerSec_ON": round((on1[1] + on2[1]) / 2, 1),
        "savedCores": round(off[0] - on_cores, 4),
        "savedPct": round(100.0 * (off[0] - on_cores) / off[0], 1) if off[0] else None,
    }
    print("RESULT " + json.dumps(res), flush=True)
    return 0

if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:
        print(f"[bench] ERROR: {e}", file=sys.stderr); sys.exit(1)
