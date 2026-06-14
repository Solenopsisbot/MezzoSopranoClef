import assert from "node:assert/strict";
import test from "node:test";
import { ClefClient, ClefError } from "./clef.ts";

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
      this.emit({ event: "welcome", data: { protocol: 1, requiresAuth: true } });
    });
  }

  send(text: string) {
    this.sent.push(text);
    const msg = JSON.parse(text);
    if (msg.cmd === "hello") {
      this.emit({ id: msg.id, ok: true, result: { authed: true, protocol: 1, scope: "full" } });
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

  assert.equal(bot.protocol, 1);
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
