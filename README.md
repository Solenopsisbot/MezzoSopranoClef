# MezzoSopranoClef

Headless Minecraft client for automation. It runs the real Fabric client, keeps networking, physics, mods, and Baritone active, and exposes control over WebSocket/JSON. Rendering, windows, audio, and screenshots are optional; the default screenshot backend is a CPU raycaster. One build joins servers on **every release from 1.12.2 through 26.2** (see [Server versions](#server-versions)).

## What it does

- Connects to offline or Microsoft-authenticated servers running any Minecraft release from 1.12.2
  to 26.2 (protocol translation via ViaFabricPlus).
- Drives movement, mining, placing, crafting, combat, inventory, containers, trades, and timed item use.
- Fights on its own clock — `shootAt` tracks a target every tick, solves lead and arrow drop, and
  aims on the release tick; `meleeWhile` swings on the attack cooldown and resolves the
  damageable part of a multi-part entity.
- Answers bulk world questions — `findBlocks` over the chunk cache (block ids or `#tags`), `blocksIn`
  for a dense cuboid, `target` for what's under the crosshair — instead of one block per round-trip.
- Streams chat, health, death, entity, block-update, pickup, weather, time, sleep and navigation events.
- Runs Baritone pathing when its compatible jar is bundled, and reports arrival or failure as an event.
- Boots without a display or OpenGL context (`noGl`) and captures PNG screenshots on demand, including
  an orthographic top-down map and annotated captures with on-screen entity boxes.
- Includes a browser dashboard plus Python and TypeScript clients.

## Requirements

The client is built against **Minecraft 26.2**, Fabric, and **Java 25**, using Mojang's official names (26.1+ ships unobfuscated; there are no Yarn mappings any more). A newer Minecraft release is not a properties-only upgrade: Fabric API, Baritone, ViaFabricPlus, mixins, launcher metadata, and live tests must move together. Older *servers* need no port at all; see [Server versions](#server-versions).

## Run

```bash
./gradlew :runClient
./gradlew :runClient -Dmezzoclef.dashboard=true
python3 scripts/ws_probe.py
```

The leading colon matters: every version module defines a `runClient` of its own, so the bare name launches all two dozen clients at once and they fight over the control port.

The first run downloads Minecraft assets. Configuration is written to `run/config/mezzoclef.json`. Set `connection.autoConnect` and `connection.serverHost`, or pass `-Dmezzoclef.connect.auto=true -Dmezzoclef.connect.host=host:port`. `connection.serverVersion` (or `-Dmezzoclef.connect.version=`) picks the protocol: `auto` (default, ping and match), `native`, or a release such as `1.12.2`.

Standalone and Docker builds are available with `./gradlew build :launcher:jar` and `docker build -t mezzosopranoclef .`.

## API

Connect to `ws://127.0.0.1:8731` and send JSON commands such as `{"cmd":"ping","args":{}}` or
`{"cmd":"status","args":{}}`. The authoritative command and event contract is
[clients/schema.json](clients/schema.json) — 73 commands, 33 events, protocol 2. Keep the socket on
localhost unless it is behind TLS and configured with `control.authToken`.

A tour of the parts that aren't obvious from the schema:

| Want to | Use |
|---|---|
| Find a tree, an ore, any log | `findBlocks {ids:["#minecraft:logs"], radius:32}` — one call, scans the loaded chunk cache |
| Read a build site | `blocksIn {minX..maxZ}` — palette + base64 varint indices, x-major, capped at 64³ |
| Make something | `craftable`, `recipes {item}`, then `craft {item, count}` — uses the open crafting screen, else the 2×2 player grid |
| Know when you arrived | `goto {x,y,z,reach}` then the `nav.done` / `nav.failed` events; `nav.check` answers "could I?" without moving |
| Know if the block broke | `mine {..., wait:true}`, or subscribe to `mineDone` — `cant_break` covers bedrock, the wrong tool, and giving up |
| Know if the block was placed | `place {x,y,z,item?}` verifies the world changed instead of assuming the click worked |
| Fight something | `entities {kinds:["zombie"]}` carries health, hostility, held item, per-tick motion and whether it's looking at you; `entityHurt` names the attacker |
| Actually hit it | `shootAt {entityId, shots}` and `meleeWhile {entityId, maxMs}` — the loop runs on the bot, not over the socket; `combat.stop` takes the body back |
| Put armour on | `equip {item}` — already worn is `{changed:false}`, not an undress; `changed` is read back from the equipment slot, so a shift-click that couldn't equip says so instead of claiming success |
| Get rid of a screen | `closeScreen` names what was open and reports whether it *stayed* closed — `{closed:false, stillOpen:"death"}` beats a hardcoded success |
| See the layout | `screenshot {mode:"topdown", radius:64}` for an orthographic map (east-right, north-up) |
| Label a screenshot | `screenshot {annotate:true}` also returns the camera and each visible entity's screen-space box |
| Save round-trips | `batch {commands:[{cmd,args}...]}` runs them in order and returns every result |
| Read the sky honestly | screenshots use the real biome/time-of-day tint on 1.14.4–1.21.8; **on 1.21.11 and 26.2 the sky is a fixed daylight blue** — see below |
| Read Baritone's own output | `baritone {command}` returns the lines it printed, and `baritone.log` streams them — otherwise they go to a chat HUD a headless bot doesn't have |
| Know which screen you're on | `status.screen` is a stable name (`title`, `death`, `pause`, `container`, …), not the obfuscated class; the raw one is in `screenClass` |

Event delivery is opt-in per connection (`subscribe`), and every non-trivial producer checks whether
anyone is listening before doing the work — an unsubscribed bot pays close to nothing for the
stream. Tune the noisy ones under `events` in the config (`blockUpdateRadius`, `packetRadius`,
`chatHistory`); bulk-query limits live under `queries`.

Tokens have scopes: a `readOnlyAuthToken` can run the observing commands (`status`, `findBlocks`,
`blocksIn`, `target`, `registry`, `recipes`, `craftable`, `nav.check`, `screenshot`, …) and nothing
that actuates. `batch` applies the same check to each sub-command, so it can't be used to launder
privilege.

**Set a token even on localhost.** A running bot is a fully actuating body; anything else on the
machine can open a socket to it. A fresh config generates a token for exactly this reason, and the
test harnesses now generate a per-run one rather than leaving the port open.

### Why combat is a command and not a script

Every other command answers in one round trip. Aiming a bow doesn't. Doing it from the far end of
the socket costs four round trips per arrow — sample, draw, sample again, aim, release — about a
second and a half, against a world that moves twenty times a second. Measured against an ender
dragon: hundreds of arrows, fifty-four spent shafts on the ground at once, and three points of
damage in ten minutes.

So `shootAt` and `meleeWhile` own the loop. They track the target every tick, solve the lead from
the motion the client actually observed (not `getVelocity()`, which is zero for anything the server
drives), solve the drop by simulating the real arrow — drag included, which the usual `10·t²` rule
leaves out and which makes every long shot land low — and push the rotation to the server on the
release tick, because the release packet carries no rotation of its own. `meleeWhile` resolves the
*part* of a multi-part entity: the parent ender dragon ignores damage outright, so `/damage` on it
reports "Applied 5.0" and changes nothing.

These are the only commands that deliberately block for seconds. Pass `wait:false` to get control
straight back and take the outcome from the `combatDone` event instead, and use `combat.stop` — from
any connection — when a reflex needs the body.

Both ship on every target in the matrix, back to 1.14.4. Two reductions on the older ones, because
the API is not there: releases before 1.21.4 have no `Level.dragonParts()`, so a dragon *part* id
cannot be looked up from the level (pass the dragon's own id, which is what `entities` reports —
the part to hit is still resolved internally); and the pre-1.20 modules carry no `Hotbar` helper, so
the bow must already be on the hotbar rather than being swapped in from the bag. `combatDone` needs
the shared `EventEmitter`, which those modules also predate, so there the command result is the
only report — it is complete either way.

### Two things worth knowing about Baritone

`goto <block>`, `mine <block>` and anything else parsing a block argument used to **deadlock the
client permanently**: Baritone computes the block's drops through a stub server level whose
registries come from a future scheduled onto the client thread and then joined — from the client
thread. Nothing after that ever ran. The bot now resolves that future on a worker at world join, so
those commands are safe; `nav.status.blockArguments` reports `ready` when they are, and the
`baritone` command refuses rather than risking it if the warm-up failed.

Navigation reports completion exactly once. `nav.done` and `nav.failed` are **terminal** — no
`nav.done` can follow a `nav.failed` for the same goal. Baritone's non-terminal path events
(a mid-route recalculation, a spliced segment) arrive as `nav.progress` instead, because reporting
them as failures made callers abandon goals the bot went on to reach.

### The pause screen a headless client can never dismiss

Vanilla pauses the game when the window loses focus. A bot has no window to focus — under
`noWindow` there is not even a display server — so that check can only ever answer "unfocused",
and it re-runs every frame: the pause screen comes back faster than any `closeScreen` can clear
it, and Minecraft skips the whole input path while a screen is up, so movement, mining and the
bow all stop at once. The client now pins `pauseOnLostFocus` off whenever it is headless
(`InputController`), which is cheap and removes the failure mode outright.

Today it is latent rather than live, and the reason is worth writing down because it is one line
away from changing. On **26.2** the check runs in `Minecraft.pauseIfInactive()`, called from
`renderFrame` — which headless still executes — and is held off only by `Window.focused` being
initialised `true` and never receiving a GLFW focus callback a hidden window doesn't get. On
**1.14.4 – 1.21.11** it lives in `GameRenderer.render`, which the headless mixin skips outright.

Relatedly, `closeScreen` no longer reports `{closed:true}` and hopes. It reads the screen back
after the attempt, so a screen that refuses to go — the death screen while the player is dying,
the title screen with no level, anything that re-opens itself — comes back as
`{closed:false, stillOpen:"death"}` instead of a success the caller can't check.

## Authentication

Offline mode works immediately for offline-mode servers. Microsoft mode uses device-code OAuth and stores refresh credentials in the configured cache file. Never commit tokens.

## Verification

```bash
./gradlew clean build --no-daemon
git diff --check
cd clients/typescript && npm ci && npm test
cd ../.. && scripts/e2e.sh
python3 scripts/verify_live.py
python3 scripts/verify_events.py
scripts/verify_versions.sh            # one client vs. a real server of every protocol era
```

The E2E scripts start a real local server, boot a real client, exercise control and world actions, and verify a software-rendered screenshot. `scripts/e2e.sh` takes `MC_VERSION=<server release>` to run the same smoke test against an older server; `scripts/verify_versions.sh` boots the client once and walks it through a whole list of server versions (`--all` for every release, `--auto` to exercise auto-detection), then `scripts/update_version_matrix.py` folds the result into the matrix file.

## Release

Set `mod_version` in `gradle.properties`, run the checks, then tag and push (the workflow publishes jars and a GHCR image):

```bash
git tag v0.1.0 && git push origin v0.1.0
```

## Layout and license

`src/main/java/dev/mezzo/clef` contains the client, API, bot, auth, navigation, mixins, and renderer. `launcher/`, `clients/`, and `scripts/` contain packaging, clients, and probes. MIT; see [LICENSE](LICENSE).

## Version support

There are **two different axes** here, and they solve different problems. Mixing them up is the
single easiest way to be wrong about what this bot can do.

### 1. Server versions — one client, every server (1.12.2 → 26.2)

The default build targets the newest Minecraft release and reaches older *servers* by translating
the protocol client-side with **ViaFabricPlus**. No per-version build is involved.

- **Picking a version.** `connection.serverVersion` (default `auto`) or the `version` argument of
  `connect`: `auto` pings the server and matches it, `native` disables translation, or name a
  release (`1.12.2`, `1.20.4`, …). The `protocol` command lists every joinable release; `status`
  reports the current target.
- **Where ViaFabricPlus comes from.** GPL-3.0, so it is installed *next to* the MIT mod, never
  inside it: `runClient` stages the pinned version into `run/mods/`, and the launcher and Docker
  image fetch it from Modrinth on first run. Without it the bot only joins servers on its own
  version (`-Pwith_viafabricplus=false` / `CLEF_VIAFABRICPLUS=false` opt out).
- **Evidence.** `servers[]` in [`minecraft-versions.json`](minecraft-versions.json) lists all 51
  official releases from 1.12.2 to 26.2, and **all 51 are `verified`** — each was joined for real,
  by one 26.2 client, against a vanilla server of that exact release. Twice over: once naming the
  protocol explicitly, and once with `serverVersion: auto`, which pings the server and picks the
  protocol itself (`autoDetect` in the matrix). Nothing in that table is inferred from a protocol
  table; `scripts/verify_versions.sh --all` reproduces it.
- **Limitation.** Translation does not load that version's *mods* — see axis 2.

### 2. Native client builds — per-version mods

A mod jar built for Fabric 1.20.1 only loads inside a 1.20.1 client. So running **version-specific
mods** requires a real build per release, which is what `versions/<loader>-<mc>/` provides. Each is
a genuine port: same shared `common/` source, plus that release's Minecraft-facing glue and mixins.

```bash
./gradlew :versions:fabric-1.20.1:build       # a real 1.20.1 Fabric mod jar
./gradlew :versions:fabric-1.20.1:runClient   # boot the 1.20.1 client headless
scripts/verify_native.sh 1.20.1               # boot it against a real 1.20.1 server
scripts/verify_native.sh neoforge-1.21.8      # non-Fabric targets are named <loader>-<mc>
```

`nativeClients[]` in the matrix file records these, with `runtime-verified` (booted and joined a
real server of its own version) ranked above `jar-built` (compiles and remaps, not yet booted).
Drop that version's mods into the module's `run/mods/` alongside the bot.

**What exists today:** Fabric targets for 26.2, 1.21.11, 1.21.8, 1.21.5, 1.21.4, 1.21.1, 1.20.6,
1.20.4, 1.20.1, 1.19.4, 1.19.2, 1.18.2, 1.17.1, 1.16.5, 1.15.2 and 1.14.4 — every Fabric release
back to the oldest one Mojang publishes mappings for.

**NeoForge covers its whole range**: 1.21.11, 1.21.8, 1.21.5, 1.21.4, 1.21.1, 1.20.6 and 1.20.4 —
NeoForge itself begins at 1.20.2. Each emits a control-plane event stream identical to its Fabric
twin. The two loaders share everything except the loader layer: `common/` is loader-neutral behind
`ModPlatform`, `versions/mc-<mc>/` holds that release's Minecraft glue (including `ClefBotCore`,
which is the whole bot), and each loader module contributes only a platform implementation and a
small class wiring its own events onto the core — 36 lines on Fabric, 48 on NeoForge.

`scripts/new_neoforge_version.py <mc> <neoForgeVersion> <javaMajor>` does both halves: splits an
existing Fabric module into the shared part and the Fabric-only remainder, then scaffolds the
NeoForge target. Four of the seven needed no source changes at all.

**What does not exist yet**, and is not pretended to (`nativeClientsNotStarted` in the matrix
file spells out why): **a working Forge target**, 1.12.2, and Fabric 1.13.2 and older. A Forge 1.20.1 module is present and builds a jar, but none of its mixins apply, so it does not run — it is `jar-built` in the matrix, never counted as verified, and the jar CI uploads for it will not work. Forge is a different loader again, though it would now reuse the seam
NeoForge proved out. 1.14.4 is the floor for a different reason: it is the first release with
official Mojang mappings, and those are what let `common/` be shared verbatim across the matrix.

**Sky colour on 1.21.11 and 26.2 is fake.** Screenshots on those two targets always draw a fixed
daylight blue, whatever the biome, time of day or dimension — a Nether capture at midnight comes back
the same colour as a plains noon. Every other target reads the real tint. This is not an oversight:
those releases removed the queryable sky colour (`Biome.getSkyColor()` is gone, and
`BiomeSpecialEffects` carries only water/foliage/grass), leaving it computed into
`SkyRenderState.skyColor` during `LevelRenderer.render` — the pass a headless bot skips, so reading
it would return a stale default dressed up as a real answer. If you need true sky colour, capture on
1.21.8 or older, or drive the real renderer with a GL context.

**GPU-free booting works on both loaders**, for the releases whose Blaze3D generation has a
stub-able device: Fabric and NeoForge on 1.21.8 and 1.21.11 (plus 26.2 on Fabric). The stub is
per-loader rather than per-release, because NeoForge patches Blaze3D with extension interfaces of
its own — `GpuDeviceExtension`, plus `RenderPass.setViewport` and `CommandEncoder.clearStencilTexture`
— so one file cannot compile against both. NeoForge also swaps in its own loading overlay, which
casts the device straight to the concrete `GlDevice`; that target keeps vanilla's overlay when
GPU-free. Everywhere else the bot is still headless, it just needs a real GL context.

**Chat events differ slightly on old releases**, because the packets do. Fabric API only gained
client message events in 1.19.3, so 1.19.2 and older read the chat packets directly
(`ClientChatMixin`) and publish the same `chat` event through `ChatSink`. Two honest consequences:
on 1.15.2 and older the packet carries no sender UUID, so `sender` is null (the name is still in
`text`, which the server sends pre-decorated); and on 1.18.2 and older `text` is that decorated
form (`<Name> hello`) rather than the bare message 1.19+ delivers alongside `sender`.

**GPU-free booting** (`noGl`) needs a stub-able Blaze3D device, which only newer releases have; see
`nativeClients[].noGl`. Where it is false the bot is still headless (the world/GUI draw is skipped)
but needs a real GL context — a hidden window, or Xvfb/Mesa on a bare server.

### Adding a version

`scripts/new_version.py <mc> <fabricApi> <javaMajor> <donorVersion>` scaffolds a module from the
nearest working target; the compile loop then shows exactly what Minecraft changed between the two.
Mojang's official mappings are used on every target, so `common/` is shared verbatim and only real
API changes differ.

Two tools make that loop short, because guessing an old signature from memory is how you waste a
boot:

```bash
scripts/mcjavap.sh 1.16.5 net.minecraft.client.Options fov   # real signatures, from the remapped jar
scripts/check_accessors.py 1.16.5                            # @Accessor types vs the actual fields
```

`check_accessors.py` is worth running before every boot. A mixin `@Accessor` whose type no longer
matches the field compiles perfectly and then kills the game at class load, so it is the one class
of mistake the compiler will never catch for you.

Finish with `scripts/verify_native.sh <mc>` and `scripts/update_native_matrix.py`. A target is only
`runtime-verified` once it has actually joined a real server of its own version — compiling proves
nothing about mixins.
