# MezzoSopranoClef

Headless Minecraft client for automation. It runs the real Fabric client, keeps networking, physics, mods, and Baritone active, and exposes control over WebSocket/JSON. Rendering, windows, audio, and screenshots are optional; the default screenshot backend is a CPU raycaster. One build joins servers on **every release from 1.12.2 through 26.2** (see [Server versions](#server-versions)).

## What it does

- Connects to offline or Microsoft-authenticated servers running any Minecraft release from 1.12.2 to 26.2 (protocol translation via ViaFabricPlus).
- Drives movement, mining, placing, combat, inventory, containers, trades, and timed item use.
- Streams chat, health, death, entity, and world events.
- Runs Baritone pathing when its compatible jar is bundled.
- Boots without a display or OpenGL context (`noGl`) and captures PNG screenshots on demand.
- Includes a browser dashboard plus Python and TypeScript clients.

## Requirements

The client is built against **Minecraft 26.2**, Fabric, and **Java 25**, using Mojang's official names (26.1+ ships unobfuscated; there are no Yarn mappings any more). A newer Minecraft release is not a properties-only upgrade: Fabric API, Baritone, ViaFabricPlus, mixins, launcher metadata, and live tests must move together. Older *servers* need no port at all; see [Server versions](#server-versions).

## Run

```bash
./gradlew runClient
./gradlew runClient -Dmezzoclef.dashboard=true
python3 scripts/ws_probe.py
```

The first run downloads Minecraft assets. Configuration is written to `run/config/mezzoclef.json`. Set `connection.autoConnect` and `connection.serverHost`, or pass `-Dmezzoclef.connect.auto=true -Dmezzoclef.connect.host=host:port`. `connection.serverVersion` (or `-Dmezzoclef.connect.version=`) picks the protocol: `auto` (default, ping and match), `native`, or a release such as `1.12.2`.

Standalone and Docker builds are available with `./gradlew build :launcher:jar` and `docker build -t mezzosopranoclef .`.

## API

Connect to `ws://127.0.0.1:8731` and send JSON commands such as `{"cmd":"ping","args":{}}` or `{"cmd":"status","args":{}}`. The authoritative command and event contract is [clients/schema.json](clients/schema.json). Keep the socket on localhost unless it is behind TLS and configured with `control.authToken`.

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
  official releases from 1.12.2 to 26.2; an entry is `verified` only after
  `scripts/verify_versions.sh` actually joined a real vanilla server of that exact release.
- **Limitation.** Translation does not load that version's *mods* — see axis 2.

### 2. Native client builds — per-version mods

A mod jar built for Fabric 1.20.1 only loads inside a 1.20.1 client. So running **version-specific
mods** requires a real build per release, which is what `versions/<loader>-<mc>/` provides. Each is
a genuine port: same shared `common/` source, plus that release's Minecraft-facing glue and mixins.

```bash
./gradlew :versions:fabric-1.20.1:build       # a real 1.20.1 Fabric mod jar
./gradlew :versions:fabric-1.20.1:runClient   # boot the 1.20.1 client headless
scripts/verify_native.sh 1.20.1               # boot it against a real 1.20.1 server
```

`nativeClients[]` in the matrix file records these, with `runtime-verified` (booted and joined a
real server of its own version) ranked above `jar-built` (compiles and remaps, not yet booted).
Drop that version's mods into the module's `run/mods/` alongside the bot.

**What exists today:** Fabric targets for 26.2, 1.21.11, 1.21.8, 1.21.5, 1.21.4, 1.21.1, 1.20.6,
1.20.4, 1.20.1, 1.19.4, 1.19.2, 1.18.2, 1.17.1, 1.16.5, 1.15.2 and 1.14.4 — every Fabric release
back to the oldest one Mojang publishes mappings for.

**What does not exist yet**, and is not pretended to (`nativeClientsNotStarted` in the matrix
file spells out why): **any Forge or NeoForge target**, 1.12.2, and Fabric 1.13.2 and older. Forge
and NeoForge are different loaders rather than older versions — different entrypoints, events and
build plugin — so each is a port of the loader-facing layer, not a version bump. If you need one,
that is the next piece of work, not a flag to flip. 1.14.4 is the floor for a different reason:
it is the first release with official Mojang mappings, and those are what let `common/` be shared
verbatim across the matrix.

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
