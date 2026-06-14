# MezzoSopranoClef

*by Solenopsisbot*

A **headless Minecraft bot client** for **Minecraft 1.21.8**, built as a Fabric mod. It runs the
*real* game client — so client-side Fabric mods and Baritone Just Work — but rips out the window,
audio, and rendering. By default it **never even creates an OpenGL context**, so it runs anywhere:
any server, no GPU, no display. You drive it over a simple **WebSocket/JSON API** (or a built-in web
dashboard), and it can still take real screenshots of the world on demand, GPU-free.

Think: *Baritone's brain in a client that's asleep until you poke it.*

**What you get**

- **Full player control over WebSocket/JSON** — move, mine, place, attack, use items, manage your
  inventory, and drive any vanilla/modded container UI (chests, furnaces, villager trades…), plus a
  live event stream (chat, health, deaths, entities…).
- **Baritone** bundled and driven for you — pathfinding, mining, following, building.
- **GPU-free screenshots** at any position / angle / resolution (a CPU raycaster; no GPU needed).
- **Microsoft (online) and offline auth**; run one bot or a whole **fleet** of them.
- A built-in **web dashboard** so you can click around without writing any code.

**What it is *not*:** a protocol/packet bot (it runs the real client, not an emulator), a server or
proxy, or a hacked-client GUI. Running the real client is the whole point — it's what lets mods and
Baritone work and screenshots be real.

**Requirements:** just a JDK (21+) — Gradle auto-provisions the right one if you don't have it.
Runs on macOS, Windows, and Linux, including headless servers with no display and no GPU.

## Contents

- [Quick start](#quick-start) — get a bot running in one command
- [Run without Gradle (launcher jar / Docker)](#run-without-gradle-standalone-launcher--docker) — pre-built, `java -jar` or `docker run`
- [Configuration](#configuration) · [Control plane API](#control-plane-websocket--json) · [Commands](#commands) · [Events](#events) · [Web dashboard](#web-dashboard)
- [Running headless (no GPU / no display)](#running-truly-headless-no-display--no-gpu) · [Performance & footprint](#performance--footprint)
- [Multiple bots (fleet)](#running-multiple-bots-a-fleet) · [Account profiles](#run-profiles-switch-accounts-on-one-bot) · [Baritone](#baritone-bundled-automatic)
- [Microsoft auth setup](#microsoft-auth-setup-one-time) · [Build & versions](#build) · [Testing](#testing) · [Project layout](#project-layout)

---

## Why it's built this way

You asked for two things that **force** the architecture:

1. **Fabric mod support**, and
2. **Real screenshots of the world at any angle/resolution.**

Both require running the actual Minecraft client. A protocol-level bot (Mineflayer, etc.) can't
load client mods and has no real renderer, so it's off the table. Instead we run the real
client and:

- **Skip the render loop** — `MinecraftClientHeadlessMixin` cancels the world/GUI draw every frame
  while headless. Game ticks, networking, physics and entity tracking keep running. It also paces
  the loop (the render path is normally what throttles it) so idle CPU stays near zero.
- **Never touch the GPU** — by default (`noGl`) `RenderSystemNoGlMixin` installs a stub
  `GpuDevice` in place of Minecraft's `GlBackend`, so the client **boots without ever creating an
  OpenGL context.** No GPU, no drivers, no llvmpipe — on any platform. On a Linux host with no
  display it also boots on the GLFW *null platform*, so **no `xvfb` either**.
- **Hide the window** — `WindowMixin` keeps a GLFW window created but never shows it (or skips it
  entirely on the null platform). With `noGl` the window's GL context is never even made current.
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

**Verified by the test suite (`./gradlew test`, 63 tests, all green):** the RFC 6455 WebSocket
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

**Validated live with `noGl` (GPU-free boot, macOS):** the client boots all the way to the title
screen with **OpenGL never initialised** — the full resource reload (atlas stitch, model bake) and
the loading-screen render run through the stub `GpuDevice`, the overlay dismisses itself, and the
control plane stays responsive (`ping`/`status`/`nav.status`), with Baritone loaded and the
screenshot backend on `software`. See *Running truly headless* below.

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
boss bars, title), Baritone pathing, a live event stream, and GPU-free screenshots. Benchmarked on
a real generated overworld: **~7% of one core standing still, ~24% while Baritone is actively
pathing, ~300–375 MB live heap** — see [Performance & footprint](#performance--footprint). The floor
is the unavoidable 20 TPS simulation, so you can run several bots per core.

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

## Run without Gradle (standalone launcher / Docker)

Don't want Gradle/Loom on the box you run the bot on? Two pre-built options do exactly what
`runClient` does — download Minecraft + Fabric on first run and launch the headless bot — with no
dev environment. Minecraft is **not** bundled in either (it isn't redistributable); it downloads
once into the game directory and is cached.

### Self-bootstrapping launcher jar

```bash
./gradlew :launcher:jar         # -> launcher/build/libs/mezzosopranoclef-launcher-<ver>.jar
java -jar mezzosopranoclef-launcher-0.1.0.jar
```

On first run it fetches the official Minecraft client + libraries + assets (Mojang) and the Fabric
loader + intermediary mappings (Fabric meta), drops the bundled mod + Fabric API into `mods/`, and
spawns the client with the GPU-free / headless flags. Knobs (env vars):

- **`CLEF_GAMEDIR`** (default `./mezzosopranoclef`) — where MC, config, and the auth cache live.
- **`CLEF_SKIP_SOUNDS`** (default `true`) — audio is muted, so sound assets are skipped; that's
  ~95% of the asset download (4085 of ~4270 objects), so first run is quick. Set `false` to fetch them.
- **`CLEF_MAX_HEAP`** (`768m`), **`CLEF_BG_THREADS`** (`4`) — same footprint caps as `runClient`.
- **`CLEF_OPTS`** — space-separated extra flags, e.g.
  `CLEF_OPTS="-Dmezzoclef.connect.auto=true -Dmezzoclef.connect.host=play.example.com"`.
  Any `-Dmezzoclef.*` passed straight to the launcher is forwarded to the bot too.

### Docker

```bash
docker build -t mezzosopranoclef .
docker run --rm -it -p 8731:8731 -v clef-data:/data \
  -e CLEF_OPTS="-Dmezzoclef.ws.host=0.0.0.0" mezzosopranoclef
```

The final image is just a JRE + the launcher (no Minecraft baked in); MC downloads into the `/data`
volume on first run. Bind the control plane to `0.0.0.0` (above) to reach it from the host — it
stays token-protected (token generated into `/data/config/mezzoclef.json`). Mount `/data` to
persist the MC download, config, and auth cache across runs.

The image is pinned to **`linux/amd64`**: Mojang ships LWJGL natives for x86_64 (and macOS) but
**not linux-arm64**, so the game can't start on arm64 Linux. On an Apple Silicon host it runs under
emulation (fine for a sim-bound bot); on a normal amd64 server it's native. A harmless
`flite`/narrator (text-to-speech) warning is logged at startup and ignored.

> **Verified end-to-end.** *Launcher jar (macOS):* `java -jar` → downloads MC + 84 de-duplicated
> libraries + (non-sound) assets, installs the mods, boots to a responsive control plane, OpenGL
> never initialised. *Docker (`linux/amd64`):* the container boots on the GLFW **null platform**
> (`noWindow: true` — no display, no GPU, no xvfb) and the control plane (`status`/`ping`) answers
> from the host across the port mapping, with Baritone loaded and the software screenshot backend.

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
  "headlessLoopSleepMs": 20,           // idle in-world CPU pacing (higher = less CPU, more latency)
  "noGl": true,                        // boot with a stub GPU device — never create/touch an OpenGL context
  "windowMode": "auto"                 // "auto" | "none" (GLFW null platform, no display) | "hidden"
}
```

Handy overrides: `-Dmezzoclef.headless=false`, `-Dmezzoclef.ws.port=9000`,
`-Dmezzoclef.auth.mode=microsoft`, `-Dmezzoclef.auth.username=Steve`,
`-Dmezzoclef.connect.auto=true`, `-Dmezzoclef.connect.host=...`, `-Dmezzoclef.screenshot.backend=gl`,
`-Dmezzoclef.nogl=false` (boot with real OpenGL), `-Dmezzoclef.window=none` (force the no-display GLFW null platform).

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
./gradlew test        # 63 unit + integration tests, no Minecraft client required
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
- **Config** — defaults, round-trip, `-D` overrides (incl. `noGl` / `windowMode`).
- **No-GL window-mode decision** — the pure logic that picks hidden window vs the GLFW null
  platform per OS / display / GPU-free state (`NoGlModeTest`).
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

**This is the default — no GPU, and (on a displayless Linux host) no `xvfb`.** In headless mode the
client boots with a stub GPU device (`noGl: true`) and **never creates or touches an OpenGL
context.** `RenderSystemNoGlMixin` intercepts the one call site that builds Minecraft's `GlBackend`
(whose constructor is what does `glfwMakeContextCurrent` + `GL.createCapabilities()` and starts
hitting the driver), installs a no-op `NoGlDevice` in its place, and cancels. Textures/buffers/
pipelines become pure metadata, uploads are dropped, draws do nothing. The default `software`
screenshot backend rasterizes on the CPU and never goes through the device, so captures still work
with **zero GPU**.

Two layers, both automatic:

- **No GPU (`noGl`, default on):** works on **any** platform — macOS, Windows, Linux with or
  without a GPU. No drivers, no Mesa/llvmpipe.
- **No display (`windowMode: "auto"`, default):** drops the window entirely via the GLFW *null
  platform* on a Linux host with **no `DISPLAY`** — so you don't need `xvfb` either. On a desktop or
  macOS it keeps a cheap hidden window instead. Force it anywhere with `windowMode: "none"` (or
  `-Dmezzoclef.window=none`).

So on a bare Linux server you just run — no virtual framebuffer, no software GL, no GPU:
```bash
./gradlew runClient        # boots straight to a headless title screen
```

**Verified live** (macOS, `noGl`): boots to `TitleScreen` with the resource reload completed
(`overlay: none`), control plane responsive (`ping`/`status`/`nav.status` all answer), Baritone
detected, screenshot backend forced to `software` — with OpenGL never initialised and the render
loop skipping every frame (`skippedFrames` climbing). The atlas stitch + model bake + loading-screen
render all run through the stub device with no GL. `status` reports the live mode:
`{"noGl": true, "noWindow": false, ...}`.

**Extra efficiency when not rendering.** Because nothing is ever drawn, no-gl mode also drops
texture **mipmaps** (`Mipmap Levels: OFF`) — they only exist for GPU sampling at distance — which
skips all mipmap generation during the atlas stitch and the extra per-sprite pixel data. The base
textures, sprite UVs and dimensions stay intact, so every feature that reads them still works.
(`mixin.client.MinecraftClientNoGlMixin` is the home for these "draw-only data we can skip" tweaks.)

> **Want vanilla-fidelity `gl` screenshots instead?** Set `screenshot.backend=gl`. That physically
> needs a real GL context, so it automatically keeps OpenGL on (disables `noGl`) and a hidden
> window — and on a displayless Linux box you'd then still need `xvfb` + llvmpipe for that one
> feature. The GPU-free `software` backend remains the default and the recommended path.

> **macOS note.** The GLFW null platform (`windowMode: "none"`) stalls on macOS (GLFW must own the
> main thread there), so it is never selected on macOS — `auto` and even an explicit `none` fall
> back to a hidden window (still fully GPU-free). The null platform is for Linux/CI hosts with no
> display. On macOS, GPU-free + hidden window is the verified path.

**No Dock icon / app on macOS.** In headless mode the client flips its `NSApplication` activation
policy to *Accessory*, so it does **not** show a Dock icon or app-switcher entry and never steals
focus (verified: `lsappinfo` reports `type="UIElement"`). It's a true background process.

**Audio is muted** in headless mode (master volume → 0). We don't tear down the sound engine —
that crashes gameplay sounds on a half-initialised OpenAL — we just silence it; on an audio-less
server Minecraft disables sound on its own anyway.

---

## Performance & footprint

Benchmarked on a 10-core machine, headless + no-GL, against a **real generated overworld**
(view-distance 10) — not a flat test world:

| State | CPU (one core) | Live heap (post-GC) |
|---|---|---|
| Title screen, idle | ~3% | ~255 MB |
| In a world, standing still | ~7% | ~300 MB |
| Actively pathing (Baritone `goto`) | ~24% | ~340–375 MB |

Worker threads are capped at 4; committed heap peaked ~600 MB while exploring (under the 768 MB cap).

What that means:

- **CPU is simulation-bound.** The render thread is *asleep* in the pace loop — no draw, no buffer
  swap, no GLFW poll — so the cost is just Minecraft's unavoidable 20 TPS world tick + networking.
  Standing still is ~7% of a core; running a Baritone task is ~24% (pathfinding + chunk processing).
  Raising `headlessLoopSleepMs` does **not** lower it (measured), so you can run several bots per core.
- **No GPU memory, no render scratch.** No-GL means zero textures/meshes/framebuffers on the GPU,
  and render-skip means **no chunk meshing** in-world — the per-frame and per-chunk render costs that
  dominate a normal client are simply gone.
- **The live set (~300 MB idle, ~375 MB and climbing while exploring) is mostly *necessary* Minecraft
  data** — registries, the collision shapes Baritone needs, fonts, datafixers, baked models — plus the
  client's chunk cache, which grows with view-distance and explored area. There's no giant waste to
  slash without dropping features. (~10 MB is dev-only Fabric mappings, absent from a deployed jar;
  mipmaps are already dropped.) Heavy explorers / large view-distances want `-PmaxHeap=1g`.

### JVM tuning (baked into `runClient` / `runFleet`, all overridable)

A no-render bot has a small heap, so the launch is tuned for a low, bounded footprint:

| Flag | Why |
|---|---|
| `-Xmx768m` — `-PmaxHeap=` | Cap the heap; a no-render client never needs the ~2 GB a rendering client wants. Bounds per-bot RAM for fleets. |
| `-XX:+UseG1GC -XX:MaxGCPauseMillis=50` | Smooth, sub-tick GC pauses. |
| `-XX:+UseStringDeduplication` | Dedupes the many duplicate registry/NBT strings. |
| `-XX:G1PeriodicGCInterval=15000` | Hands idle heap back to the OS so RSS shrinks while the bot waits. |
| `-Dmax.bg.threads=4` — `-PbgThreads=` | Minecraft sizes its worker pool to CPU count; a no-render bot needs few, and this stops a fleet of N bots spawning N×(cores−1) threads. |

Tune at launch — e.g. a dense fleet on a small box:

```bash
./gradlew runFleet -Pbots=a,b,c,d -PmaxHeap=512m -PbgThreads=2
```

> **Measuring RAM honestly:** these flags cut the JVM's *committed heap* (≈545 → ≈450 MB here) and
> bound the ceiling, but **macOS RSS barely moves** — macOS keeps `MADV_FREE`'d pages counted as
> resident until there's memory pressure, so RSS over-reports there. On Linux (the deploy target)
> the idle uncommit + cap translate straight into lower RSS. Measure with committed heap
> (`jcmd <pid> GC.heap_info`) or on a Linux host for a faithful number.

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
├── MezzoClef / ClefClient / ClefPreLaunch   entrypoints (main / client / preLaunch; preLaunch resolves no-gl/window mode)
├── config          ClefConfig (JSON + -D overrides; noGl, windowMode)
├── headless        HeadlessController (render-skip + no-gl/no-window state + loop pacing)
│   └── nogl        NoGlDevice — GPU-free GpuDevice/CommandEncoder/buffer/texture/pass stubs
├── auth            MSA device-code flow, offline UUID, token cache, session injection
├── api             WebSocket control plane, command dispatcher, core commands
│   └── ws          hand-rolled RFC6455 server (zero external deps)
├── bot             ServerConnector, InputController + BotInput + MovementMath, ActionManager
├── nav             Navigator + reflective BaritoneNavigator
├── render          VoxelView, ArrayVoxelView, SoftwareRaycaster, EntityBox, SnapshotBounds,
│                   PngEncoder, WorldSnapshotter (GPU-free CPU screenshot backend — pure + tested)
├── screenshot      ScreenshotService (software default backend + optional GL backend)
└── mixin.client    render draw-skip + loop pacing, window hide/swap-skip, camera, accessors, and:
                    RenderSystemNoGlMixin (stub device + GLFW null platform),
                    MinecraftClientNoGlMixin (drop mipmaps when not rendering)

src/test/java   63 JUnit tests (pure logic incl. no-gl window-mode decision, mock-HTTP auth, in-process control-plane)
scripts         e2e.sh, fetch-server.sh, ws_probe.py, events_probe.py, verify_live.py

launcher/       standalone bootstrap module (Launcher.java): downloads MC + Fabric and spawns the
                client — bundles the mod + Fabric API, depends only on gson. Builds the
                mezzosopranoclef-launcher jar. (Dockerfile at the repo root builds on it.)
```

## License

MIT © 2026 Solenopsisbot. **MezzoSopranoClef** — made by **Solenopsisbot**.
