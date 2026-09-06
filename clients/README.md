# MezzoSopranoClef clients

Thin, typed clients for the [control plane](../README.md#control-plane-websocket--json). They own
the WebSocket handshake, the `hello` auth step, request-id correlation, event dispatch, and turn
`{"ok":false,"code":...}` responses into a typed error you can branch on.

Everything here is derived from one source of truth: **[`schema.json`](./schema.json)** — the full
machine-readable contract (every command + typed args, every event + fields, the error-code
catalog, the protocol version). It's also returned live by the `schema` command. Generate bindings
for any other language straight off it.

| Client | Runtime | Dependencies |
|---|---|---|
| [`python/clef.py`](./python/clef.py) | Python 3.8+ | none (standard library only) |
| [`typescript/clef.ts`](./typescript/clef.ts) | Browser / Node ≥ 22 / Bun / Deno | none (global `WebSocket`) |

## Python

```python
from clef import ClefClient, ClefError

with ClefClient(token="...") as bot:          # connect + auth; token also read from $CLEF_WS_TOKEN
    print("protocol", bot.protocol)
    bot.connect_server("play.example.com")
    bot.chat("hello from a corpse")
    try:
        bot.mine(10, 64, -3)
    except ClefError as e:
        if e.code == "NOT_IN_WORLD":
            print("not spawned yet")

    bot.subscribe("chat", "death")
    for name, data in bot.events():           # blocks, yields pushed events
        print(name, data)
```

Smoke test against a running bot: `python3 clef.py status`.

If your script starts the bot process and immediately connects, use `connect_retry()` (or
`reconnect()`) to wait through startup without writing your own backoff loop.

## TypeScript / JavaScript

Node ≥ 22 and browsers have a global `WebSocket`. On older Node, set one first:
`import WS from "ws"; (globalThis as any).WebSocket = WS;`

```ts
import { ClefClient, ClefError } from "./clef.ts";

const bot = new ClefClient({ host: "127.0.0.1", port: 8731, token: "..." });
await bot.connectRetry();
await bot.connectServer("play.example.com");

bot.on("chat", (d) => console.log("chat:", d.text));
await bot.subscribe("chat", "death");

try {
  await bot.mine(10, 64, -3);
} catch (e) {
  if (e instanceof ClefError && e.code === "NOT_IN_WORLD") console.log("not spawned yet");
}

const png = await bot.screenshot({ width: 1280, height: 720 }); // Uint8Array
```

Run the source directly with `tsx`, `bun`, or `deno`, or compile with `tsc`.

## Beyond the basics

Both clients wrap the newer command groups too:

```python
logs = bot.find_blocks(["#minecraft:logs"], radius=32, max=8)   # nearest first
bot.goto(logs[0]["x"], logs[0]["z"], logs[0]["y"], reach=2)     # then wait for nav.done
bot.mine(logs[0]["x"], logs[0]["y"], logs[0]["z"], wait=True)   # blocks until broken or refused

bot.craft("stick", count=4)
png = bot.map(radius=64)                                        # top-down orthographic PNG

region = bot.blocks_in((0, 63, 0), (15, 78, 15))
from clef import decode_blocks_in
blocks = decode_blocks_in(region)                               # flat list, x-major
```

```ts
const logs = await bot.findBlocks(["#minecraft:logs"], { radius: 32, max: 8 });
await bot.goto(logs[0].x, logs[0].z, logs[0].y, 2);
bot.on("nav.done", () => console.log("arrived"));
await bot.mine(logs[0].x, logs[0].y, logs[0].z, { wait: true });

await bot.craft("stick", { count: 4 });
const { png, entities } = await bot.screenshotAnnotated({ width: 640, height: 360 });

const region = await bot.blocksIn([0, 63, 0], [15, 78, 15]);
const blocks = decodeBlocksIn(region);                          // flat array, x-major
```

`blocksIn` returns a palette plus base64 LEB128 varint indices in **x-major** order —
`i = ((x-minX)*sizeY + (y-minY))*sizeZ + (z-minZ)`. Both clients ship a reference decoder
(`decode_blocks_in` / `decodeBlocksIn`); blocks in chunks the server hasn't sent read as
`"unloaded"`, which is not the same as air.

## Anything not covered by a helper

Both clients expose the generic call for the full command set in `schema.json`:

```python
bot.call("clickSlot", slot=0, mode="quickMove")
```
```ts
await bot.call("clickSlot", { slot: 0, mode: "quickMove" });
```

## Regenerating `schema.json`

It's generated from `ApiSchema` and guarded by a build-time test (the build fails if it drifts from
the registered command set). To refresh it after changing the API:

```bash
./gradlew test -Dclef.schema.write=true
```
