#!/usr/bin/env python3
"""
Live verification of the agent-facing API added on top of the original actuation surface:
bulk world queries, crafting, navigation completion, the wider event stream, and the
screenshot camera modes.

Run it against a bot that is already in a world (see scripts/e2e.sh for a full harness):

    CLEF_WS_PORT=8731 python3 scripts/verify_agent_api.py

Every check is a real round-trip to a real server. Checks that need something the world may
not have (a villager to trade with, a bed to sleep in) are skipped rather than failed, and
counted separately, so a PASS means "everything testable here worked" rather than
"everything ran". Exit 0 on success.
"""
import base64
import json
import os
import socket
import struct
import sys
import time

PNG_SIG = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])

# ---- websocket plumbing (stdlib only, same shape as the other verify scripts) ----------


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
    """Connect, tolerating a bot that is still booting. The socket timeout is deliberately long:
    during the resource reload the client thread is busy, and every command bounces onto it, so a
    short timeout reads a healthy-but-busy bot as a dead one."""
    end = time.time() + total
    last = None
    while time.time() < end:
        try:
            return handshake(host, port, 60)
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
EVENTS = []


def call(s, cmd, timeout=60, **args):
    global _id
    _id += 1
    mid = str(_id)
    send(s, json.dumps({"id": mid, "cmd": cmd, "args": args}))
    end = time.time() + timeout
    while time.time() < end:
        msg = json.loads(recv(s))
        if msg.get("event"):
            EVENTS.append(msg)
            continue
        if msg.get("id") == mid:
            if not msg.get("ok"):
                raise RuntimeError(f"{cmd}: [{msg.get('code')}] {msg.get('error')}")
            return msg.get("result")
    raise RuntimeError(f"timeout: {cmd}")


def drain(s, seconds):
    """Collect pushed events for a while without sending anything."""
    end = time.time() + seconds
    s.settimeout(0.5)
    try:
        while time.time() < end:
            try:
                msg = json.loads(recv(s))
                if msg.get("event"):
                    EVENTS.append(msg)
            except socket.timeout:
                pass
    finally:
        s.settimeout(60)


def seen(event):
    return [e["data"] for e in EVENTS if e["event"] == event]


# ---- check bookkeeping -----------------------------------------------------------------

PASSED, SKIPPED, FAILED = [], [], []


def ok(name, detail=""):
    PASSED.append(name)
    print(f"  [ok]   {name}{' — ' + detail if detail else ''}")


def skip(name, why):
    SKIPPED.append(name)
    print(f"  [skip] {name} — {why}")


def fail(name, why):
    FAILED.append(name)
    print(f"  [FAIL] {name} — {why}")


def check(name, fn):
    """Runs one check. A raised AssertionError is a failure; Skip is not."""
    try:
        detail = fn()
        ok(name, detail or "")
    except Skip as e:
        skip(name, str(e))
    except Exception as e:
        fail(name, repr(e))


class Skip(Exception):
    pass


# ---- decoding --------------------------------------------------------------------------


def pos(s):
    """Fresh integer block position. Checks must not reuse a position captured earlier in the run:
    mining, placing and respawning all move the bot, and a stale position turns a working command
    into a confusing failure."""
    p = call(s, "status")["player"]
    return int(p["x"] // 1), int(p["y"] // 1), int(p["z"] // 1), p


def decode_blocks_in(result):
    """Reference decoder for the blocksIn palette format — the same one the clients ship."""
    if "blocks" in result:
        return list(result["blocks"])
    palette = result["palette"]
    data = base64.b64decode(result["data"])
    out, value, shift = [], 0, 0
    for byte in data:
        value |= (byte & 0x7F) << shift
        if byte & 0x80:
            shift += 7
            continue
        out.append(palette[value])
        value, shift = 0, 0
    return out


# ---- the checks ------------------------------------------------------------------------


def main():
    host = os.environ.get("CLEF_WS_HOST", "127.0.0.1")
    port = int(os.environ.get("CLEF_WS_PORT", "8731"))
    s = connect(host, port, int(os.environ.get("CLEF_CONNECT_TIMEOUT", "120")))
    token = os.environ.get("CLEF_WS_TOKEN")
    if token:
        assert call(s, "hello", token=token)["authed"], "auth rejected"
    print(f"[verify] connected to {host}:{port}")

    # Wait for the bot to actually be in a world before asserting anything about the world.
    end = time.time() + int(os.environ.get("CLEF_WORLD_TIMEOUT", "180"))
    status = call(s, "status")
    while not status.get("inWorld") and time.time() < end:
        time.sleep(2)
        status = call(s, "status")
    if not status.get("inWorld"):
        print("[verify] bot never entered a world", file=sys.stderr)
        return 1
    print(f"[verify] in world at {status['player']['x']:.1f},"
          f"{status['player']['y']:.1f},{status['player']['z']:.1f}")

    # "In a world" and "finished loading it" are not the same thing. While
    # DownloadingTerrainScreen is up, MinecraftClient skips the entire input path, so anything that
    # holds a key — shootAt drawing a bow, useHold — silently does nothing. Wait for the screen to
    # clear rather than racing it.
    while call(s, "status").get("screen") != "none" and time.time() < end:
        time.sleep(1)

    call(s, "subscribe")   # everything; the event checks below filter what they need
    schema = call(s, "schema")

    print("\n[verify] contract")
    check("schema protocol is 2", lambda: assert_eq(schema["protocol"], 2))
    check("schema declares every registered command", lambda: schema_matches(s, schema))

    print("\n[verify] rich status")
    st = call(s, "status")
    check("status.world carries time/weather/biome/light", lambda: world_status(st))
    check("status.player carries survival context", lambda: player_status(st))

    print("\n[verify] bulk world queries")
    check("findBlocks finds stone by id", lambda: find_blocks_id(s))
    check("findBlocks accepts a #tag", lambda: find_blocks_tag(s))
    check("findBlocks respects max and sorts nearest-first", lambda: find_blocks_sorted(s))
    check("blocksIn round-trips through the palette codec", lambda: blocks_in(s))
    check("blocksIn refuses an oversized region", lambda: blocks_in_too_big(s))
    check("registry lists the running version's ids", lambda: registry(s))
    check("target reports block/entity/none", lambda: target(s))

    print("\n[verify] navigation")
    check("nav.check answers without moving", lambda: nav_check(s))
    check("nav.check reports unknown, not unreachable, when it runs out of budget",
          lambda: nav_check_budget(s))

    print("\n[verify] baritone passthrough")
    check("block-argument support is initialised off the client thread", lambda: baritone_warm(s))
    check("a block-argument command does not deadlock the client", lambda: baritone_block_arg(s))
    check("baritone returns the lines it printed", lambda: baritone_output(s))

    print("\n[verify] readable screen names")
    check("status reports a readable screen name and the raw class", lambda: screen_names(s))

    print("\n[verify] mining and placing report reality")
    check("mine wait:true returns a verified outcome", lambda: mine_wait(s))
    check("mine reports cant_break for an unbreakable block", lambda: mine_unbreakable(s))
    check("place verifies the world actually changed", lambda: place_verified(s))

    print("\n[verify] crafting")
    # Hand the bot the inputs first. The client recipe book only holds what the *server* has
    # unlocked, and vanilla unlocks a recipe when you first obtain its ingredients — so asking
    # about sticks before owning any planks legitimately returns nothing.
    stock_up(s)
    check("recipes describes a known recipe", lambda: recipes(s))
    check("craftable lists what the inventory affords", lambda: craftable(s))
    check("craft actually produces items", lambda: craft(s))

    print("\n[verify] inventory helpers")
    check("moveToHotbar selects the item", lambda: move_to_hotbar(s))

    print("\n[verify] chat")
    check("chat kind is the protocol-2 enum", lambda: chat_kinds(s))
    check("chatHistory replays what was said", lambda: chat_history(s))

    print("\n[verify] batch")
    check("batch runs in order and reports each result", lambda: batch_ok(s))
    check("batch stops at the first error by default", lambda: batch_stops(s))
    check("batch refuses to nest", lambda: batch_no_nest(s))

    print("\n[verify] event stream")
    check("nav.failed is terminal and non-terminal path events are separate",
          lambda: nav_event_shapes(schema))
    check("blockUpdate fires for a nearby change", lambda: event_block_update(s))
    check("time/weather/title/inventory events are declared", lambda: events_declared(schema))
    check("entityHurt names the victim", lambda: event_entity_hurt(s))

    print("\n[verify] combat loops")
    check("combat.status reports an idle body", lambda: combat_idle(s))
    check("shootAt validates its arguments", lambda: shoot_bad_args(s))
    check("shootAt reports NOT_FOUND for an entity that isn't there", lambda: shoot_no_entity(s))
    check("entities carries per-tick motion as vx/vy/vz", lambda: entity_motion(s))
    check("shootAt looses the arrows it was asked for", lambda: shoot_at_a_pig(s))
    check("meleeWhile swings while the target is in reach", lambda: melee_a_pig(s))

    print("\n[verify] equipment")
    check("equipping worn armour a second time does not undress", lambda: equip_idempotent(s))

    print("\n[verify] screenshots")
    check("perspective capture returns a PNG", lambda: shot_normal(s))
    check("topdown capture returns a PNG", lambda: shot_topdown(s))
    check("annotate returns the camera and entity boxes", lambda: shot_annotated(s))

    print(f"\n[verify] {len(PASSED)} passed, {len(SKIPPED)} skipped, {len(FAILED)} failed")
    if FAILED:
        for name in FAILED:
            print(f"  FAILED: {name}")
        return 1
    return 0


# ---- individual checks -----------------------------------------------------------------


def assert_eq(actual, expected):
    assert actual == expected, f"expected {expected!r}, got {actual!r}"
    return str(actual)


def schema_matches(s, schema):
    declared = {c["name"] for c in schema["commands"]}
    registered = set(call(s, "help").keys())
    assert declared == registered, f"drift: {declared ^ registered}"
    return f"{len(declared)} commands, {len(schema['events'])} events"


def world_status(st):
    w = st["world"]
    assert w["time"]["phase"] in ("dawn", "day", "dusk", "night"), w["time"]
    assert w["weather"] in ("clear", "rain", "thunder"), w["weather"]
    assert "biome" in w, "no biome"
    assert 0 <= w["light"]["sky"] <= 15, w["light"]
    return f"{w['time']['phase']}, {w['weather']}, {w['biome']}"


def player_status(st):
    p = st["player"]
    for field in ("gamemode", "air", "fallDistance", "effects", "armor", "offhand",
                  "inWater", "inLava", "onFire", "sleeping", "saturation", "absorption"):
        assert field in p, f"missing status.player.{field}"
    assert len(p["armor"]) == 4, p["armor"]
    return f"gamemode={p['gamemode']}, armor={p['armor']}"


def find_blocks(s, ids, **kw):
    return call(s, "findBlocks", ids=ids, **kw)


def find_blocks_id(s):
    hits = find_blocks(s, ["stone"], radius=32, max=16)
    if not hits:
        raise Skip("no stone within 32 blocks")
    assert all(h["block"].endswith(":stone") for h in hits), hits[:3]
    return f"{len(hits)} hits, nearest {hits[0]['distance']:.1f} blocks"


def find_blocks_tag(s):
    for tag in ("#minecraft:dirt", "#minecraft:base_stone_overworld", "#minecraft:logs"):
        hits = find_blocks(s, [tag], radius=32, max=8)
        if hits:
            return f"{tag}: {len(hits)} hits ({hits[0]['block']})"
    raise Skip("none of the probe tags are present nearby")


def find_blocks_sorted(s):
    hits = find_blocks(s, ["stone", "dirt", "grass_block"], radius=48, max=5)
    if len(hits) < 2:
        raise Skip("fewer than two blocks nearby to order")
    assert len(hits) <= 5, f"max ignored: {len(hits)}"
    distances = [h["distance"] for h in hits]
    assert distances == sorted(distances), distances
    return f"{len(hits)} hits in ascending distance"


def blocks_in(s):
    x, y, z, _ = pos(s)
    region = call(s, "blocksIn", minX=x - 2, minY=y - 2, minZ=z - 2,
                  maxX=x + 2, maxY=y + 2, maxZ=z + 2)
    sx, sy, sz = region["size"]
    assert [sx, sy, sz] == [5, 5, 5], region["size"]
    blocks = decode_blocks_in(region)
    assert len(blocks) == 125, f"decoded {len(blocks)}"

    # The x-major index must agree with blockAt for a specific cell.
    probe = (1, 0, -1)
    idx = ((probe[0] + 2) * sy + (probe[1] + 2)) * sz + (probe[2] + 2)
    direct = call(s, "blockAt", x=x + probe[0], y=y + probe[1], z=z + probe[2])["block"]
    assert blocks[idx] == direct, f"index disagrees with blockAt: {blocks[idx]} vs {direct}"

    plain = call(s, "blocksIn", minX=x, minY=y, minZ=z, maxX=x, maxY=y, maxZ=z, palette=False)
    assert plain["blocks"] == [call(s, "blockAt", x=x, y=y, z=z)["block"]], plain
    return f"palette of {len(region['palette'])}, index agrees with blockAt"


def blocks_in_too_big(s):
    try:
        call(s, "blocksIn", minX=0, minY=0, minZ=0, maxX=200, maxY=200, maxZ=200)
    except RuntimeError as e:
        assert "BAD_ARGS" in str(e), e
        return "rejected with BAD_ARGS"
    raise AssertionError("a 201^3 region was accepted")


def registry(s):
    reg = call(s, "registry", kinds=["blocks"])
    assert len(reg["blocks"]) > 500, len(reg["blocks"])
    assert "minecraft:stone" in reg["blocks"]
    assert "items" not in reg, "kinds filter ignored"
    return f"{len(reg['blocks'])} blocks, version {reg['minecraftVersion']}"


def target(s):
    call(s, "look", pitch=90.0)          # look straight down: the ground is right there
    time.sleep(0.3)
    hit = call(s, "target", maxDistance=5.0)
    assert hit["kind"] in ("block", "entity", "none"), hit
    if hit["kind"] == "none":
        # Underwater the ground can be further than 5 blocks, and fluids are transparent to the
        # default raycast — retry the way a bot actually would before calling it a miss.
        hit = call(s, "target", maxDistance=16.0, fluids=True)
    if hit["kind"] == "none":
        raise Skip("nothing within 16 blocks even looking straight down")
    return f"{hit['kind']} {hit.get('block') or hit.get('type')} at {hit.get('distance', 0):.2f}"


def nav_check(s):
    x, y, z, before = pos(s)
    if not before["onGround"]:
        # Mid-fall there is genuinely nowhere to walk from, and the bot's y is moving under us.
        raise Skip("bot is not on the ground")
    here = call(s, "nav.check", x=x, y=y, z=z, reach=1)
    assert here["reachable"], f"cannot reach own position: {here}"
    far = call(s, "nav.check", x=x + 4, y=y, z=z + 4, reach=1)
    assert "reachable" in far and "nodes" in far, far
    after = call(s, "status")["player"]
    assert (round(before["x"], 3), round(before["z"], 3)) == (round(after["x"], 3), round(after["z"], 3)), \
        "nav.check moved the bot"
    return f"self reachable (cost {here['cost']:.1f}); +4,+4 reachable={far['reachable']}"


def nav_check_budget(s):
    x, y, z, _ = pos(s)
    # A tiny budget against a far goal is guaranteed to exhaust, which must read as "unknown".
    result = call(s, "nav.check", x=x + 400, y=y, z=z + 400, reach=1, maxNodes=100)
    assert "verdict" in result, f"no verdict field: {result}"
    assert result["verdict"] in ("reachable", "unreachable", "unknown"), result
    if not result["exhausted"]:
        raise Skip("a 100-node search did not exhaust; nothing to assert about the budget")
    assert result["verdict"] == "unknown", \
        f"exhausted search reported {result['verdict']!r} instead of unknown: {result}"
    return f"verdict={result['verdict']} after {result['nodes']} nodes"


def baritone_warm(s):
    nav = call(s, "nav.status")
    if not nav["available"]:
        raise Skip("Baritone is not installed in this build")
    # Warm-up starts on world join and takes well under a second; give it a moment regardless.
    for _ in range(30):
        state = call(s, "nav.status").get("blockArguments")
        if state != "pending":
            break
        time.sleep(1)
    state = call(s, "nav.status").get("blockArguments")
    assert state is not None, "nav.status does not report blockArguments"
    assert state == "ready", f"block arguments are {state!r}, so block commands will be refused"
    return f"blockArguments={state}"


def baritone_block_arg(s):
    """The regression that matters most: `goto <block>` used to park the client thread forever."""
    if not call(s, "nav.status")["available"]:
        raise Skip("Baritone is not installed in this build")
    started = time.time()
    try:
        call(s, "baritone", command="goto oak_log", timeout=45)
    except RuntimeError as e:
        # A refusal is a correct outcome too — it means the guard caught an un-warmed bot.
        if "refusing" not in str(e):
            raise
        return f"refused safely: {str(e)[:60]}"
    finally:
        try:
            call(s, "baritone", command="cancel", timeout=20)
        except RuntimeError:
            pass
    # The real assertion: the client thread still works afterwards.
    call(s, "status", timeout=20)
    call(s, "blockAt", x=0, y=64, z=0, timeout=20)
    return f"survived, and the client thread is still responsive ({time.time() - started:.1f}s)"


def baritone_output(s):
    if not call(s, "nav.status")["available"]:
        raise Skip("Baritone is not installed in this build")
    result = call(s, "baritone", command="version", collectMs=600, timeout=30)
    assert "output" in result, result
    if not result["output"]:
        raise Skip("Baritone printed nothing for `version` on this build")
    return f"{len(result['output'])} line(s): {result['output'][0][:50]}"


def screen_names(s):
    st = call(s, "status")
    assert "screenClass" in st, "status has no screenClass"
    allowed = {"none", "title", "connect", "disconnected", "death", "downloading", "message",
               "inventory", "crafting", "furnace", "anvil", "enchanting", "merchant", "sign",
               "book", "container", "other"}
    assert st["screen"] in allowed, f"unknown screen name {st['screen']!r}"
    return f"screen={st['screen']} screenClass={st['screenClass']}"


def nav_event_shapes(schema):
    events = {e["name"]: e for e in schema["events"]}
    failed = events["nav.failed"]
    assert any(f["name"] == "terminal" for f in failed["fields"]), \
        "nav.failed does not declare `terminal`"
    assert "nav.progress" in events, "no nav.progress event for non-terminal path events"
    assert "baritone.log" in events, "no baritone.log event"
    return "nav.failed is terminal; nav.progress and baritone.log exist"


def solid_block_near(s):
    """A nearby breakable block, or Skip if the bot is standing in open air."""
    hits = find_blocks(s, ["stone", "dirt", "grass_block", "sand", "gravel"], radius=6, max=1)
    if not hits:
        raise Skip("no breakable block within 6 blocks")
    return hits[0]


def mine_wait(s):
    block = solid_block_near(s)
    result = call(s, "mine", x=block["x"], y=block["y"], z=block["z"], wait=True, timeout=60)
    assert result["mining"] is False, result
    assert "broken" in result and "ticks" in result, result
    if not result["broken"]:
        return f"reported {result.get('reason')} in {result['ticks']} ticks (out of reach is fine)"
    after = call(s, "blockAt", x=block["x"], y=block["y"], z=block["z"])
    assert after["air"], f"claimed broken but block is {after['block']}"
    return f"broke {block['block']} in {result['ticks']} ticks"


def mine_unbreakable(s):
    """Bedrock is usually hundreds of blocks below, so put some within reach if we can."""
    hits = find_blocks(s, ["bedrock"], radius=64, max=1)
    if not hits:
        x, y, z, _ = pos(s)
        call(s, "chat", message=f"/setblock {x + 3} {y} {z} minecraft:bedrock replace")
        time.sleep(1.5)
        hits = find_blocks(s, ["bedrock"], radius=8, max=1)
    if not hits:
        raise Skip("no bedrock in range and /setblock was denied (needs op)")
    b = hits[0]
    try:
        result = call(s, "mine", x=b["x"], y=b["y"], z=b["z"], wait=True, timeout=60)
        assert result["broken"] is False, result
        assert result["reason"] == "cant_break", result
        return f"reason={result['reason']} detail={result.get('detail', '')[:48]}"
    finally:
        call(s, "chat", message=f"/setblock {b['x']} {b['y']} {b['z']} minecraft:air replace")


PLACEABLE_SUFFIXES = ("_block", ":stone", ":dirt", "_planks", ":cobblestone", ":glass", "_wool")


def placeable_item(s):
    inv = call(s, "inventory")["items"]
    found = next((i["item"] for i in inv if i["item"].endswith(PLACEABLE_SUFFIXES)), None)
    if found:
        return found
    call(s, "chat", message="/give @s minecraft:cobblestone 16")
    time.sleep(1.5)
    inv = call(s, "inventory")["items"]
    return next((i["item"] for i in inv if i["item"].endswith(PLACEABLE_SUFFIXES)), None)


def place_verified(s):
    item = placeable_item(s)
    if not item:
        raise Skip("nothing placeable in the inventory and /give was denied (needs op)")
    x, y, z, _ = pos(s)
    # Clear a target column, then place on top of the block below it.
    call(s, "chat", message=f"/setblock {x + 1} {y} {z} minecraft:air replace")
    call(s, "chat", message=f"/setblock {x + 1} {y - 1} {z} minecraft:stone replace")
    time.sleep(1.0)
    call(s, "lookAt", x=x + 1, y=y - 1, z=z)
    result = call(s, "place", x=x + 1, y=y - 1, z=z, face="up", item=item, timeout=30)
    assert result["confirmed"] is True, result
    assert isinstance(result["placed"], bool), result
    if result["placed"]:
        assert result["block"], result
        after = call(s, "blockAt", x=result["x"], y=result["y"], z=result["z"])
        assert after["block"] == result["block"], f"{after} vs {result}"
        call(s, "chat", message=f"/setblock {result['x']} {result['y']} {result['z']} minecraft:air replace")
        return f"placed {result['block']} after {result['ticks']} ticks, confirmed by blockAt"
    return "reported placed=false honestly (nothing changed within the confirm window)"


def stock_up(s):
    """Best-effort: give the bot planks so the crafting checks have something to work with."""
    call(s, "chat", message="/give @s minecraft:oak_planks 16")
    time.sleep(1.5)


def recipes(s):
    found = call(s, "recipes", item="stick")
    if not found:
        raise Skip("the server has not unlocked the stick recipe")
    r = found[0]
    for field in ("result", "count", "ingredients", "needsTable", "kind", "recipeId"):
        assert field in r, f"missing {field}"
    assert r["result"].endswith(":stick"), r["result"]
    assert r["needsTable"] is False, "sticks fit a 2x2 grid"
    return f"{len(found)} recipe(s), {r['count']} per craft, {len(r['ingredients'])} ingredients"


def craftable(s):
    result = call(s, "craftable")
    assert "grid" in result and "items" in result, result
    assert "knownRecipes" in result, "craftable does not say how many recipes the book holds"
    if result["knownRecipes"] == 0:
        raise Skip("the server has unlocked no recipes for this account yet")
    inv = {i["item"]: i["count"] for i in call(s, "inventory")["items"]}
    if any(k.endswith("_planks") for k in inv) and not result["items"]:
        raise AssertionError(f"holding planks but craftable is empty (knownRecipes="
                             f"{result['knownRecipes']}, inventory={list(inv)})")
    assert all("fitsOpenGrid" in i for i in result["items"]), result["items"][:2]
    if not result["items"]:
        raise Skip("nothing craftable from the current inventory")
    return f"{len(result['items'])} craftable on the {result['grid']} grid"


def craft(s):
    before = call(s, "findItem", item="stick")["total"]
    result = call(s, "craft", item="stick", count=4, timeout=90)
    assert "crafted" in result, result
    if result["crafted"] == 0:
        raise Skip(f"nothing crafted (reason={result.get('reason')}); needs creative or planks")
    after = call(s, "findItem", item="stick")["total"]
    assert after > before, f"craft reported {result['crafted']} but inventory went {before} -> {after}"
    return f"crafted {result['crafted']} (inventory {before} -> {after})"


def move_to_hotbar(s):
    inv = call(s, "inventory")["items"]
    if not inv:
        raise Skip("inventory is empty")
    item = inv[-1]["item"]
    result = call(s, "moveToHotbar", item=item)
    assert 0 <= result["slot"] <= 8, result
    held = call(s, "status")["player"]["heldItem"]
    assert held.startswith(item), f"selected slot {result['slot']} but holding {held}"
    return f"{item} in slot {result['slot']} (moved={result['moved']})"


def chat_kinds(s):
    EVENTS.clear()
    call(s, "chat", message="clef-verify chat probe")
    drain(s, 3)
    messages = seen("chat")
    if not messages:
        raise Skip("no chat event came back (server may suppress echo)")
    kinds = {m["kind"] for m in messages}
    allowed = {"chat", "system", "whisper", "team", "actionbar"}
    assert kinds <= allowed, f"unexpected kinds: {kinds - allowed}"
    assert any("raw" in m for m in messages), "no message carried the raw component"
    return f"kinds={sorted(kinds)}, raw present"


def chat_history(s):
    marker = f"clef-verify-history-{int(time.time())}"
    call(s, "chat", message=marker)
    time.sleep(1.5)
    history = call(s, "chatHistory", limit=25)
    assert history["stored"] >= 1, history
    assert any(marker in line["text"] for line in history["lines"]), \
        f"marker not in the last 25 lines ({len(history['lines'])} stored)"
    return f"{history['stored']} lines buffered"


def batch_ok(s):
    result = call(s, "batch", commands=[
        {"cmd": "setSlot", "args": {"slot": 3}},
        {"cmd": "status", "args": {}},
        {"cmd": "setSlot", "args": {"slot": 0}},
    ])
    assert result["ran"] == 3, result
    assert all(r["ok"] for r in result["results"]), result["results"]
    assert result["results"][1]["result"]["player"]["selectedSlot"] == 3, "order not preserved"
    return "3 commands, order preserved"


def batch_stops(s):
    result = call(s, "batch", commands=[
        {"cmd": "ping", "args": {}},
        {"cmd": "setSlot", "args": {"slot": 99}},   # out of range
        {"cmd": "ping", "args": {}},
    ])
    assert result["ran"] == 2, f"should stop after the failure: {result}"
    assert result["results"][1]["code"] == "BAD_ARGS", result["results"][1]
    cont = call(s, "batch", continueOnError=True, commands=[
        {"cmd": "setSlot", "args": {"slot": 99}},
        {"cmd": "ping", "args": {}},
    ])
    assert cont["ran"] == 2 and cont["results"][1]["ok"], cont
    return "stops by default, continues with continueOnError"


def batch_no_nest(s):
    result = call(s, "batch", commands=[{"cmd": "batch", "args": {"commands": []}}])
    assert result["results"][0]["ok"] is False, result
    assert result["results"][0]["code"] == "BAD_ARGS", result["results"][0]
    return "nested batch rejected"


def event_block_update(s):
    x, y, z, _ = pos(s)
    # Start from a known non-glass state, or "replace with glass" can be a no-op that
    # legitimately produces no update.
    call(s, "chat", message=f"/setblock {x + 2} {y} {z} minecraft:air replace")
    time.sleep(1.0)
    EVENTS.clear()
    call(s, "chat", message=f"/setblock {x + 2} {y} {z} minecraft:glass replace")
    drain(s, 3)
    updates = seen("blockUpdate")
    if not updates:
        raise Skip("no blockUpdate (setblock may have been denied — needs op)")
    glass = [u for u in updates if u["to"].endswith(":glass")]
    assert glass, f"no glass in {[u['to'] for u in updates]}"
    assert all(k in glass[0] for k in ("x", "y", "z", "from", "to")), glass[0]
    call(s, "chat", message=f"/setblock {x + 2} {y} {z} minecraft:air replace")
    return f"{glass[0]['from']} -> {glass[0]['to']}"


def events_declared(schema):
    names = {e["name"] for e in schema["events"]}
    expected = {"blockUpdate", "itemPickup", "inventory", "entityHurt", "explosion",
                "weather", "time", "sleep", "title", "mineDone", "nav.done", "nav.failed",
                "combatDone"}
    missing = expected - names
    assert not missing, f"schema is missing {missing}"
    return f"{len(names)} events declared"


def event_entity_hurt(s):
    EVENTS.clear()
    call(s, "chat", message="/summon minecraft:pig ~ ~ ~2")
    time.sleep(1.0)
    call(s, "chat", message="/damage @e[type=pig,limit=1] 2")
    drain(s, 3)
    hurts = seen("entityHurt")
    if not hurts:
        raise Skip("no entityHurt (summon/damage may have been denied — needs op)")
    assert "id" in hurts[0] and "type" in hurts[0] and "self" in hurts[0], hurts[0]
    call(s, "chat", message="/kill @e[type=pig]")
    return f"{hurts[0]['type']} hurt, health now {hurts[0].get('health')}"


# ---- combat and equipment ---------------------------------------------------------------


def give(s, item, count=1):
    """Hands the bot an item, returning False when the server refused (no op)."""
    call(s, "chat", message=f"/give @s {item} {count}")
    time.sleep(0.6)
    return call(s, "findItem", item=item)["total"] >= count


def summon_pig(s, dz):
    """A pig `dz` blocks north of the bot, or None when /summon was denied."""
    call(s, "chat", message=f"/summon minecraft:pig ~ ~ ~{dz}")
    time.sleep(1.0)
    pigs = call(s, "entities", radius=dz + 10, kinds=["pig"])
    return pigs[0] if pigs else None


def kill_pigs(s):
    call(s, "chat", message="/kill @e[type=pig]")


def combat_idle(s):
    status = call(s, "combat.status")
    assert status["busy"] is False, status
    stop = call(s, "combat.stop")
    assert stop["stopped"] is False, f"stopped something that wasn't running: {stop}"
    return "idle, and combat.stop is honest about it"


def shoot_bad_args(s):
    for args, why in (({"entityId": 1, "shots": 0}, "zero shots"),
                      ({"entityId": 1, "charge": 0}, "zero charge"),
                      ({"entityId": 1, "maxRange": 0}, "zero range")):
        try:
            call(s, "shootAt", **args)
            raise AssertionError(f"{why} was accepted")
        except RuntimeError as e:
            assert "BAD_ARGS" in str(e), f"{why} gave {e}"
    return "zero shots/charge/range all rejected as BAD_ARGS"


def shoot_no_entity(s):
    try:
        call(s, "shootAt", entityId=2_000_000_000)
    except RuntimeError as e:
        assert "NOT_FOUND" in str(e), e
        return "NOT_FOUND"
    raise AssertionError("shot at an entity that does not exist")


def entity_motion(s):
    """vx/vy/vz must be present, numeric, and agree with the `velocity` array."""
    entities = call(s, "entities", radius=48)
    if not entities:
        if summon_pig(s, 4) is None:
            raise Skip("nothing nearby and /summon was denied (needs op)")
        entities = call(s, "entities", radius=48)
    if not entities:
        raise Skip("no entities to inspect")
    moving = 0
    for e in entities:
        for field in ("vx", "vy", "vz"):
            assert field in e, f"{e['type']} is missing {field}: {e}"
            assert isinstance(e[field], (int, float)), f"{field} is not a number: {e[field]}"
        assert e["velocity"] == [e["vx"], e["vy"], e["vz"]], \
            f"velocity array disagrees with the scalars: {e}"
        if abs(e["vx"]) + abs(e["vy"]) + abs(e["vz"]) > 1e-6:
            moving += 1
    return f"{len(entities)} entities, {moving} of them in motion"


def shoot_at_a_pig(s):
    if not give(s, "minecraft:bow") or not give(s, "minecraft:arrow", 16):
        raise Skip("/give was denied (needs op)")
    pig = summon_pig(s, 12)
    if pig is None:
        raise Skip("/summon was denied (needs op)")
    try:
        # Blocks for the whole volley: that is the point of moving the loop onto the bot.
        result = call(s, "shootAt", entityId=pig["id"], shots=2, maxRange=48)
    finally:
        kill_pigs(s)
    assert result["kind"] == "shootAt", result
    assert result["stopped"] in ("done", "dead"), result
    # Fewer arrows than asked for is only legitimate if the target stopped existing: a fully drawn
    # bow does up to 10 damage and a pig has exactly that much, so a clean first shot ends the
    # volley. Anything else short of `shots` is the loop giving up.
    assert 1 <= result["fired"] <= 2, f"wrong number of arrows: {result}"
    assert result["fired"] == 2 or result["killed"], f"stopped short without a kill: {result}"
    return (f"fired {result['fired']} in {result['ticks']} ticks, stopped={result['stopped']}, "
            f"damage={result['damage']}, killed={result['killed']}")


def melee_a_pig(s):
    pig = summon_pig(s, 1)
    if pig is None:
        raise Skip("/summon was denied (needs op)")
    try:
        result = call(s, "meleeWhile", entityId=pig["id"], maxMs=4000, reach=4.5)
    finally:
        kill_pigs(s)
    assert result["kind"] == "meleeWhile", result
    assert result["hits"] > 0, f"never got a swing in: {result}"
    # Swinging is not the claim; connecting is. Damage-attribution is not visible from the client,
    # but nothing else is hitting a pig that was summoned a second ago.
    assert result["damage"] > 0, f"swung {result['hits']} times and hurt nothing: {result}"
    assert result["stopped"] in ("dead", "timeout"), result
    return (f"{result['hits']} swings for {result['damage']} damage, killed={result['killed']}, "
            f"stopped={result['stopped']}")


def equip_idempotent(s):
    if not give(s, "minecraft:iron_chestplate"):
        raise Skip("/give was denied (needs op)")
    # Start bare, or a bot that is already wearing one from an earlier run makes the first equip a
    # legitimate no-op and the check reads as a failure.
    call(s, "chat", message="/item replace entity @s armor.chest with minecraft:air")
    time.sleep(0.5)
    first = call(s, "equip", item="minecraft:iron_chestplate")
    assert first["changed"] is True, first
    time.sleep(0.5)
    worn = call(s, "status")["player"]["armor"]
    assert worn[1] == "minecraft:iron_chestplate", f"never got worn: {worn}"

    # The regression: `equip` is a shift-click, and shift-clicking a worn piece takes it off.
    second = call(s, "equip", item="minecraft:iron_chestplate")
    assert second["changed"] is False, f"clicked again instead of no-op: {second}"
    assert second.get("slot") == "chest", second
    time.sleep(0.5)
    still = call(s, "status")["player"]["armor"]
    assert still[1] == "minecraft:iron_chestplate", f"re-equipping undressed the bot: {still}"
    return "worn once, no-op the second time, still worn"


def shot(s, **kw):
    result = call(s, "screenshot", timeout=120, **kw)
    png = base64.b64decode(result["base64"])
    assert png[:8] == PNG_SIG, "not a PNG"
    assert len(png) > 200, f"suspiciously small PNG ({len(png)} bytes)"
    return result, png


def shot_normal(s):
    result, png = shot(s, width=160, height=120)
    assert result["mode"] == "normal", result["mode"]
    return f"{len(png)} bytes in {result['durationMs']:.0f}ms"


def shot_topdown(s):
    result, png = shot(s, mode="topdown", radius=24, width=160, height=160)
    assert result["mode"] == "topdown", result["mode"]
    return f"{len(png)} bytes in {result['durationMs']:.0f}ms"


def shot_annotated(s):
    result, _ = shot(s, width=160, height=120, annotate=True)
    cam = result["camera"]
    assert cam["projection"] == "perspective", cam
    assert cam["width"] == 160 and cam["height"] == 120, cam
    assert isinstance(result["entities"], list), result
    visible = [e for e in result["entities"] if e["visible"]]
    for e in visible:
        assert len(e["box"]) == 4, e
    return f"camera + {len(result['entities'])} entities ({len(visible)} on screen)"


if __name__ == "__main__":
    sys.exit(main())
