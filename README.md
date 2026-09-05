# MezzoSopranoClef

Headless Minecraft client for automation. It runs the real Fabric client, keeps networking, physics, mods, and Baritone active, and exposes control over WebSocket/JSON. Rendering, windows, audio, and screenshots are optional; the default screenshot backend is a CPU raycaster.

## What it does

- Connects to offline or Microsoft-authenticated servers.
- Drives movement, mining, placing, combat, inventory, containers, trades, and timed item use.
- Streams chat, health, death, entity, and world events.
- Runs Baritone pathing when its compatible jar is bundled.
- Boots without a display or OpenGL context (`noGl`) and captures PNG screenshots on demand.
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
```

The E2E scripts start a real local server, boot a real client, exercise control and world actions, and verify a software-rendered screenshot.

## Release

Set `mod_version` in `gradle.properties`, run the checks, then tag and push (the workflow publishes jars and a GHCR image):

```bash
git tag v0.1.0 && git push origin v0.1.0
```

## Layout and license

`src/main/java/dev/mezzo/clef` contains the client, API, bot, auth, navigation, mixins, and renderer. `launcher/`, `clients/`, and `scripts/` contain packaging, clients, and probes. MIT; see [LICENSE](LICENSE).
