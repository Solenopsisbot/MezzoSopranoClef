# MezzoSopranoClef

Headless Minecraft client for automation. It runs the real Fabric client, keeps networking, physics, mods, and Baritone active, and exposes control over WebSocket/JSON. Rendering, windows, audio, and screenshots are optional; the default screenshot backend is a CPU raycaster.

## What it does

- Connects to offline or Microsoft-authenticated servers.
- Drives movement, mining, placing, crafting, combat, inventory, containers, trades, and timed item use.
- Answers bulk world questions — `findBlocks` over the chunk cache (block ids or `#tags`), `blocksIn`
  for a dense cuboid, `target` for what's under the crosshair — instead of one block per round-trip.
- Streams chat, health, death, entity, block-update, pickup, weather, time, sleep and navigation events.
- Runs Baritone pathing when its compatible jar is bundled, and reports arrival or failure as an event.
- Boots without a display or OpenGL context (`noGl`) and captures PNG screenshots on demand, including
  an orthographic top-down map and annotated captures with on-screen entity boxes.
- Includes a browser dashboard plus Python and TypeScript clients.

## Requirements

This branch targets **Minecraft 1.21.8**, Fabric, and Java 21. A newer Minecraft release is not a properties-only upgrade: mappings, Fabric API, Baritone, mixins, launcher metadata, and live tests must move together.

## Run

```bash
./gradlew runClient
./gradlew runClient -Dmezzoclef.dashboard=true
python3 scripts/ws_probe.py
```

The first run downloads Minecraft assets. Configuration is written to `run/config/mezzoclef.json`. Set `connection.autoConnect` and `connection.serverHost`, or pass `-Dmezzoclef.connect.auto=true -Dmezzoclef.connect.host=host:port`.

Standalone and Docker builds are available with `./gradlew build :launcher:jar` and `docker build -t mezzosopranoclef .`.

## API

Connect to `ws://127.0.0.1:8731` and send JSON commands such as `{"cmd":"ping","args":{}}` or
`{"cmd":"status","args":{}}`. The authoritative command and event contract is
[clients/schema.json](clients/schema.json) — 68 commands, 30 events, protocol 2. Keep the socket on
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
| Fight something | `entities {kinds:["zombie"]}` carries health, hostility, held item and whether it's looking at you; `entityHurt` names the attacker |
| See the layout | `screenshot {mode:"topdown", radius:64}` for an orthographic map (east-right, north-up) |
| Label a screenshot | `screenshot {annotate:true}` also returns the camera and each visible entity's screen-space box |
| Save round-trips | `batch {commands:[{cmd,args}...]}` runs them in order and returns every result |
| Read Baritone's own output | `baritone {command}` returns the lines it printed, and `baritone.log` streams them — otherwise they go to a chat HUD a headless bot doesn't have |
| Know which screen you're on | `status.screen` is a stable name (`title`, `death`, `container`, …), not the obfuscated class; the raw one is in `screenClass` |

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
```

The E2E scripts start a real local server, boot a real client, exercise control and world actions, and verify a software-rendered screenshot.

## Release

Set `mod_version` in `gradle.properties`, run the checks, then tag and push (the workflow publishes jars and a GHCR image):

```bash
git tag v0.1.0 && git push origin v0.1.0
```

## Layout and license

`src/main/java/dev/mezzo/clef` contains the client, API, bot, auth, navigation, mixins, and renderer. `launcher/`, `clients/`, and `scripts/` contain packaging, clients, and probes. MIT; see [LICENSE](LICENSE).
