# Contributing

## Local Checks

Run the fast checks before opening a pull request:

```bash
./gradlew test
./gradlew build
git diff --check
```

The schema contract is generated from `ApiSchema` and guarded by tests. If you change commands,
events, or result metadata, refresh the committed schema:

```bash
./gradlew test -Dclef.schema.write=true
```

The TypeScript client has its own small test harness:

```bash
cd clients/typescript
npm install
npm test
```

## Live Verification

The live scripts boot a real offline-mode Minecraft server and a real headless client. They are
heavier than unit tests and are best run before releases or after touching launch/headless/world
interaction code:

```bash
scripts/e2e.sh                       # newest-version server (the client's own version)
MC_VERSION=1.12.2 scripts/e2e.sh     # same smoke test against an old server (via ViaFabricPlus)
python3 scripts/verify_live.py
python3 scripts/verify_events.py
scripts/verify_versions.sh           # one client, a real server per protocol era; --all for every release
python3 scripts/update_version_matrix.py   # fold the report into minecraft-versions.json
scripts/verify_native.sh             # every NATIVE per-version build vs a server of its own version
python3 scripts/update_native_matrix.py    # fold that report in too
```

Older servers need older JDKs (8 for 1.12–1.16, 17 for 1.18–1.20.4, 21 for 1.20.5+, 25 for 26.x);
`scripts/pick_server_java.py` finds them, including Gradle's auto-provisioned toolchains.

The GitHub `Live E2E` workflow can also be started manually and runs on a schedule.

## Version work

Two axes, kept separate (see the README):

* **Server reach** is protocol translation — no per-version build. Bumping the *newest* release
  means updating `minecraft_version`, `fabric_api_version`, `baritone_version` and
  `viafabricplus_version` in `gradle.properties` (all four must exist for that release), fixing
  what the compiler and mixins complain about, then running the live checks.
* **Native client builds** live in `versions/<loader>-<mc>/` and are what make version-specific
  mods work. Add one with `scripts/new_version.py <mc> <fabricApi> <javaMajor> <donor>`, which
  copies the nearest working target; the compile loop then surfaces the real API drift.

Two helpers keep that loop short:

```bash
scripts/mcjavap.sh 1.16.5 net.minecraft.client.Options fov   # real signatures from the remapped jar
scripts/mcjavap.sh 1.16.5 --list 'Chat.*Packet'              # or find where a class moved to
python3 scripts/check_accessors.py 1.16.5                    # @Accessor types vs the actual fields
```

Look the signature up rather than recalling it — the same method changes shape several times across
the matrix, and a wrong guess costs a boot to discover.

The same applies to what a field *means*, which no compiler will check for you. The one that has
already cost us a bug: **`Slot.index` is the slot's position in the menu, not its index in the
container.** `AbstractContainerMenu.addSlot` assigns it, which is why it is the number the click
packet carries — but it means an `InventoryMenu` slot holding hotbar item 0 has `index == 36`.
Comparing it against inventory indices silently drops the whole hotbar. Use `getContainerSlot()`
(1.17.1+) if you genuinely need the container index, and prefer not needing it.

### Layering

Four layers, sharing as much as honestly possible:

| Where | What | Compiled into |
|---|---|---|
| `common/` | loader- and version-neutral; reaches the loader only via `ModPlatform` | every target |
| `fabric-common/` | Fabric entrypoints + `FabricPlatform` | every Fabric target |
| `versions/mc-<mc>/` | one release's Minecraft glue, where two loaders share a release | that release's targets |
| `versions/<loader>-<mc>/` | that loader's platform, entrypoint and event wiring | itself |

Adding a **loader** (Forge, say) means implementing `ModPlatform` and writing a small class that
adapts that loader's events onto `ClefBotCore` — the Fabric one is 36 lines, the NeoForge one 48.
Adding a **version** means porting only the Minecraft glue. Two things learned porting NeoForge,
both invisible to the compiler: NeoForge constructs mods while `Minecraft.getInstance()` is still
null (Fabric's client entrypoint runs after it exists), and NeoForge patches Blaze3D with its own
extension interfaces, which is why the GPU-free stub device is per-loader rather than per-release.

Keep version-agnostic code in `common/` — it is compiled into every target, so anything added
there must build against all of them, back to 1.14.4. Two traps live there: Minecraft only puts
**SLF4J** on the classpath from 1.17 (so `common/` logs through the Log4j2 API, which every release
has), and it ships **Gson 2.8.0** at 1.17.1 and older, which has no `JsonParser.parseString` (use
`dev.mezzo.clef.util.Json.parse`). Minecraft-facing code belongs in the version module.

A feature that cannot reach every release should still *register* everywhere, and say why it can't,
rather than vanishing from the command surface on older targets. The combat loop is the worked
example: `Ballistics` is arithmetic, so it sits in `common/` and is shared verbatim, while
`CombatController` is per-version and reduces in two places — `Level.dragonParts()` is 1.21.4+ (so a
dragon *part* id cannot be resolved from the level below that; the dragon's own id still works, and
the part to hit comes from `getSubEntities()`), and the `Hotbar` helper only exists in the 1.20.1+
modules (so below that the bow must already be on the hotbar, and the error says exactly that).

A compile is not a pass: mixin targets are only checked at runtime, so a module is not
`runtime-verified` until `scripts/verify_native.sh <mc>` has actually joined a server with it.
`check_accessors.py` catches the most common instance of this — an `@Accessor` whose declared type
no longer matches the field compiles fine and then kills the game at class load.

## API Changes

When adding a command:

1. Register it in `CoreCommands`, `ActionCommands`, or `UiCommands`.
2. Add it to `ApiSchema`.
3. Decide whether it belongs in the read-only control-plane scope.
4. Add focused tests for validation and error-code behavior.
5. Regenerate `clients/schema.json`.

Prefer `ApiException` for expected user/input failures so clients can branch on stable error codes.

## Remote Control Safety

Keep the WebSocket bound to `127.0.0.1` unless you are deliberately exposing it. For remote access,
put it behind a TLS reverse proxy or SSH tunnel, keep `control.authToken` set, and use
`control.readOnlyAuthToken` for dashboards or observers that do not need actuation.

Rotate the full-control token at runtime with:

```json
{"cmd":"control.rotateToken","args":{}}
```

The new token is returned once and persisted to `config/mezzoclef.json`.
