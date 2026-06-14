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
scripts/e2e.sh
python3 scripts/verify_live.py
python3 scripts/verify_events.py
```

The GitHub `Live E2E` workflow can also be started manually and runs on a schedule.

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
