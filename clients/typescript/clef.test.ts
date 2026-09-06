import assert from "node:assert/strict";
import test from "node:test";
import { ClefClient, ClefError, SUPPORTED_PROTOCOL, decodeBlocksIn } from "./clef.ts";

type Handler = ((event?: any) => void) | null;

class FakeWebSocket {
  static instances: FakeWebSocket[] = [];
  readonly OPEN = 1;
  readyState = 0;
  onopen: Handler = null;
  onmessage: Handler = null;
  onclose: Handler = null;
  onerror: Handler = null;
  sent: string[] = [];

  constructor(readonly url: string) {
    FakeWebSocket.instances.push(this);
    queueMicrotask(() => {
      this.readyState = this.OPEN;
      this.onopen?.({});
      this.emit({ event: "welcome", data: { protocol: SUPPORTED_PROTOCOL, requiresAuth: true } });
    });
  }

  send(text: string) {
    this.sent.push(text);
    const msg = JSON.parse(text);
    if (msg.cmd === "hello") {
      this.emit({ id: msg.id, ok: true, result: { authed: true, protocol: SUPPORTED_PROTOCOL, scope: "full" } });
    } else if (msg.cmd === "ping") {
      this.emit({ id: msg.id, ok: true, result: { pong: true } });
    }
  }

  close() {
    this.readyState = 3;
    this.onclose?.({});
  }

  emit(msg: unknown) {
    queueMicrotask(() => this.onmessage?.({ data: JSON.stringify(msg) }));
  }
}

class NoWelcomeWebSocket extends FakeWebSocket {
  constructor(url: string) {
    super(url);
  }

  override emit(_msg: unknown) {
    // Drop welcome frames to exercise connect timeout.
  }
}

test("connect waits for welcome and authenticates before resolving", async () => {
  FakeWebSocket.instances = [];
  (globalThis as any).WebSocket = FakeWebSocket;
  const bot = new ClefClient({ token: "secret", timeoutMs: 100 });

  await bot.connect();

  assert.equal(bot.protocol, SUPPORTED_PROTOCOL);
  assert.equal(bot.requiresAuth, true);
  assert.equal(FakeWebSocket.instances[0].sent.length, 1);
  assert.equal(JSON.parse(FakeWebSocket.instances[0].sent[0]).cmd, "hello");
});

test("connect rejects if welcome never arrives", async () => {
  FakeWebSocket.instances = [];
  (globalThis as any).WebSocket = NoWelcomeWebSocket;
  const bot = new ClefClient({ timeoutMs: 5 });

  await assert.rejects(() => bot.connect(), ClefError);
});

test("reconnect clears pending calls and reconnects", async () => {
  FakeWebSocket.instances = [];
  (globalThis as any).WebSocket = FakeWebSocket;
  const bot = new ClefClient({ timeoutMs: 100 });
  await bot.connect();

  await bot.reconnect({ attempts: 1 });
  const pong = await bot.ping();

  assert.equal((pong as any).pong, true);
  assert.equal(FakeWebSocket.instances.length, 2);
});

test("decodeBlocksIn expands the palette form in x-major order", () => {
  // 2x1x2 region: two block types alternating, indices [0,1,1,0].
  const decoded = decodeBlocksIn({
    size: [2, 1, 2],
    origin: [0, 0, 0],
    order: "x-major",
    palette: ["minecraft:stone", "minecraft:dirt"],
    encoding: "base64 LEB128 varint indices into palette",
    data: Buffer.from([0, 1, 1, 0]).toString("base64"),
  });

  assert.deepEqual(decoded, [
    "minecraft:stone", "minecraft:dirt",   // x=0: z=0, z=1
    "minecraft:dirt", "minecraft:stone",   // x=1: z=0, z=1
  ]);
});

test("decodeBlocksIn handles multi-byte varints and the unpacked form", () => {
  // Index 200 needs two bytes: 0xC8 0x01.
  const palette = Array.from({ length: 201 }, (_, i) => `mod:block_${i}`);
  const decoded = decodeBlocksIn({
    size: [1, 1, 1],
    origin: [0, 0, 0],
    order: "x-major",
    palette,
    data: Buffer.from([0xc8, 0x01]).toString("base64"),
  });
  assert.deepEqual(decoded, ["mod:block_200"]);

  const unpacked = decodeBlocksIn({
    size: [1, 1, 2],
    origin: [0, 0, 0],
    order: "x-major",
    blocks: ["minecraft:air", "minecraft:stone"],
  });
  assert.deepEqual(unpacked, ["minecraft:air", "minecraft:stone"]);
});

test("decodeBlocksIn refuses a payload that does not fill the region", () => {
  assert.throws(() => decodeBlocksIn({
    size: [4, 4, 4],
    origin: [0, 0, 0],
    order: "x-major",
    palette: ["minecraft:stone"],
    data: Buffer.from([0]).toString("base64"),
  }), /expected 64/);
});
