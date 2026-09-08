package dev.mezzo.clef.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
     *
     * <p><b>2</b> — the {@code chat} event's {@code kind} became a closed enum
     * ({@code chat|system|whisper|team|actionbar}); it previously reported the literal
     * {@code "game"} for everything unsigned. {@code status} kept every field it had and gained a
     * sibling {@code world} object. Everything else in this version was additive.</p>
     */
    public static final int PROTOCOL_VERSION = 2;

    /** Argument / field value types, rendered to a stable lowercase wire string. */
    public enum Type {
        INT, LONG, DOUBLE, FLOAT, BOOL, STRING, STRING_ARRAY, OBJECT_ARRAY, INT_ARRAY, DOUBLE_ARRAY;

        String wire() {
            return switch (this) {
                case STRING_ARRAY -> "string[]";
                case OBJECT_ARRAY -> "object[]";
                case INT_ARRAY -> "int[]";
                case DOUBLE_ARRAY -> "double[]";
                default -> name().toLowerCase();
            };
        }
    }

    public record ArgSpec(String name, Type type, boolean required, String def) {}

    private record Field(String name, Type type, boolean required) {}

    // ---- fluent builders ------------------------------------------------------------

    private static final class C {
        final String name;
        final List<ArgSpec> args = new ArrayList<>();
        String result = "";

        C(String name) { this.name = name; }

        C req(String n, Type t) { args.add(new ArgSpec(n, t, true, null)); return this; }
        C opt(String n, Type t) { args.add(new ArgSpec(n, t, false, null)); return this; }
        C opt(String n, Type t, String def) { args.add(new ArgSpec(n, t, false, def)); return this; }
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
            c("stats").result("{uptimeMs,cpuMs?,soundsSuppressed,skippedFrames,commandsHandled,commandsFailed,commandAvgMs,disableSound,noGl,muteAudio}"),
            c("optimize").opt("sound", Type.BOOL).result("{disableSound}"),
            c("subscribe").opt("events", Type.STRING_ARRAY).result("{subscribed:[...]}"),
            c("unsubscribe").result("{unsubscribed}"),
            c("events").result("{events:{<name>:<desc>},subscribeAll}"),
            // main enriched this description; keep its detail plus our protocol block.
            c("status").result("{headless,noGl,noWindow,screen,screenClass,inWorld,"
                    + "player?{...,armor,effects,gamemode,air,...},"
                    + "world?{time,weather,biome,light},server?,nav*,screenshotBackend,"
                    + "protocol{available,native,target},...}"),
            c("auth.status").result("{username,uuid,type}"),
            c("control.rotateToken").result("{rotated,authToken}"),
            c("connect").req("host", Type.STRING).opt("port", Type.INT, "25565")
                    .opt("version", Type.STRING, "auto").result("{connecting,host,port,version}"),
            c("protocol").result("{available,native,target,reason?,versions:[{name,protocol}]}"),
            c("disconnect").result("{disconnected}"),
            c("chat").req("message", Type.STRING).result("{sent}"),
            c("chatHistory").opt("limit", Type.INT, "50")
                    .result("{lines:[{time,kind:chat|system|whisper|team|actionbar,sender?,text}],stored} "
                            + "— an object, not a bare array; oldest line first"),
            c("whisper").req("player", Type.STRING).req("text", Type.STRING).result("{sent,command,to}"),
            c("batch").req("commands", Type.OBJECT_ARRAY).opt("continueOnError", Type.BOOL, "false")
                    .result("{results:[{cmd,ok,result?|code,error}],ran,requested}"),
            c("look").opt("yaw", Type.FLOAT).opt("pitch", Type.FLOAT).result("{yaw,pitch}"),
            c("lookAt").opt("x", Type.DOUBLE).opt("y", Type.DOUBLE).opt("z", Type.DOUBLE)
                    .opt("entityId", Type.INT).result("{yaw,pitch,distance}"),
            c("players").result("[{name,id,ping}]"),
            c("headless").opt("enabled", Type.BOOL).result("{headless}"),
            c("screenshot")
                    .opt("x", Type.DOUBLE).opt("y", Type.DOUBLE).opt("z", Type.DOUBLE)
                    .opt("yaw", Type.FLOAT).opt("pitch", Type.FLOAT)
                    .opt("width", Type.INT).opt("height", Type.INT).opt("fov", Type.FLOAT)
                    .opt("mode", Type.STRING, "normal")
                    .opt("centerX", Type.DOUBLE).opt("centerZ", Type.DOUBLE).opt("radius", Type.INT, "32")
                    .opt("annotate", Type.BOOL, "false")
                    .result("{format,backend,mode,bytes,width,height,durationMs,snapshotMs,renderMs,pngMs,"
                            + "camera?,entities?:[{id,type,visible,box,depth}],base64}"),
            c("registry").opt("kinds", Type.STRING_ARRAY).opt("tags", Type.BOOL, "false")
                    .result("{minecraftVersion,blocks?,items?,entities?,tags?}"),

            // --- navigation (Baritone) ---
            c("goto").req("x", Type.INT).req("z", Type.INT).opt("y", Type.INT).opt("reach", Type.INT, "1")
                    .result("{pathing,reach,backend}"),
            c("nav.check").req("x", Type.INT).req("y", Type.INT).req("z", Type.INT)
                    .opt("reach", Type.INT, "1").opt("maxNodes", Type.INT)
                    .result("{verdict:reachable|unreachable|unknown,reachable,cost?,nodes,maxNodes,exhausted,model} "
                            + "— an estimate; 'unknown' means the search ran out of budget, NOT that "
                            + "there is no route"),
            c("baritone").req("command", Type.STRING).opt("collectMs", Type.INT, "250")
                    .result("{ran,backend,output:[lines Baritone printed]}"),
            c("nav.stop").result("{stopped}"),
            c("nav.status").result("{available,backend,active,blockArguments?,goal?}"),

            // --- movement / actuation ---
            c("move")
                    .opt("forward", Type.BOOL).opt("backward", Type.BOOL)
                    .opt("left", Type.BOOL).opt("right", Type.BOOL)
                    .opt("jump", Type.BOOL).opt("sneak", Type.BOOL).opt("sprint", Type.BOOL)
                    .opt("durationMs", Type.INT).result("{moving}"),
            c("stopMove").result("{stopped}"),
            c("mine").req("x", Type.INT).req("y", Type.INT).req("z", Type.INT)
                    .opt("face", Type.STRING, "up").opt("wait", Type.BOOL, "false")
                    .result("{mining} | {x,y,z,mining:false,broken,reason?,detail?,ticks}"),
            c("stopMine").result("{stopped}"),
            c("breakBlock").req("x", Type.INT).req("y", Type.INT).req("z", Type.INT).result("{broken}"),
            c("place").req("x", Type.INT).req("y", Type.INT).req("z", Type.INT)
                    .opt("face", Type.STRING, "up").opt("item", Type.STRING).opt("confirm", Type.BOOL, "true")
                    .result("{placed,confirmed,result,ticks?,slot?,x?,y?,z?,block?}"),
            c("use").opt("hand", Type.STRING, "main").result("{used}"),
            c("attack").opt("entityId", Type.INT).result("{attacked,type}"),
            c("setSlot").req("slot", Type.INT).result("{slot}"),
            c("dropItem").opt("all", Type.BOOL).result("{dropped}"),
            c("inventory").result("{selectedSlot,items:[{slot,item,name,count}]}"),
            c("entities").opt("radius", Type.DOUBLE, "16").opt("kinds", Type.STRING_ARRAY)
                    .result("[{id,type,name,x,y,z,distance,vx,vy,vz,velocity:[vx,vy,vz],onFire,health?,"
                            + "maxHealth?,armor?,baby?,held?,lookingAtMe?,hostile,targetingMe,item?,"
                            + "villager?,owner?}] — vx/vy/vz are blocks per tick as the client "
                            + "observed the entity move, not the raw velocity field, which is zero "
                            + "for anything the server drives"),
            c("blockAt").req("x", Type.INT).req("y", Type.INT).req("z", Type.INT).result("{block,air}"),
            c("findBlocks").req("ids", Type.STRING_ARRAY).opt("radius", Type.INT, "32")
                    .opt("max", Type.INT, "32").opt("sort", Type.STRING, "nearest")
                    .result("[{x,y,z,block,distance}]"),
            c("blocksIn").req("minX", Type.INT).req("minY", Type.INT).req("minZ", Type.INT)
                    .req("maxX", Type.INT).req("maxY", Type.INT).req("maxZ", Type.INT)
                    .opt("palette", Type.BOOL, "true")
                    .result("{size,origin,order,palette?,encoding?,data?,blocks?}"),
            c("target").opt("maxDistance", Type.DOUBLE, "4.5").opt("fluids", Type.BOOL, "false")
                    .result("{kind:block|entity|none,x?,y?,z?,block?,face?,entityId?,type?,distance?}"),
            c("interactEntity").req("entityId", Type.INT).opt("hand", Type.STRING, "main")
                    .result("{interacted,type,result}"),
            c("swapHands").result("{swapped}"),
            c("pickBlock").req("x", Type.INT).req("y", Type.INT).req("z", Type.INT)
                    .opt("nbt", Type.BOOL).result("{picked}"),
            c("useHold").opt("ticks", Type.INT, "40").result("{holding}"),
            c("useRelease").result("{released}"),
            c("eat").opt("ticks", Type.INT, "40").result("{eating}"),
            c("respawn").result("{respawned}"),

            // --- crafting ---
            c("craft").req("item", Type.STRING).opt("count", Type.INT, "1").opt("all", Type.BOOL, "false")
                    .result("{crafted,item,reason?}"),
            c("recipes").req("item", Type.STRING)
                    .result("[{result,count,recipeId,kind,ingredients:[{item?,items,tag?,count}],needsTable,"
                            + "width?,height?,station?}]"),
            c("craftable").result("{grid,knownRecipes,items:[{item,count,fitsOpenGrid}]}"),

            // --- combat loops that run on the body, at 20 Hz ---
            c("shootAt").req("entityId", Type.INT).opt("lead", Type.BOOL, "true")
                    .opt("charge", Type.INT, "25").opt("shots", Type.INT, "1")
                    .opt("maxRange", Type.DOUBLE, "64").opt("wait", Type.BOOL, "true")
                    .result("{kind,entityId,fired,hits,damage,killed,stopped,detail?,ticks} — or "
                            + "{started,kind,entityId} with wait:false, where the same shape arrives "
                            + "as the combatDone event"),
            c("meleeWhile").req("entityId", Type.INT).opt("maxMs", Type.INT, "5000")
                    .opt("reach", Type.DOUBLE, "3.5").opt("stopBelowHealth", Type.DOUBLE)
                    .opt("wait", Type.BOOL, "true")
                    .result("{kind,entityId,fired,hits,damage,killed,stopped,detail?,ticks} — or "
                            + "{started,kind,entityId} with wait:false"),
            c("combat.stop").result("{stopped,kind?}"),
            c("combat.status").result("{busy,kind?}"),

            // --- containers / screens / inventory transfer ---
            c("container").result("{handler,syncId,screen,screenClass,slots,cursor,trades?}"),
            c("clickSlot").req("slot", Type.INT).opt("button", Type.INT, "0")
                    .opt("mode", Type.STRING, "pickup").result("{clicked,mode,cursor}"),
            c("closeScreen").result("{closed,wasOpen,screen,stillOpen?} — closed is read back "
                    + "after the attempt, not assumed; stillOpen names what refused to go"),
            c("selectTrade").req("index", Type.INT).result("{selected}"),
            c("screen").result("{screen,screenClass,widgets:[{index,type,text,x,y,active,visible}]}"),
            c("clickButton").req("index", Type.INT).result("{clicked,text}"),
            c("setText").req("index", Type.INT).opt("text", Type.STRING, "").result("{set}"),
            c("serverui").result("{title,bossBars,scoreboards,sidebar}"),
            c("findItem").req("item", Type.STRING).result("{total,slots:[{slot,count}]}"),
            c("equip").req("item", Type.STRING)
                    .result("{equipped,changed,worn,moved,slot?,fromSlot?,detail?} — changed is read "
                            + "back from the equipment slot, never assumed from the click: already worn "
                            + "is {changed:false,worn:true}, and a shift-click with nowhere to go is "
                            + "{changed:false,moved:false,detail}"),
            c("moveToHotbar").req("item", Type.STRING).opt("slot", Type.INT).result("{slot,moved,fromSlot}"),
            c("deposit").req("item", Type.STRING).result("{deposited}"),
            c("withdraw").req("item", Type.STRING).result("{withdrew}"),
            c("dropStack").req("item", Type.STRING).result("{dropped}")
    );

    private static final List<E> EVENTS = List.of(
            e("welcome", "sent once on connect, before auth")
                    .f("server", Type.STRING).f("protocol", Type.INT).f("requiresAuth", Type.BOOL),
            e("chat", "a chat, system, whisper, team or action-bar message arrived; "
                    + "'raw' is the JSON component, 'kind' is chat|system|whisper|team|actionbar")
                    .f("text", Type.STRING).opt("sender", Type.STRING).f("kind", Type.STRING)
                    .opt("raw", Type.STRING),
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
            e("screenOpen", "a container/screen opened. 'screen' is a stable readable name "
                    + "(title|connect|disconnected|death|downloading|message|inventory|crafting|furnace|"
                    + "anvil|enchanting|merchant|sign|book|container|other); 'screenClass' is the raw "
                    + "class, which is obfuscated outside a dev run")
                    .f("screen", Type.STRING).f("screenClass", Type.STRING),
            e("screenClose", "the open screen closed")
                    .f("screen", Type.STRING).f("screenClass", Type.STRING),
            e("entitySpawn", "an entity appeared nearby (within 24 blocks)")
                    .f("id", Type.INT).f("type", Type.STRING).f("x", Type.DOUBLE).f("y", Type.DOUBLE).f("z", Type.DOUBLE),
            e("entityRemove", "a nearby entity left").f("id", Type.INT),
            e("entityHurt", "an entity within events.packetRadius took damage — including us; "
                    + "'attacker' is the entity id that dealt it, which is what self-defence needs")
                    .f("id", Type.INT).f("type", Type.STRING).f("self", Type.BOOL).f("health", Type.FLOAT)
                    .opt("source", Type.STRING).opt("attacker", Type.INT).opt("attackerType", Type.STRING),
            e("itemPickup", "the bot picked an item up").f("item", Type.STRING).f("count", Type.INT),
            e("inventory", "inventory slots changed this tick; fetch values with the inventory command")
                    .f("changed", Type.INT_ARRAY),
            e("blockUpdate", "a block changed within events.blockUpdateRadius of the bot")
                    .f("x", Type.INT).f("y", Type.INT).f("z", Type.INT)
                    .f("from", Type.STRING).f("to", Type.STRING),
            e("explosion", "an explosion went off within events.packetRadius. 1.21.8's packet carries "
                    + "no blast power, so 'knockback' (the impulse applied to us) is the only magnitude")
                    .f("x", Type.DOUBLE).f("y", Type.DOUBLE).f("z", Type.DOUBLE).f("distance", Type.DOUBLE)
                    .opt("knockback", Type.DOUBLE_ARRAY),
            e("weather", "weather changed: clear | rain | thunder").f("kind", Type.STRING),
            e("time", "the day phase changed: dawn | day | dusk | night")
                    .f("phase", Type.STRING).f("time", Type.LONG).f("day", Type.LONG),
            e("sleep", "the bot fell asleep (ok:true) or a bed was refused (ok:false, reason from the "
                    + "server's block.minecraft.bed.* message)")
                    .f("ok", Type.BOOL).opt("reason", Type.STRING).opt("key", Type.STRING)
                    .opt("x", Type.INT).opt("y", Type.INT).opt("z", Type.INT),
            e("title", "the title, subtitle or action bar text changed")
                    .f("title", Type.STRING).f("subtitle", Type.STRING).f("actionBar", Type.STRING),
            e("mineDone", "a mine request finished; reason is cant_break | cancelled | replaced | not_in_world")
                    .f("x", Type.INT).f("y", Type.INT).f("z", Type.INT).f("broken", Type.BOOL)
                    .opt("reason", Type.STRING).opt("detail", Type.STRING).f("ticks", Type.INT),
            e("combatDone", "a shootAt / meleeWhile finished. 'stopped' is done | dead | gone | "
                    + "timeout | range | health | blocked | out_of_ammo | cancelled | replaced. "
                    + "'hits' is melee swings for meleeWhile, and for shootAt the number of times "
                    + "the target's health dropped during the run — evidence, not a hit registry, "
                    + "since damage from any source counts")
                    .f("kind", Type.STRING).f("entityId", Type.INT).f("fired", Type.INT)
                    .f("hits", Type.INT).f("damage", Type.DOUBLE).f("killed", Type.BOOL)
                    .f("stopped", Type.STRING).opt("detail", Type.STRING).f("ticks", Type.INT),
            e("nav.done", "the bot reached its goto goal")
                    .f("x", Type.INT).opt("y", Type.INT).f("z", Type.INT).f("reach", Type.INT),
            e("nav.failed", "the goal is over and was NOT reached. Always terminal — no nav.done can "
                    + "follow for the same goal. reason is a terminal Baritone PathEvent (CALC_FAILED, "
                    + "CANCELED) or CANCELLED | NO_PATH | REPLACED")
                    .f("x", Type.INT).opt("y", Type.INT).f("z", Type.INT).f("reach", Type.INT)
                    .f("reason", Type.STRING).f("terminal", Type.BOOL),
            e("nav.progress", "Baritone is still working on the goal and something notable happened "
                    + "(NEXT_CALC_FAILED, SPLICING_ONTO_NEXT_EARLY, ...). Informational: the goal is "
                    + "still live, so do NOT treat this as a failure")
                    .f("x", Type.INT).opt("y", Type.INT).f("z", Type.INT).f("reach", Type.INT)
                    .f("event", Type.STRING),
            e("baritone.log", "a line Baritone would have printed to the chat HUD — find results, "
                    + "eta, 'No known locations of ...', build progress, missing materials")
                    .f("text", Type.STRING),
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
            for (ArgSpec a : cmd.args) {
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

    public static Map<String, List<ArgSpec>> commandArgs() {
        Map<String, List<ArgSpec>> out = new LinkedHashMap<>();
        for (C cmd : COMMANDS) out.put(cmd.name, List.copyOf(cmd.args));
        return out;
    }

    public static void validateArgs(String command, JsonObject args) {
        List<ArgSpec> specs = commandArgs().get(command);
        if (specs == null) return; // dispatcher will return UNKNOWN_COMMAND.
        JsonObject safeArgs = args == null ? new JsonObject() : args;
        for (ArgSpec spec : specs) {
            JsonElement value = safeArgs.get(spec.name());
            if (value == null || value.isJsonNull()) {
                if (spec.required()) throw ApiException.badArgs("missing required arg: " + spec.name());
                continue;
            }
            if (!matchesType(value, spec.type())) {
                throw ApiException.badArgs("arg '" + spec.name() + "' must be " + spec.type().wire());
            }
        }
    }

    private static boolean matchesType(JsonElement value, Type type) {
        if (type == Type.STRING_ARRAY) {
            if (!value.isJsonArray()) return false;
            for (JsonElement e : value.getAsJsonArray()) {
                if (!isString(e)) return false;
            }
            return true;
        }
        if (type == Type.OBJECT_ARRAY) {
            if (!value.isJsonArray()) return false;
            for (JsonElement e : value.getAsJsonArray()) {
                if (!e.isJsonObject()) return false;
            }
            return true;
        }
        if (type == Type.INT_ARRAY || type == Type.DOUBLE_ARRAY) {
            if (!value.isJsonArray()) return false;
            for (JsonElement e : value.getAsJsonArray()) {
                if (type == Type.INT_ARRAY ? !isIntegral(e) : !isNumber(e)) return false;
            }
            return true;
        }
        if (!value.isJsonPrimitive()) return false;
        return switch (type) {
            case INT, LONG -> isIntegral(value);
            case DOUBLE, FLOAT -> isNumber(value);
            case BOOL -> value.getAsJsonPrimitive().isBoolean();
            case STRING -> isString(value);
            case STRING_ARRAY, OBJECT_ARRAY, INT_ARRAY, DOUBLE_ARRAY -> false;
        };
    }

    private static boolean isString(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    private static boolean isNumber(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
    }

    private static boolean isIntegral(JsonElement value) {
        if (!isNumber(value)) return false;
        try {
            value.getAsLong();
            double d = value.getAsDouble();
            return Double.isFinite(d) && d == Math.rint(d);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private ApiSchema() {}
}
