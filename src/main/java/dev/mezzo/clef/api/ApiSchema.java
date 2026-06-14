package dev.mezzo.clef.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The machine-readable description of the entire control-plane contract: every command and its
 * argument shape, every event and its payload shape, every error code, and the protocol version.
 *
 * <p>This is the <b>single source of truth</b> for the wire contract. It is served verbatim by the
 * {@code schema} command, so any client (in any language) can fetch it and generate a typed wrapper
 * or validate requests instead of scraping the README. One-line command summaries are <i>not</i>
 * duplicated here — they're merged in from the dispatcher's {@code help} map at serialization time,
 * so there's exactly one place each summary lives.</p>
 *
 * <p>An {@code ApiSchemaTest} asserts the command set declared here matches the set actually
 * registered with the dispatcher, so the two can never silently drift.</p>
 */
public final class ApiSchema {

    /**
     * Version of the JSON control protocol (envelope shape, command/event/error semantics). Bump
     * this on any breaking wire change; it's surfaced in the {@code welcome} event and the
     * {@code schema} command so clients can negotiate or fail fast.
     */
    public static final int PROTOCOL_VERSION = 1;

    /** Argument / field value types, rendered to a stable lowercase wire string. */
    public enum Type {
        INT, LONG, DOUBLE, FLOAT, BOOL, STRING, STRING_ARRAY;

        String wire() {
            return this == STRING_ARRAY ? "string[]" : name().toLowerCase();
        }
    }

    private record Arg(String name, Type type, boolean required, String def) {}

    private record Field(String name, Type type, boolean required) {}

    // ---- fluent builders ------------------------------------------------------------

    private static final class C {
        final String name;
        final List<Arg> args = new ArrayList<>();
        String result = "";

        C(String name) { this.name = name; }

        C req(String n, Type t) { args.add(new Arg(n, t, true, null)); return this; }
        C opt(String n, Type t) { args.add(new Arg(n, t, false, null)); return this; }
        C opt(String n, Type t, String def) { args.add(new Arg(n, t, false, def)); return this; }
        C result(String r) { this.result = r; return this; }
    }

    private static final class E {
        final String name;
        final String desc;
        final List<Field> fields = new ArrayList<>();

        E(String name, String desc) { this.name = name; this.desc = desc; }

        E f(String n, Type t) { fields.add(new Field(n, t, true)); return this; }
        E opt(String n, Type t) { fields.add(new Field(n, t, false)); return this; }
    }

    private static C c(String name) { return new C(name); }
    private static E e(String name, String desc) { return new E(name, desc); }

    // ---- the contract ---------------------------------------------------------------

    private static final List<C> COMMANDS = List.of(
            // --- core / lifecycle / introspection ---
            c("ping").result("{pong,time}"),
            c("help").result("{<command>:<summary>,...}"),
            c("schema").result("{protocol,server,auth,envelope,commands,events,errors}"),
            c("stats").result("{uptimeMs,cpuMs?,soundsSuppressed,skippedFrames,disableSound,noGl,muteAudio}"),
            c("optimize").opt("sound", Type.BOOL).result("{disableSound}"),
            c("subscribe").opt("events", Type.STRING_ARRAY).result("{subscribed:[...]}"),
            c("unsubscribe").result("{unsubscribed}"),
            c("events").result("{events:{<name>:<desc>},subscribeAll}"),
            c("status").result("{headless,noGl,noWindow,inWorld,player?{...},server?,nav*,screenshotBackend,...}"),
            c("auth.status").result("{username,uuid,type}"),
            c("connect").req("host", Type.STRING).opt("port", Type.INT, "25565").result("{connecting,host,port}"),
            c("disconnect").result("{disconnected}"),
            c("chat").req("message", Type.STRING).result("{sent}"),
            c("look").opt("yaw", Type.FLOAT).opt("pitch", Type.FLOAT).result("{yaw,pitch}"),
            c("players").result("[{name,id,ping}]"),
            c("headless").opt("enabled", Type.BOOL).result("{headless}"),
            c("screenshot")
                    .opt("x", Type.DOUBLE).opt("y", Type.DOUBLE).opt("z", Type.DOUBLE)
                    .opt("yaw", Type.FLOAT).opt("pitch", Type.FLOAT)
                    .opt("width", Type.INT).opt("height", Type.INT).opt("fov", Type.FLOAT)
                    .result("{format,backend,bytes,base64}"),

            // --- navigation (Baritone) ---
            c("goto").req("x", Type.INT).req("z", Type.INT).opt("y", Type.INT).result("{pathing,backend}"),
            c("baritone").req("command", Type.STRING).result("{ran,backend}"),
            c("nav.stop").result("{stopped}"),
            c("nav.status").result("{available,backend,active}"),

            // --- movement / actuation ---
            c("move")
                    .opt("forward", Type.BOOL).opt("backward", Type.BOOL)
                    .opt("left", Type.BOOL).opt("right", Type.BOOL)
                    .opt("jump", Type.BOOL).opt("sneak", Type.BOOL).opt("sprint", Type.BOOL)
                    .opt("durationMs", Type.INT).result("{moving}"),
            c("stopMove").result("{stopped}"),
            c("mine").req("x", Type.INT).req("y", Type.INT).req("z", Type.INT)
                    .opt("face", Type.STRING, "up").result("{mining}"),
            c("stopMine").result("{stopped}"),
            c("breakBlock").req("x", Type.INT).req("y", Type.INT).req("z", Type.INT).result("{broken}"),
            c("place").req("x", Type.INT).req("y", Type.INT).req("z", Type.INT)
                    .opt("face", Type.STRING, "up").result("{placed}"),
            c("use").opt("hand", Type.STRING, "main").result("{used}"),
            c("attack").opt("entityId", Type.INT).result("{attacked,type}"),
            c("setSlot").req("slot", Type.INT).result("{slot}"),
            c("dropItem").opt("all", Type.BOOL).result("{dropped}"),
            c("inventory").result("{selectedSlot,items:[{slot,item,name,count}]}"),
            c("entities").opt("radius", Type.DOUBLE, "16").result("[{id,type,name,x,y,z,distance}]"),
            c("blockAt").req("x", Type.INT).req("y", Type.INT).req("z", Type.INT).result("{block,air}"),
            c("interactEntity").req("entityId", Type.INT).opt("hand", Type.STRING, "main")
                    .result("{interacted,type,result}"),
            c("swapHands").result("{swapped}"),
            c("pickBlock").req("x", Type.INT).req("y", Type.INT).req("z", Type.INT)
                    .opt("nbt", Type.BOOL).result("{picked}"),
            c("useHold").opt("ticks", Type.INT, "40").result("{holding}"),
            c("useRelease").result("{released}"),
            c("eat").opt("ticks", Type.INT, "40").result("{eating}"),
            c("respawn").result("{respawned}"),

            // --- containers / screens / inventory transfer ---
            c("container").result("{handler,syncId,screen,slots,cursor,trades?}"),
            c("clickSlot").req("slot", Type.INT).opt("button", Type.INT, "0")
                    .opt("mode", Type.STRING, "pickup").result("{clicked,mode,cursor}"),
            c("closeScreen").result("{closed}"),
            c("selectTrade").req("index", Type.INT).result("{selected}"),
            c("screen").result("{screen,widgets:[{index,type,text,x,y,active,visible}]}"),
            c("clickButton").req("index", Type.INT).result("{clicked,text}"),
            c("setText").req("index", Type.INT).opt("text", Type.STRING, "").result("{set}"),
            c("serverui").result("{title,bossBars,scoreboards,sidebar}"),
            c("findItem").req("item", Type.STRING).result("{total,slots:[{slot,count}]}"),
            c("equip").req("item", Type.STRING).result("{equipped,fromSlot}"),
            c("deposit").req("item", Type.STRING).result("{deposited}"),
            c("withdraw").req("item", Type.STRING).result("{withdrew}"),
            c("dropStack").req("item", Type.STRING).result("{dropped}")
    );

    private static final List<E> EVENTS = List.of(
            e("welcome", "sent once on connect, before auth")
                    .f("server", Type.STRING).f("protocol", Type.INT).f("requiresAuth", Type.BOOL),
            e("chat", "a chat or system message was received")
                    .f("text", Type.STRING).opt("sender", Type.STRING).f("kind", Type.STRING),
            e("health", "health or hunger changed").f("health", Type.FLOAT).f("food", Type.INT),
            e("damage", "the bot took damage").f("amount", Type.FLOAT).f("health", Type.FLOAT),
            e("death", "the bot died"),
            e("respawn", "the bot respawned"),
            e("join", "a player entered the tab list").f("name", Type.STRING),
            e("leave", "a player left the tab list").f("name", Type.STRING),
            e("connected", "the bot connected to a server"),
            e("disconnected", "the bot left a server"),
            e("tick", "throttled state snapshot (~1/s)")
                    .f("x", Type.DOUBLE).f("y", Type.DOUBLE).f("z", Type.DOUBLE)
                    .f("yaw", Type.FLOAT).f("pitch", Type.FLOAT)
                    .f("health", Type.FLOAT).f("food", Type.INT).f("dimension", Type.STRING),
            e("screenOpen", "a container/screen opened").f("screen", Type.STRING),
            e("screenClose", "the open screen closed").f("screen", Type.STRING),
            e("entitySpawn", "an entity appeared nearby (within 24 blocks)")
                    .f("id", Type.INT).f("type", Type.STRING).f("x", Type.DOUBLE).f("y", Type.DOUBLE).f("z", Type.DOUBLE),
            e("entityRemove", "a nearby entity left").f("id", Type.INT),
            e("auth.prompt", "device-code login: show this to the user (always delivered)")
                    .f("verificationUri", Type.STRING).f("userCode", Type.STRING),
            e("auth.ok", "login succeeded (always delivered)"),
            e("auth.error", "login failed (always delivered)").opt("error", Type.STRING)
    );

    // ---- serialization --------------------------------------------------------------

    /**
     * Serializes the full contract. {@code help} is the dispatcher's name→summary map (the single
     * source of each command's one-line summary).
     */
    public static JsonObject toJson(Map<String, String> help) {
        JsonObject root = new JsonObject();
        root.addProperty("protocol", PROTOCOL_VERSION);
        root.addProperty("server", "MezzoSopranoClef");

        JsonObject auth = new JsonObject();
        auth.addProperty("requiredWhen", "control.authToken is set (welcome.requiresAuth = true)");
        auth.addProperty("handshake", "{\"cmd\":\"hello\",\"args\":{\"token\":\"...\"}}");
        root.add("auth", auth);

        JsonObject envelope = new JsonObject();
        envelope.addProperty("request", "{id?, cmd, args?}");
        envelope.addProperty("response", "{id?, ok:true, result} | {id?, ok:false, code, error}");
        envelope.addProperty("event", "{event, data}");
        root.add("envelope", envelope);

        root.add("commands", commandsJson(help));
        root.add("events", eventsJson());
        root.add("errors", errorsJson());
        return root;
    }

    private static JsonArray commandsJson(Map<String, String> help) {
        JsonArray arr = new JsonArray();
        for (C cmd : COMMANDS) {
            JsonObject jc = new JsonObject();
            jc.addProperty("name", cmd.name);
            jc.addProperty("summary", help == null ? "" : help.getOrDefault(cmd.name, ""));
            JsonArray ja = new JsonArray();
            for (Arg a : cmd.args) {
                JsonObject jo = new JsonObject();
                jo.addProperty("name", a.name);
                jo.addProperty("type", a.type.wire());
                jo.addProperty("required", a.required);
                if (a.def != null) jo.addProperty("default", a.def);
                ja.add(jo);
            }
            jc.add("args", ja);
            if (!cmd.result.isEmpty()) jc.addProperty("result", cmd.result);
            arr.add(jc);
        }
        return arr;
    }

    private static JsonArray eventsJson() {
        JsonArray arr = new JsonArray();
        for (E ev : EVENTS) {
            JsonObject je = new JsonObject();
            je.addProperty("name", ev.name);
            je.addProperty("description", ev.desc);
            JsonArray jf = new JsonArray();
            for (Field f : ev.fields) {
                JsonObject jo = new JsonObject();
                jo.addProperty("name", f.name);
                jo.addProperty("type", f.type.wire());
                jo.addProperty("required", f.required);
                jf.add(jo);
            }
            je.add("fields", jf);
            arr.add(je);
        }
        return arr;
    }

    private static JsonArray errorsJson() {
        JsonArray arr = new JsonArray();
        for (ErrorCode code : ErrorCode.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("code", code.name());
            o.addProperty("meaning", code.meaning);
            arr.add(o);
        }
        return arr;
    }

    /** Compact {@code name -> description} object for the legacy {@code events} command shape. */
    public static JsonObject eventDescriptions() {
        JsonObject o = new JsonObject();
        for (E ev : EVENTS) o.addProperty(ev.name, ev.desc);
        return o;
    }

    /** Names of every declared command — used by the drift-guard test against the dispatcher. */
    public static TreeSet<String> commandNames() {
        TreeSet<String> names = new TreeSet<>();
        for (C cmd : COMMANDS) names.add(cmd.name);
        return names;
    }

    private ApiSchema() {}
}
