# MezzoSopranoClef

*by Solenopsisbot*

A **headless, render-on-demand Minecraft client** for botting, built as a Fabric mod for
**Minecraft 1.21.8**. It runs the *real* game client (so client-side Fabric mods and Baritone
work), but rips out per-frame rendering, the window, and audio. The GPU only ever does work
when you explicitly ask for a screenshot — at any position, angle, and resolution you want.

Think: *Baritone's brain in a client that's asleep until you poke it.*

---

## Why it's built this way

You asked for two things that **force** the architecture:

1. **Fabric mod support**, and
2. **Real screenshots of the world at any angle/resolution.**

Both require running the actual Minecraft client. A protocol-level bot (Mineflayer, etc.) can't
load client mods and has no real renderer, so it's off the table. Instead we run the real
client and:

- **Skip the render loop** — `MinecraftClientHeadlessMixin` cancels `MinecraftClient.render(boolean)`
  every frame while headless. Game ticks, networking, physics and entity tracking keep running.
  It also paces the loop (the render path is normally what throttles it) so idle CPU stays near zero.
- **Hide the window** — `WindowMixin` keeps the GLFW window created (so a real GL context exists
  for screenshots) but never shows it.
- **Mute audio** — in headless mode the client sets master volume to 0 once options are ready.
  We keep the sound engine intact because tearing it down can crash gameplay sounds.
- **Screenshot on demand, GPU-free** — the default `software` backend is a CPU voxel raycaster
  (`SoftwareRaycaster`): it snapshots the loaded world's blocks and rasterizes from any camera at
  any resolution, on the CPU, then PNG-encodes via ImageIO. **No GL context, no GPU, no window** —
  runs on any headless host and is fully unit-tested. An optional `gl` backend renders through the
  real client renderer for vanilla fidelity when a GL context is available.

---

## Status (read this — I don't oversell)

The entire project **compiles and builds green against real 1.21.8 Yarn mappings**
(`./gradlew build` → `compileJava` + Mixin annotation processor + `remapJar` all pass). That
means every mixin `@Shadow`/`@Accessor` target, the `Session` constructor, and all Minecraft API
calls were verified against the actual mapped game — not guessed.

**Verified by building against 1.21.8:** project layout, dependency stack, all mixin targets
resolve, auth module, WebSocket control plane, command layer, Baritone reflective bridge,
session injection, server connect/disconnect, chat/look/players/status.

**Verified by the test suite (`./gradlew test`, 48 tests, all green):** the RFC 6455 WebSocket
codec (handshake, masking, fragmentation, ping/pong, close, oversized-frame rejection); the full
control-plane wire over a real socket (dispatch, `ping`/`help`/error, **event subscription
filtering** with two clients) via an in-process `ControlServer`; the **complete Microsoft auth
flow against a local mock HTTP server** (device-code polling, `authorization_pending`, refresh,
errors); offline UUID derivation; config load/override; the CPU raycaster (orientation, ground/
sky, entity hits, occlusion, dimensions); movement-vector + snapshot-bounds math; and PNG
encoding.

**Validated live against a real 1.21.8 server (`scripts/verify_live.py`):** the headless client
boots, dismisses the first-launch onboarding screen, injects the offline session, **auto-connects
and spawns as `ClefBot`**, then — asserted programmatically — **walks** (position delta),
**breaks** and **mines** blocks (verified turning to air), selects hotbar slots, lists
inventory/entities, receives **chat events**, and produces a **GPU-free PNG screenshot**.
Render-skip engages only in-world so the loading screen never deadlocks.

**Still worth knowing:**

- **The optional `gl` screenshot backend** wasn't pixel-verified (the `software` default is what's
  tested and recommended). It uses the blaze3d `GpuDevice` pipeline correctly but needs a real/
  software GL context, and is serialized (not reentrant); prefer `software` unless you specifically
  need vanilla fidelity.
- **Audio** initialises normally (so gameplay sounds — e.g. block breaking — don't crash the
  action). On a headless host with no audio device, Minecraft disables sound gracefully on its own,
  so there's no overhead where it matters.
- **Online-mode signed chat.** Session injection swaps the identity used for the server-join
  handshake (works for joining online servers). It does not rebuild `UserApiService`, so 1.19+
  *secure chat* on strict servers may need extra work.

It's a real, working bot aimed at full player parity: movement (WASD/jump/sprint/sneak), mining,
placing, using items, combat, hotbar/inventory, **driving any vanilla/modded container UI**
(chests, furnaces, crafting, anvils, **villager/wandering-trader trades**), generic screen widgets,
right-click entity interaction (mount/trade/breed), timed use (eat/drink/charge bow), swap-hand,
pick-block, **death events + auto-respawn**, world/UI queries (inventory, entities, scoreboard,
boss bars, title), Baritone pathing, a live event stream, and GPU-free screenshots. Idle in-world
CPU is roughly ~10–25% of one core (tunable via `headlessLoopSleepMs`); the floor is the
unavoidable 20 TPS simulation, so you can run several bots per core.

The remaining gaps to literal 100% parity are mostly convenience wrappers over primitives that
already exist (e.g. a high-level `craft <item>` on top of `clickSlot`), and a few niche reads
(advancements/statistics). The fundamental "click any slot, open any UI, interact anything" capability
is in.

---

## Quick start

```bash
# 1. Run the headless bot (first run downloads MC assets; Gradle auto-provisions JDK 21).
#    The bot reads its config from run/config/mezzoclef.json (written on first run).
./gradlew runClient

# 2. Easiest way to drive it: enable the built-in web dashboard, then open it in a browser.
./gradlew runClient -Dmezzoclef.dashboard=true
#    -> open http://127.0.0.1:8732  (Connect is automatic; use the buttons / WASD / screenshot)

# 3. Or script it over WebSocket (any language). Example with the bundled probe:
python3 scripts/ws_probe.py            # one-shot assertions
python3 scripts/events_probe.py        # tail the live event stream
# If the control token is enabled, add: CLEF_WS_TOKEN="$(jq -r .control.authToken run/config/mezzoclef.json)"
```

To **connect to a server**: set `connection.autoConnect`/`serverHost` in the config (or
`-Dmezzoclef.connect.auto=true -Dmezzoclef.connect.host=play.example.com`), or send the
`connect` command from the dashboard/WebSocket at runtime.

For **Microsoft (online) accounts** see *Microsoft auth setup* below; for offline/cracked
servers the default `auth.mode=offline` just works.

To deploy outside dev: `./gradlew build` → drop `build/libs/mezzosopranoclef-0.1.0.jar` + Fabric API
into a Fabric `mods/` folder.

## Running multiple bots (a fleet)

Minecraft is one-client-per-JVM (`MinecraftClient.getInstance()` is a global singleton), so
multiple accounts means multiple **processes** — you can't run two clients in one JVM. The
`runFleet` task launches a handful of fully independent bots from one command, each with its own
gameDir (`run-fleet/<name>/`), config, control-plane port, auth-token cache, and identity:

```bash
./gradlew runFleet -Pbots=alice,bob,carol            # 3 offline bots
./gradlew runFleet -Pcount=3                          # same, auto-named ClefBot1..3
./gradlew runFleet -Pbots=a,b -Pserver=play.example.com:25565   # all auto-connect
./gradlew runFleet -Pbots=a,b -Pdashboard=true        # per-bot web dashboards
./gradlew runFleet -Pbots=a,b -PdryRun                # print the launch plan, start nothing
```

Each bot `i` (0-based) gets control port `wsPortBase + i` (default base `8731`) and, with
`-Pdashboard=true`, dashboard port `dashPortBase + i` (default base `8831`). Output is prefixed
`[name]`, and Ctrl-C stops the whole fleet. Per-bot dirs live under `run-fleet/` (gitignored).

| `-P` flag | Default | Meaning |
|---|---|---|
| `bots` | — | Comma-separated names (e.g. `alice,bob`). Required unless `count` is given. |
| `count` | — | Auto-name `ClefBot1..N` instead of an explicit list. |
| `wsPortBase` | `8731` | Control-plane port for the first bot; incremented per bot. |
| `dashPortBase` | `8831` | Dashboard port base (only used with `dashboard=true`). |
| `dashboard` | `false` | Serve a per-bot web dashboard. |
| `server` | — | `host[:port]` to auto-connect all bots to. |
| `auth` | `offline` | `offline` or `microsoft`. |
| `clientId` | — | Azure app id for `auth=microsoft` (see below). |
| `token` | — | Shared control token across the fleet. Omit to keep each bot's own generated one. |
| `dryRun` | — | Print the per-bot launch plan and exit without starting anything. |

**Offline** (the default) is trivial — each name becomes the in-game username. **Microsoft** works
too (`-Pauth=microsoft -PclientId=<azure-app-id>`): because each bot has its own gameDir, each
caches its own refresh token independently. But every bot needs its **own** Microsoft account, and
the first run prints a device-code prompt per bot (watch the `[name]` lines). Note each bot is a
full client JVM, so RAM — not CPU — is the practical ceiling on how many you run at once.

## Run profiles (switch accounts on one bot)

Want several accounts on hand and to switch between them without clobbering each other's config or
tokens? Pass `-Pprofile=<name>` and `runClient` runs in `run-profiles/<name>/` instead of `run/`,
so each profile keeps its **own** config (account + auth mode), its own `auth-cache.json` (refresh
token), and its own logs:

```bash
./gradlew runClient -Pprofile=alice                          # uses run-profiles/alice/
./gradlew runClient -Pprofile=bob -Dmezzoclef.dashboard=true
./gradlew runClient                                          # no profile => default run/ dir
```

First launch of a profile writes `run-profiles/<name>/config/mezzoclef.json` with defaults; set
that profile's account in it, or pass it on the command line (these now forward into the game JVM):
`-Dmezzoclef.auth.mode=microsoft`, `-Dmezzoclef.auth.username=Steve`, etc. A Microsoft profile does
its own one-time device-code sign-in and caches its refresh token under its own dir, so switching
back to an already-authed profile never re-prompts.

This is the *switch-between-accounts* tool (one running at a time). To run **several at once**, give
each a distinct control port (`-Dmezzoclef.ws.port=8741 -Dmezzoclef.dashboard.port=8742`) or use
`runFleet` above. Profiles live under `run-profiles/` (gitignored).

## Build

Requires JDK 21 for the game (Gradle auto-provisions it via the foojay toolchain resolver, so you
don't need it installed — the build was validated with only JDK 22/25 present). Uses **Gradle 9.5.1**
(wrapper included) and **Fabric Loom 1.17.11**.

```bash
./gradlew build           # produces build/libs/mezzosopranoclef-0.1.0.jar
./gradlew runClient       # launches the headless bot in a dev environment
```

Version stack (pinned in `gradle.properties`, verified against fabricmc.net 2026-06-13):

| Component       | Version          |
|-----------------|------------------|
| Minecraft       | 1.21.8           |
| Yarn mappings   | 1.21.8+build.1   |
| Fabric Loader   | 0.19.3           |
| Fabric API      | 0.136.1+1.21.8   |
| Loom            | 1.17.11          |
| Gradle          | 9.5.1            |

To deploy into an existing launcher (Prism/MultiMC/etc.), drop the built jar + Fabric API into
`mods/`.

---

## Configuration

First run writes `config/mezzoclef.json`. Anything can be overridden by a `-Dmezzoclef.*` JVM flag.

```jsonc
{
  "auth": {
    "mode": "offline",                 // "offline" or "microsoft"
    "offlineUsername": "ClefBot",
    "azureClientId": "fec2c6a8-e025-42d8-8b6c-364fb09d8acb",  // bundled app; override with your own (see below)
    "tokenCacheFile": "mezzoclef/auth-cache.json"
  },
  "connection": {
    "autoConnect": false,              // auto-join after auth?
    "serverHost": "localhost",
    "serverPort": 25565
  },
  "control": {
    "enabled": true,
    "host": "127.0.0.1",
    "port": 8731,
    "authToken": "<generated>",        // required by default; send via the hello command
    "allowedOrigins": "",              // comma-separated extra browser origins for WebSocket
    "dashboard": false,                // serve the built-in browser dashboard
    "dashboardPort": 8732
  },
  "screenshot": {
    "backend": "software",             // "software" (GPU-free, default) or "gl" (needs a GL context)
    "defaultWidth": 1280, "defaultHeight": 720, "defaultFov": 70.0,
    "maxWidth": 3840, "maxHeight": 2160,
    "maxPixels": 8294400,
    "maxRayDistance": 64,              // software: capture cube half-size + max ray length (blocks)
    "maxEntities": 64                  // software: max nearby entities drawn per capture
  },
  "headless": true,
  "headlessLoopSleepMs": 20            // idle in-world CPU pacing (higher = less CPU, more latency)
}
```

Handy overrides: `-Dmezzoclef.headless=false`, `-Dmezzoclef.ws.port=9000`,
`-Dmezzoclef.auth.mode=microsoft`, `-Dmezzoclef.auth.username=Steve`,
`-Dmezzoclef.connect.auto=true`, `-Dmezzoclef.connect.host=...`, `-Dmezzoclef.screenshot.backend=gl`.

---

## Microsoft auth setup (one-time)

MezzoSopranoClef ships with a bundled public-client Azure app id, so microsoft mode works out of
the box: set `auth.mode` to `microsoft` and run. (The official launcher's client id, by contrast,
isn't redistributable — hence a project-specific one. A public-client id is not a secret.)

**Prefer your own app** (so you don't share the bundled app's throttling/consent)? It's free:

1. Azure Portal → *App registrations* → *New registration*. Account type: *Personal Microsoft
   accounts*.
2. *Authentication* → *Advanced settings* → **Allow public client flows** = **Yes**, then *Save*.
   Skipping this is the #1 failure: without it Azure rejects the device-code flow with
   `AADSTS70002: … must be marked as 'mobile'`.
3. Copy the **Application (client) ID** into `auth.azureClientId`.

On first run the bot logs (and broadcasts over the control plane) a URL + code:

```
================ MICROSOFT SIGN-IN ================
  Visit : https://www.microsoft.com/link
  Code  : ABCD-EFGH
===================================================
```

Authorize on any device. The refresh token is cached (`auth-cache.json`, git-ignored — treat it
like a password) so restarts are silent.

**Offline mode** needs nothing — it derives the vanilla offline UUID from the username and joins
offline-mode servers immediately.

---

## Control plane (WebSocket + JSON)

Connect to `ws://<host>:<port>` (default `127.0.0.1:8731`).

```jsonc
// request
{ "id": "1", "cmd": "status", "args": {} }
// response
{ "id": "1", "ok": true, "result": { ... } }   // or { "ok": false, "error": "..." }
// unsolicited events
{ "event": "auth.prompt", "data": { "verificationUri": "...", "userCode": "..." } }
```

New configs generate `control.authToken` automatically. Send
`{"cmd":"hello","args":{"token":"..."}}` first, or set `CLEF_WS_TOKEN` when using the bundled
Python probes. Browser WebSocket origins are rejected unless they come from the built-in dashboard
or from `control.allowedOrigins`.

### Commands

| Command        | Args                                              | Does |
|----------------|---------------------------------------------------|------|
| `ping`         | —                                                 | liveness |
| `help`         | —                                                 | list all commands |
| `status`       | —                                                 | headless/world/player state |
| `auth.status`  | —                                                 | current identity |
| `connect`      | `host`, `port?`                                   | join a server |
| `disconnect`   | —                                                 | leave the world |
| `chat`         | `message` (prefix `/` for a command)              | send chat / run command |
| `look`         | `yaw?`, `pitch?`                                  | aim the player |
| `players`      | —                                                 | tab-list players |
| `headless`     | `enabled?`                                        | toggle render-skip live |
| `screenshot`   | `x? y? z? yaw? pitch? width? height? fov?`        | render PNG → base64 (defaults to player view) |
| `goto`         | `x`, `z`, `y?`                                     | Baritone path |
| `baritone`     | `command`                                          | run any raw Baritone command (full feature set) |
| `nav.stop`     | —                                                 | cancel pathing |
| `nav.status`   | —                                                 | pathfinding availability/state |
| `move`         | `forward? backward? left? right? jump? sprint? sneak? durationMs?` | set continuous movement input |
| `stopMove`     | —                                                 | stop all movement |
| `mine`         | `x, y, z, face?`                                  | break a block over time (survival) |
| `stopMine`     | —                                                 | stop breaking |
| `breakBlock`   | `x, y, z`                                          | instantly break a block (creative) |
| `place`        | `x, y, z, face?`                                  | right-click / place against a face |
| `use`          | `hand?`                                            | use held item / right-click air |
| `attack`       | `entityId?`                                        | attack an entity (or nearest in reach) |
| `setSlot`      | `slot` (0–8)                                       | select hotbar slot |
| `dropItem`     | `all?`                                             | drop the held item/stack |
| `inventory`    | —                                                 | list inventory contents |
| `entities`     | `radius?`                                          | nearby entities (id/type/pos/distance) |
| `blockAt`      | `x, y, z`                                          | block id at a position |
| `interactEntity` | `entityId`, `hand?`                             | right-click an entity (mount/trade/breed/leash) |
| `useHold`      | `ticks?=40`                                        | hold right-click (charge bow, block, fish) |
| `useRelease`   | —                                                 | release a held use (fire bow) |
| `eat`          | `ticks?=40`                                        | eat/drink the held item |
| `swapHands`    | —                                                 | swap main/off-hand |
| `pickBlock`    | `x, y, z`, `nbt?`                                  | middle-click pick a block |
| `respawn`      | —                                                 | respawn after death (auto by default) |
| **`container`**  | —                                               | read the open container: slots, cursor, villager trades |
| **`clickSlot`**  | `slot`, `button?`, `mode?`                      | click a container slot (any vanilla/modded UI) |
| **`closeScreen`**| —                                               | close the open container/screen |
| **`selectTrade`**| `index`                                         | pick a villager trade |
| **`screen`**     | —                                               | list clickable widgets on the current screen |
| **`clickButton`**| `index`                                         | click a screen widget (modded button GUIs) |
| **`setText`**    | `index`, `text`                                 | type into a text-field widget (signs/books/modded) |
| **`serverui`**   | —                                               | read title/subtitle/action-bar, boss bars, scoreboard |
| `findItem`     | `item`                                            | find inventory slots holding an item |
| `equip`        | `item`                                            | shift-click an item (armor/shield auto-equips) |
| `deposit`      | `item`                                            | shift-click items from inventory into the open container |
| `withdraw`     | `item`                                            | shift-click items from the container into inventory |
| `dropStack`    | `item`                                            | throw whole stacks of an item |
| `goto`/`nav.stop`/`baritone` | …                                     | Baritone (bundled) — see below |
| `subscribe`    | `events?:[...]` (omit = all)                       | stream events to this connection |
| `unsubscribe`  | —                                                 | stop streaming events |

### Using vanilla & modded UIs (furnaces, villagers, chests, machines…)

Almost every container UI — vanilla **and modded** — runs on Minecraft's server-synced
`ScreenHandler`/`clickSlot` system, so one generic flow drives them all:

```jsonc
// open it (right-click the block, or interact the entity):
{"cmd":"place","args":{"x":10,"y":64,"z":-3}}        // right-click a furnace/chest/etc.
{"cmd":"interactEntity","args":{"entityId":114}}      // open a villager / wandering trader

{"cmd":"container"}                                   // read every slot + cursor (+ trades)
{"cmd":"clickSlot","args":{"slot":0,"mode":"pickup"}} // pick up
{"cmd":"clickSlot","args":{"slot":3,"mode":"quickMove"}} // shift-click (smelt/craft/deposit)
{"cmd":"selectTrade","args":{"index":0}}              // choose a villager trade
{"cmd":"closeScreen"}
```
This covers chests, furnaces, crafting tables, anvils, enchanting/brewing, villager/wandering-trader
trades, and modded machine GUIs (anything using `ScreenHandler`). For pure-widget modded screens
(buttons/text fields) use `screen` → `clickButton`/`setText`. *Live-verified: opened a wandering
trader and read its 9 trades; read the player inventory's 46 slots.*

### Events

`subscribe` to a list (`{"events":["chat","damage"]}`) or to everything (no args / `["*"]`); then
the server pushes unsolicited `{"event":"...","data":{...}}` frames. `events` lists the full
catalog at runtime.

| Event | Data | Fires when |
|---|---|---|
| `chat` | `text`, `sender?`, `kind` | a chat/system message is received |
| `health` | `health`, `food` | health or hunger changes |
| `damage` | `amount`, `health` | the bot takes damage |
| `death` / `respawn` | — | the bot dies / respawns |
| `join` / `leave` | `name` | a player enters/leaves the tab list |
| `connected` / `disconnected` | — | the bot joins/leaves a server |
| `tick` | `x,y,z,yaw,pitch,health,food,dimension` | throttled state snapshot (~1/s) |
| `screenOpen` / `screenClose` | `screen` | a container/screen opens or closes |
| `entitySpawn` | `id,type,x,y,z` | an entity appears nearby (within 24 blocks) |
| `entityRemove` | `id` | a nearby entity leaves |

The expensive sources (`tick`, `entitySpawn`/`entityRemove`) are only computed when something is
subscribed — idle CPU stays low if no one's listening. `auth.prompt`/`auth.ok`/`auth.error` are
always delivered. Tail the stream with `python3 scripts/events_probe.py` (stdlib-only).

### Example (Python)

```python
import json, base64, websocket   # pip install websocket-client

ws = websocket.create_connection("ws://127.0.0.1:8731")
def call(cmd, **args):
    ws.send(json.dumps({"id": "1", "cmd": cmd, "args": args}))
    return json.loads(ws.recv())

print(call("connect", host="play.example.com"))
print(call("chat", message="hello from a corpse"))

shot = call("screenshot", yaw=180, pitch=10, width=1920, height=1080)
open("shot.png", "wb").write(base64.b64decode(shot["result"]["base64"]))
```

---

## Web dashboard

A built-in, dependency-free browser UI (a single static page that talks to the control plane).
Enable it and open the URL:

```bash
./gradlew runClient -Dmezzoclef.dashboard=true     # or set control.dashboard=true in the config
# -> http://127.0.0.1:8732
```

It gives you, in the browser: live status (position/health/dimension/current screen, auto-refresh),
**WASD keyboard control** + look, mine/break/place/use/attack/hotbar/drop, server connect/chat,
Baritone `goto` + a **raw Baritone command box**, a **screenshot button that renders the capture
inline**, a raw-command console, and a streaming **event log** (chat/health/join/leave/death).

Served on `control.dashboardPort` (default 8732), bound to `control.host` (default `127.0.0.1` =
localhost only). The page connects back to the WebSocket control plane on `control.port`; paste
the generated `control.authToken` into the token field before sending commands.

---

## Testing

```bash
./gradlew test        # 48 unit + integration tests, no Minecraft client required
```

Covers (all green, GPU-free):

- **WebSocket codec** — RFC 6455 accept-key vector, masked frames, fragmentation, ping/pong, close,
  and oversized-frame rejection (`WsConnectionTest`).
- **Control plane end-to-end** — a real `ControlServer` on an ephemeral port over an actual
  WebSocket handshake: `ping`/`help`/unknown-command **and event-subscription filtering with two
  clients** (`ControlServerIntegrationTest`).
- **Microsoft auth flow** — the entire device-code → XBL → XSTS → MC → profile chain (plus refresh
  and error paths) against a local mock HTTP server (`MicrosoftAuthFlowTest`).
- **Auth** — offline UUID determinism + v3, MSA UUID handling, empty-client-id rejection.
- **Config** — defaults, round-trip, `-D` overrides.
- **CPU renderer** — Minecraft-accurate look vectors, ground/sky, **entity hits + occlusion**,
  dimensions, PNG round-trip; plus extracted `MovementMath` and `SnapshotBounds`
  (`SoftwareRaycasterTest`, `MovementMathTest`, `SnapshotBoundsTest`, `PngEncoderTest`).

### Live actuation verification

`scripts/verify_live.py` drives a running bot and asserts the things that can't be unit-tested:
movement (position delta), block break/mine (turning to air), hotbar selection, inventory/entity
queries, chat events, and a GPU-free screenshot.

### End-to-end against a real Minecraft server

`scripts/e2e.sh` boots an actual **1.21.8 server** (offline mode, flat creative world), launches
the bot auto-connecting in offline mode, then runs a stdlib-only WebSocket probe
(`scripts/ws_probe.py`) that waits for the bot to enter the world, checks the tab-list contains
it, and takes a **GPU-free screenshot**, asserting it's a valid PNG.

```bash
# Needs a JDK 21+ for the server (set SERVER_JAVA if `java` isn't 21+), Python 3, and network.
SERVER_JAVA=/path/to/jdk21/bin/java ./scripts/e2e.sh
# artifacts: e2e/e2e-shot.png, e2e/bot.log, e2e/server/server.log
```

The server boot + offline join were validated here (the real 1.21.8 server reached `Done` in ~5s
on JDK 21). The bot-client launch goes through `gradlew runClient`, whose **first run downloads
Minecraft assets** (hundreds of MB) and needs a GL context to boot (see below) — so budget time
and run it on a real host or CI.

---

## Running truly headless (no display / no GPU)

Two distinct concerns:

1. **Screenshots** need no GPU at all — the default `software` backend rasterizes on the CPU.
2. **Booting Minecraft** still initialises a GL context (the blaze3d `GpuDevice`), so the client
   itself needs *some* OpenGL even though we never draw a frame.

- **macOS / Windows / Linux-with-display:** works out of the box — GLFW makes a hidden window.
- **Linux servers with no display/GPU:** run under a virtual framebuffer with Mesa's software
  rasterizer (llvmpipe) — no GPU required:
  ```bash
  LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClient
  ```
  With the `software` screenshot backend, the captures themselves involve zero GPU work; the GL
  context exists only to satisfy the engine's boot.

**No Dock icon / app on macOS.** In headless mode the client flips its `NSApplication` activation
policy to *Accessory*, so it does **not** show a Dock icon or app-switcher entry and never steals
focus (verified: `lsappinfo` reports `type="UIElement"`). It's a true background process.

**Audio is muted** in headless mode (master volume → 0). We don't tear down the sound engine —
that crashes gameplay sounds on a half-initialised OpenAL — we just silence it; on an audio-less
server Minecraft disables sound on its own anyway.

---

## Baritone (bundled, automatic)

**This mod does not reimplement Baritone** — it bundles the real thing and drives it. Baritone
**1.15.0** (for MC 1.21.6–1.21.8) is **auto-downloaded from GitHub releases and bundled into the
jar (jar-in-jar)** by the build, so there's nothing to install manually. Toggle with
`-Pbundle_baritone=false` / `bundle_baritone` in `gradle.properties`; pin a version with
`baritone_version`. The bot drives it **reflectively** (no compile dependency), so it still builds
and runs fine if you disable it; `nav.status` reports whether it was detected.

> Implementation notes (learned the hard way, verified live): we use the **`unoptimized-fabric`**
> build, not `standalone` — the standalone build obfuscates the public `baritone.api.*` package,
> which breaks reflective integration. Baritone also bundles a native elytra lib
> (`nether-pathfinder`); in production it loads via JiJ, and for `gradlew runClient` (dev) it's
> added explicitly so Baritone's startup doesn't `NoClassDefFoundError`.

You get Baritone's **full feature set** via the `baritone` command (raw passthrough to its command
manager) — or the dashboard's command box:

```jsonc
{ "cmd": "baritone", "args": { "command": "mine diamond_ore" } }
{ "cmd": "baritone", "args": { "command": "goto 100 64 -200" } }
{ "cmd": "baritone", "args": { "command": "follow player Steve" } }   // build / farm / explore / elytra / sel ...
```

`goto`/`nav.stop`/`nav.status` are convenience wrappers over the same bridge, and starting a
Baritone path automatically releases the bot's manual movement input so the two don't fight.
**Live-verified:** Baritone detected, `goto` physically pathed the bot, and raw commands executed
(`[Baritone] Going to: GoalBlock{...}`).

For the things Baritone deliberately *doesn't* do (inventory/containers), use the built-in helpers:
`findItem`, `equip`, `deposit`, `withdraw`, `dropStack` (shift-click logic over `clickSlot`).

---

## Project layout

```
dev.mezzo.clef
├── MezzoClef / ClefClient / ClefPreLaunch   entrypoints (main / client / preLaunch)
├── config        ClefConfig (JSON + -D overrides)
├── headless      HeadlessController (render-skip state + loop pacing)
├── auth          MSA device-code flow, offline UUID, token cache, session injection
├── api           WebSocket control plane, command dispatcher, core commands
│   └── ws        hand-rolled RFC6455 server (zero external deps)
├── bot           ServerConnector, InputController + BotInput + MovementMath, ActionManager
├── nav           Navigator + reflective BaritoneNavigator
├── render        VoxelView, ArrayVoxelView, SoftwareRaycaster, EntityBox, SnapshotBounds,
│                 PngEncoder, WorldSnapshotter (GPU-free CPU screenshot backend — pure + tested)
├── screenshot    ScreenshotService (software default backend + optional GL backend)
└── mixin.client  headless (draw-skip)/window/camera/accessor mixins

src/test/java   48 JUnit tests (pure logic, mock-HTTP auth flow, in-process control-plane)
scripts         e2e.sh, fetch-server.sh, ws_probe.py, events_probe.py, verify_live.py
```

## License

MIT © 2026 Solenopsisbot. **MezzoSopranoClef** — made by **Solenopsisbot**.
