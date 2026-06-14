/**
 * MezzoSopranoClef control-plane client — TypeScript, isomorphic (browser + Node >= 22).
 *
 * Uses the global `WebSocket` (built into browsers and Node >= 22). On older Node, assign one first:
 *   `import WS from "ws"; (globalThis as any).WebSocket = WS;`
 *
 * The full machine-readable contract (commands, args, events, error codes) is in
 * `clients/schema.json` and from the `schema` command; this is a thin typed wrapper that owns the
 * handshake, `hello` auth, request-id correlation, event dispatch, and turns `{ok:false,code}`
 * responses into a typed {@link ClefError}.
 *
 *   const bot = new ClefClient({ token: "..." });
 *   await bot.connect();
 *   await bot.connectServer("play.example.com");
 *   bot.on("chat", (d) => console.log(d.text));
 *   await bot.subscribe("chat", "death");
 *   try { await bot.mine(10, 64, -3); }
 *   catch (e) { if (e instanceof ClefError && e.code === "NOT_IN_WORLD") {} }
 */

/** Highest control protocol this client was written against (welcome.protocol / schema.protocol). */
export const SUPPORTED_PROTOCOL = 1;

export type ErrorCode =
  | "INVALID_JSON" | "MISSING_CMD" | "UNKNOWN_COMMAND" | "UNAUTHORIZED" | "BAD_TOKEN"
  | "BAD_ARGS" | "NOT_IN_WORLD" | "NOT_CONNECTED" | "NOT_FOUND" | "COMMAND_FAILED";

export type EventName =
  | "welcome" | "chat" | "health" | "damage" | "death" | "respawn" | "join" | "leave"
  | "connected" | "disconnected" | "tick" | "screenOpen" | "screenClose"
  | "entitySpawn" | "entityRemove" | "auth.prompt" | "auth.ok" | "auth.error";

export interface ClefOptions {
  host?: string;          // default 127.0.0.1
  port?: number;          // default 8731
  url?: string;           // overrides host/port, e.g. "ws://10.0.0.5:8731"
  token?: string;         // sent via `hello` on connect when set
  timeoutMs?: number;     // per-request timeout (default 30000)
}

type EventData = Record<string, any>;
type EventHandler = (data: EventData, event: string) => void;

export class ClefError extends Error {
  constructor(public code: ErrorCode | string, public detail: string, public cmd?: string) {
    super(`${cmd ? cmd + ": " : ""}[${code}] ${detail}`);
    this.name = "ClefError";
  }
}

interface Pending {
  resolve: (v: any) => void;
  reject: (e: unknown) => void;
  timer: ReturnType<typeof setTimeout>;
}

export class ClefClient {
  readonly url: string;
  protocol?: number;
  requiresAuth = false;

  private readonly token?: string;
  private readonly timeoutMs: number;
  private ws?: WebSocket;
  private id = 0;
  private readonly pending = new Map<string, Pending>();
  private readonly handlers = new Map<string, Set<EventHandler>>();
  private readonly anyHandlers = new Set<EventHandler>();

  constructor(opts: ClefOptions = {}) {
    this.url = opts.url ?? `ws://${opts.host ?? "127.0.0.1"}:${opts.port ?? 8731}`;
    this.token = opts.token;
    this.timeoutMs = opts.timeoutMs ?? 30000;
  }

  /** Open the socket, read `welcome`, and authenticate if a token was provided. */
  connect(): Promise<void> {
    if (typeof WebSocket === "undefined") {
      throw new Error("no global WebSocket — on Node < 22 set globalThis.WebSocket = require('ws')");
    }
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(this.url);
      this.ws = ws;
      let opened = false;

      ws.onmessage = (ev: MessageEvent) => {
        const raw = typeof ev.data === "string" ? ev.data : String(ev.data);
        let msg: any;
        try { msg = JSON.parse(raw); } catch { return; }

        if (typeof msg.event === "string") {
          if (msg.event === "welcome") {
            this.protocol = msg.data?.protocol;
            this.requiresAuth = !!msg.data?.requiresAuth;
            if (this.protocol != null && this.protocol !== SUPPORTED_PROTOCOL) {
              console.warn(`[clef] bot protocol ${this.protocol} != client ${SUPPORTED_PROTOCOL}`);
            }
          }
          this.dispatch(msg.event, msg.data ?? {});
          return;
        }
        const p = msg.id != null ? this.pending.get(String(msg.id)) : undefined;
        if (!p) return;
        this.pending.delete(String(msg.id));
        clearTimeout(p.timer);
        if (msg.ok) p.resolve(msg.result);
        else p.reject(new ClefError(msg.code ?? "COMMAND_FAILED", msg.error ?? ""));
      };

      ws.onerror = () => { if (!opened) reject(new Error(`failed to connect to ${this.url}`)); };
      ws.onclose = () => {
        for (const p of this.pending.values()) {
          clearTimeout(p.timer);
          p.reject(new ClefError("NOT_CONNECTED", "connection closed"));
        }
        this.pending.clear();
      };
      ws.onopen = async () => {
        opened = true;
        try {
          if (this.token) await this.hello(this.token);
          resolve();
        } catch (e) {
          reject(e);
        }
      };
    });
  }

  /** Send `cmd` with `args`; resolves with `result`, rejects with {@link ClefError} on `ok:false`. */
  call<T = any>(cmd: string, args: Record<string, any> = {}): Promise<T> {
    const ws = this.ws;
    if (!ws || ws.readyState !== ws.OPEN) {
      return Promise.reject(new ClefError("NOT_CONNECTED", "not connected — call connect() first", cmd));
    }
    const mid = String(++this.id);
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(mid);
        reject(new ClefError("COMMAND_FAILED", `timeout after ${this.timeoutMs}ms`, cmd));
      }, this.timeoutMs);
      this.pending.set(mid, {
        resolve,
        reject: (e) => reject(e instanceof ClefError ? new ClefError(e.code, e.detail, cmd) : e),
        timer,
      });
      ws.send(JSON.stringify({ id: mid, cmd, args }));
    });
  }

  hello(token: string) { return this.call("hello", { token }); }

  close() { this.ws?.close(); }

  // ---- events ----------------------------------------------------------------------

  on(event: EventName | string, handler: EventHandler): this {
    (this.handlers.get(event) ?? this.set(event)).add(handler);
    return this;
  }
  onAny(handler: EventHandler): this { this.anyHandlers.add(handler); return this; }
  off(event: EventName | string, handler: EventHandler): this {
    this.handlers.get(event)?.delete(handler);
    return this;
  }

  subscribe(...events: (EventName | string)[]) {
    return events.length ? this.call("subscribe", { events }) : this.call("subscribe");
  }
  unsubscribe() { return this.call("unsubscribe"); }

  private set(event: string): Set<EventHandler> {
    const s = new Set<EventHandler>();
    this.handlers.set(event, s);
    return s;
  }
  private dispatch(event: string, data: EventData) {
    this.handlers.get(event)?.forEach((h) => h(data, event));
    this.anyHandlers.forEach((h) => h(data, event));
  }

  // ---- typed convenience wrappers (anything else: use call()) ----------------------

  ping() { return this.call("ping"); }
  status() { return this.call("status"); }
  schema() { return this.call("schema"); }
  connectServer(host: string, port = 25565) { return this.call("connect", { host, port }); }
  disconnect() { return this.call("disconnect"); }
  chat(message: string) { return this.call("chat", { message }); }
  look(yaw?: number, pitch?: number) { return this.call("look", prune({ yaw, pitch })); }
  players() { return this.call<any[]>("players"); }
  goto(x: number, z: number, y?: number) { return this.call("goto", prune({ x, z, y })); }
  baritone(command: string) { return this.call("baritone", { command }); }
  navStop() { return this.call("nav.stop"); }
  move(flags: Record<string, any>) { return this.call("move", flags); }
  stopMove() { return this.call("stopMove"); }
  mine(x: number, y: number, z: number, face?: string) { return this.call("mine", prune({ x, y, z, face })); }
  place(x: number, y: number, z: number, face?: string) { return this.call("place", prune({ x, y, z, face })); }
  breakBlock(x: number, y: number, z: number) { return this.call("breakBlock", { x, y, z }); }
  use(hand?: string) { return this.call("use", prune({ hand })); }
  attack(entityId?: number) { return this.call("attack", prune({ entityId })); }
  setSlot(slot: number) { return this.call("setSlot", { slot }); }
  inventory() { return this.call("inventory"); }
  entities(radius?: number) { return this.call<any[]>("entities", prune({ radius })); }
  blockAt(x: number, y: number, z: number) { return this.call("blockAt", { x, y, z }); }

  /** Render a PNG and return its raw bytes. opts: x,y,z,yaw,pitch,width,height,fov. */
  async screenshot(opts: Record<string, any> = {}): Promise<Uint8Array> {
    const res = await this.call<{ base64: string }>("screenshot", opts);
    return b64ToBytes(res.base64);
  }
}

/** Drops undefined values so optional args are simply omitted from the request. */
function prune(obj: Record<string, any>): Record<string, any> {
  const out: Record<string, any> = {};
  for (const k of Object.keys(obj)) if (obj[k] !== undefined) out[k] = obj[k];
  return out;
}

function b64ToBytes(b64: string): Uint8Array {
  const g = globalThis as any;
  if (typeof g.Buffer !== "undefined") return new Uint8Array(g.Buffer.from(b64, "base64"));
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
