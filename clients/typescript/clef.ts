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
export const SUPPORTED_PROTOCOL = 2;

export type ErrorCode =
  | "INVALID_JSON" | "MISSING_CMD" | "UNKNOWN_COMMAND" | "UNAUTHORIZED" | "BAD_TOKEN"
  | "BAD_ARGS" | "NOT_IN_WORLD" | "NOT_CONNECTED" | "NOT_FOUND" | "RATE_LIMIT" | "COMMAND_FAILED";

export type EventName =
  | "welcome" | "chat" | "health" | "damage" | "death" | "respawn" | "join" | "leave"
  | "connected" | "disconnected" | "tick" | "screenOpen" | "screenClose"
  | "entitySpawn" | "entityRemove" | "entityHurt" | "itemPickup" | "inventory"
  | "blockUpdate" | "explosion" | "weather" | "time" | "sleep" | "title" | "mineDone"
  | "combatDone"
  | "nav.done" | "nav.failed" | "nav.progress" | "baritone.log"
  | "auth.prompt" | "auth.ok" | "auth.error";

/**
 * Stable, remap-proof screen names (`status.screen`, `screenOpen.screen`). The raw class name is
 * alongside as `screenClass`, but it is obfuscated outside a development run — `class_424`, not
 * `TitleScreen` — so branch on this, not on that.
 */
export type ScreenName =
  | "none" | "title" | "connect" | "disconnected" | "death" | "downloading" | "message"
  | "inventory" | "crafting" | "furnace" | "anvil" | "enchanting" | "merchant" | "sign"
  | "book" | "container" | "other";

/** `nav.check`'s answer. `unknown` means the search ran out of budget, not that there is no route. */
export type NavVerdict = "reachable" | "unreachable" | "unknown";

/** The closed set the `chat` event's `kind` field takes since protocol 2. */
export type ChatKind = "chat" | "system" | "whisper" | "team" | "actionbar";

/** Why a `shootAt`/`meleeWhile` ended. `done` is the only one that means "ran to completion". */
export type CombatStop =
  | "done" | "dead" | "gone" | "timeout" | "range" | "health"
  | "blocked" | "out_of_ammo" | "cancelled" | "replaced";

/**
 * The outcome of a combat loop, returned by `shootAt`/`meleeWhile` and pushed as `combatDone`.
 *
 * `hits` is melee swings for `meleeWhile`; for `shootAt` it counts the times the target's health
 * dropped during the run, which includes damage from anything else that was hitting it.
 */
export interface CombatResult {
  kind: "shootAt" | "meleeWhile";
  entityId: number;
  fired: number;
  hits: number;
  damage: number;
  killed: boolean;
  stopped: CombatStop;
  detail?: string;
  ticks: number;
}

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
      let settled = false;
      const connectTimer = setTimeout(() => {
        if (!settled) {
          settled = true;
          ws.close();
          reject(new ClefError("COMMAND_FAILED", `connect timeout after ${this.timeoutMs}ms`));
        }
      }, this.timeoutMs);
      const finishResolve = () => {
        if (settled) return;
        settled = true;
        clearTimeout(connectTimer);
        resolve();
      };
      const finishReject = (e: unknown) => {
        if (settled) return;
        settled = true;
        clearTimeout(connectTimer);
        reject(e);
      };

      ws.onmessage = async (ev: MessageEvent) => {
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
            this.dispatch(msg.event, msg.data ?? {});
            try {
              if (this.token) await this.hello(this.token);
              finishResolve();
            } catch (e) {
              finishReject(e);
            }
            return;
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

      ws.onerror = () => { if (!opened) finishReject(new Error(`failed to connect to ${this.url}`)); };
      ws.onclose = () => {
        for (const p of this.pending.values()) {
          clearTimeout(p.timer);
          p.reject(new ClefError("NOT_CONNECTED", "connection closed"));
        }
        this.pending.clear();
        if (!settled) finishReject(new ClefError("NOT_CONNECTED", "connection closed before welcome"));
      };
      ws.onopen = () => {
        opened = true;
      };
    });
  }

  async connectRetry(opts: { attempts?: number; initialDelayMs?: number; maxDelayMs?: number } = {}): Promise<void> {
    const attempts = Math.max(1, opts.attempts ?? 30);
    let delay = opts.initialDelayMs ?? 500;
    const maxDelay = opts.maxDelayMs ?? 5000;
    let last: unknown;
    for (let i = 0; i < attempts; i++) {
      try {
        await this.connect();
        return;
      } catch (e) {
        last = e;
        this.close();
        if (i < attempts - 1) await sleep(delay);
        delay = Math.min(maxDelay, delay * 2);
      }
    }
    throw last instanceof Error ? last : new Error(`failed to connect after ${attempts} attempts`);
  }

  async reconnect(opts: { attempts?: number; initialDelayMs?: number; maxDelayMs?: number } = {}): Promise<void> {
    this.close();
    for (const p of this.pending.values()) {
      clearTimeout(p.timer);
      p.reject(new ClefError("NOT_CONNECTED", "reconnecting"));
    }
    this.pending.clear();
    await this.connectRetry(opts);
  }

  /** Send `cmd` with `args`; resolves with `result`, rejects with {@link ClefError} on `ok:false`. */
  /**
   * @param timeoutMs overrides the client default for this call only — needed by the commands that
   *                  deliberately take a while (`screenshot`, `mine {wait}`, `shootAt`,
   *                  `meleeWhile`), which can outlast the 30 s default on their own.
   */
  call<T = any>(cmd: string, args: Record<string, any> = {}, timeoutMs?: number): Promise<T> {
    const ws = this.ws;
    if (!ws || ws.readyState !== ws.OPEN) {
      return Promise.reject(new ClefError("NOT_CONNECTED", "not connected — call connect() first", cmd));
    }
    const mid = String(++this.id);
    const limit = timeoutMs ?? this.timeoutMs;
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(mid);
        reject(new ClefError("COMMAND_FAILED", `timeout after ${limit}ms`, cmd));
      }, limit);
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
  /**
   * Start pathing. Resolves as soon as Baritone accepts the goal — listen for `nav.done` /
   * `nav.failed` for the outcome rather than polling `nav.status`.
   */
  goto(x: number, z: number, y?: number, reach?: number) { return this.call("goto", prune({ x, z, y, reach })); }
  /**
   * Cheap walkability **estimate** — a walk-only A* over the loaded chunks, not Baritone's planner.
   * Nothing moves. Branch on `verdict`: `unknown` means the search hit its node budget, which is
   * not the same as proving there is no route, and Baritone may well get there anyway.
   */
  navCheck(x: number, y: number, z: number, opts: { reach?: number; maxNodes?: number } = {}) {
    return this.call<{ verdict: NavVerdict; reachable: boolean; cost?: number; nodes: number; exhausted: boolean }>(
      "nav.check", prune({ x, y, z, ...opts }));
  }
  /**
   * Run any Baritone command. Resolves with the lines Baritone printed within `collectMs`, which is
   * how you read the output of `find`, `eta`, or "No known locations of ...".
   *
   * Commands naming a block (`goto jungle_log`, `mine diamond_ore`) are safe: the bot pre-initialises
   * Baritone's block-argument machinery off the client thread, and refuses the command outright
   * rather than running it if that initialisation failed.
   */
  baritone(command: string, collectMs?: number) {
    return this.call<{ ran: string; backend: string; output: string[] }>("baritone", prune({ command, collectMs }));
  }
  navStop() { return this.call("nav.stop"); }
  move(flags: Record<string, any>) { return this.call("move", flags); }
  stopMove() { return this.call("stopMove"); }
  /** Break a block. With `wait`, resolves only once it is broken or refused. */
  mine(x: number, y: number, z: number, opts: { face?: string; wait?: boolean } = {}) {
    return this.call("mine", prune({ x, y, z, ...opts }));
  }
  /** Place against a block face. `item` is selected first; `placed` is verified against the world. */
  place(x: number, y: number, z: number, opts: { face?: string; item?: string; confirm?: boolean } = {}) {
    return this.call<{ placed: boolean; confirmed: boolean }>("place", prune({ x, y, z, ...opts }));
  }
  /** Put an item on the hotbar and select it, swapping it in from the inventory if needed. */
  moveToHotbar(item: string, slot?: number) { return this.call<{ slot: number }>("moveToHotbar", prune({ item, slot })); }
  breakBlock(x: number, y: number, z: number) { return this.call("breakBlock", { x, y, z }); }
  use(hand?: string) { return this.call("use", prune({ hand })); }
  attack(entityId?: number) { return this.call("attack", prune({ entityId })); }
  setSlot(slot: number) { return this.call("setSlot", { slot }); }

  // ---- combat loops that run on the bot --------------------------------------------

  /**
   * Loose arrows at an entity, with the tracking loop running on the bot at 20 Hz: it solves the
   * lead from the target's real motion and the drop from a real arrow simulation, and sets the
   * rotation on the release tick. Aiming from out here costs about 1.5 s a shot, which is long
   * enough for anything mobile to have left.
   *
   * Resolves when the last arrow has landed. Pass `wait: false` to get control straight back and
   * take the outcome from the `combatDone` event.
   */
  shootAt(entityId: number, opts: { shots?: number; lead?: boolean; charge?: number;
                                    maxRange?: number; wait?: boolean } = {}) {
    const shots = opts.shots ?? 1;
    const charge = opts.charge ?? 25;
    return this.call<CombatResult>("shootAt", prune({ entityId, ...opts }),
      (shots * (charge + 25) + 80) * 50 + 10000);
  }

  /**
   * Swing at an entity on the attack-cooldown cadence while it stays in reach. Resolves the
   * damageable part of a multi-part entity, which is the only way to hurt an ender dragon — the
   * parent entity ignores damage outright.
   */
  meleeWhile(entityId: number, opts: { maxMs?: number; reach?: number; stopBelowHealth?: number;
                                       wait?: boolean } = {}) {
    return this.call<CombatResult>("meleeWhile", prune({ entityId, ...opts }),
      (opts.maxMs ?? 5000) + 10000);
  }

  /** Take the body back from a running `shootAt`/`meleeWhile`. */
  combatStop() { return this.call<{ stopped: boolean; kind?: string }>("combat.stop"); }
  combatStatus() { return this.call<{ busy: boolean; kind?: string }>("combat.status"); }

  /**
   * Wear or hold an item. Already in its equipment slot is a no-op (`changed: false`) —
   * re-equipping worn armour does not take it off.
   */
  equip(item: string) {
    return this.call<{ equipped: string; changed: boolean; slot?: string; fromSlot?: number }>(
      "equip", { item });
  }
  inventory() { return this.call("inventory"); }
  /** Nearby entities with health, hostility, held item and trade data. `kinds` filters by type id. */
  entities(radius?: number, kinds?: string[]) { return this.call<any[]>("entities", prune({ radius, kinds })); }
  blockAt(x: number, y: number, z: number) { return this.call("blockAt", { x, y, z }); }

  // ---- bulk world queries ----------------------------------------------------------

  /** Nearest matching blocks in the loaded chunks. `ids` accepts block ids and `#tag` names. */
  findBlocks(ids: string[], opts: { radius?: number; max?: number; sort?: "nearest" | "none" } = {}) {
    return this.call<Array<{ x: number; y: number; z: number; block: string; distance: number }>>(
      "findBlocks", prune({ ids, ...opts }));
  }
  /** Dense readout of a block cuboid; decode the default palette form with {@link decodeBlocksIn}. */
  blocksIn(min: [number, number, number], max: [number, number, number], palette = true) {
    return this.call<BlocksInResult>("blocksIn", {
      minX: min[0], minY: min[1], minZ: min[2], maxX: max[0], maxY: max[1], maxZ: max[2], palette,
    });
  }
  /** What is under the crosshair, raycast on demand (so it works headless). */
  target(maxDistance?: number, fluids?: boolean) { return this.call("target", prune({ maxDistance, fluids })); }
  /** Block/item/entity ids the running Minecraft version actually has. */
  registry(kinds?: string[], tags?: boolean) { return this.call("registry", prune({ kinds, tags })); }
  lookAt(target: { x: number; y: number; z: number } | { entityId: number }) {
    return this.call("lookAt", target as Record<string, any>);
  }

  // ---- crafting --------------------------------------------------------------------

  /** Craft via the open crafting screen, else the 2x2 player grid. Resolves when the craft ends. */
  craft(item: string, opts: { count?: number; all?: boolean } = {}) {
    return this.call<{ crafted: number; item: string; reason?: string }>("craft", prune({ item, ...opts }));
  }
  recipes(item: string) { return this.call<any[]>("recipes", { item }); }
  craftable() { return this.call<{ grid: string; items: any[] }>("craftable"); }

  // ---- chat ------------------------------------------------------------------------

  chatHistory(limit?: number) { return this.call<{ lines: any[]; stored: number }>("chatHistory", prune({ limit })); }
  /** Private-message a player using whichever of msg/tell/w/whisper this server has. */
  whisper(player: string, text: string) { return this.call("whisper", { player, text }); }
  /** Run several commands in order over one round-trip. */
  batch(commands: Array<{ cmd: string; args?: Record<string, any> }>, continueOnError = false) {
    return this.call<{ results: any[]; ran: number }>("batch", { commands, continueOnError });
  }

  /**
   * Render a PNG and return its raw bytes. opts: x,y,z,yaw,pitch,width,height,fov for the free
   * camera, or `{ mode: "topdown", centerX, centerZ, radius }` for an orthographic map.
   */
  async screenshot(opts: Record<string, any> = {}): Promise<Uint8Array> {
    const res = await this.call<{ base64: string }>("screenshot", opts);
    return b64ToBytes(res.base64);
  }

  /** Like {@link screenshot}, but also returns the camera and each visible entity's screen box. */
  async screenshotAnnotated(opts: Record<string, any> = {}): Promise<{
    png: Uint8Array;
    camera: Record<string, any>;
    entities: Array<{ id: number; type: string; visible: boolean; box?: number[]; depth?: number }>;
  }> {
    const res = await this.call<any>("screenshot", { ...opts, annotate: true });
    return { png: b64ToBytes(res.base64), camera: res.camera, entities: res.entities ?? [] };
  }

  /** Orthographic top-down map PNG, east-right and north-up, centred on the bot. */
  map(radius = 32, opts: Record<string, any> = {}): Promise<Uint8Array> {
    return this.screenshot({ ...opts, mode: "topdown", radius });
  }
}

/** The palette form of a {@link ClefClient.blocksIn} result. */
export interface BlocksInResult {
  size: [number, number, number];
  origin: [number, number, number];
  order: string;
  palette?: string[];
  encoding?: string;
  data?: string;
  blocks?: string[];
}

/**
 * Expands a palette-encoded `blocksIn` result into a flat array of block ids, in the x-major order
 * the bot documents: `i = ((x-minX)*sizeY + (y-minY))*sizeZ + (z-minZ)`.
 *
 * Blocks in chunks the server has not sent read as `"unloaded"` — which is not the same as air.
 */
export function decodeBlocksIn(result: BlocksInResult): string[] {
  if (result.blocks) return result.blocks;
  if (!result.palette || !result.data) throw new Error("blocksIn result has neither blocks nor palette data");
  const bytes = b64ToBytes(result.data);
  const [sx, sy, sz] = result.size;
  const out: string[] = [];
  let value = 0;
  let shift = 0;
  for (const byte of bytes) {
    value |= (byte & 0x7f) << shift;
    if (byte & 0x80) { shift += 7; continue; }
    out.push(result.palette[value]);
    value = 0;
    shift = 0;
  }
  if (out.length !== sx * sy * sz) {
    throw new Error(`decoded ${out.length} blocks, expected ${sx * sy * sz}`);
  }
  return out;
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

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
