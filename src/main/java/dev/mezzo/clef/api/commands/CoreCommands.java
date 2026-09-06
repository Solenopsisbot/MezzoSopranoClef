package dev.mezzo.clef.api.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.api.ApiException;
import dev.mezzo.clef.api.ApiSchema;
import dev.mezzo.clef.api.CommandContext;
import dev.mezzo.clef.api.CommandDispatcher;
import dev.mezzo.clef.api.ErrorCode;
import dev.mezzo.clef.api.ScreenNames;
import dev.mezzo.clef.bot.ServerConnector;
import dev.mezzo.clef.version.ProtocolBridge;
import dev.mezzo.clef.config.ClefConfig;
import dev.mezzo.clef.headless.HeadlessController;
import dev.mezzo.clef.nav.BaritoneNavigator;
import dev.mezzo.clef.nav.Navigator;
import dev.mezzo.clef.screenshot.ScreenshotService;
import dev.mezzo.clef.bot.EventEmitter;
import java.util.Base64;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.LightLayer;

/** Registers the built-in control-plane commands. Add your own bot behaviours here. */
public final class CoreCommands {

    public static void registerAll(CommandDispatcher d) {

        d.register("ping", "liveness check", ctx -> {
            JsonObject o = new JsonObject();
            o.addProperty("pong", true);
            o.addProperty("time", System.currentTimeMillis());
            return o;
        });

        d.register("help", "list all commands", ctx -> {
            JsonObject o = new JsonObject();
            ctx.server.dispatcher().help().forEach(o::addProperty);
            return o;
        });

        d.register("schema",
                "machine-readable API contract: commands+args, events+fields, error codes, protocol version",
                ctx -> ApiSchema.toJson(ctx.server.dispatcher().help()));

        d.register("stats", "runtime perf counters: process CPU time, suppressed sound work, skipped frames", ctx -> {
            HeadlessController hc = HeadlessController.get();
            JsonObject o = new JsonObject();
            o.addProperty("uptimeMs", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());
            ProcessHandle.current().info().totalCpuDuration()
                    .ifPresent(cpu -> o.addProperty("cpuMs", cpu.toMillis()));
            o.addProperty("soundsSuppressed", hc.soundsSuppressed());
            o.addProperty("skippedFrames", hc.skippedFrames());
            o.addProperty("commandsHandled", ctx.server.commandsHandled());
            o.addProperty("commandsFailed", ctx.server.commandsFailed());
            long handled = Math.max(1, ctx.server.commandsHandled());
            o.addProperty("commandAvgMs", (ctx.server.commandTotalNanos() / 1_000_000.0) / handled);
            o.addProperty("disableSound", hc.isDisableSound());
            o.addProperty("noGl", hc.isNoGl());
            o.addProperty("muteAudio", hc.isMuteAudio());
            return o;
        });

        d.register("optimize", "toggle the muted-sound short-circuit at runtime {sound?}", ctx -> {
            HeadlessController hc = HeadlessController.get();
            if (ctx.has("sound")) hc.setDisableSound(ctx.bool("sound", true));
            JsonObject o = new JsonObject();
            o.addProperty("disableSound", hc.isDisableSound());
            return o;
        });

        d.register("subscribe", "stream events to this connection {events:[...] or omit for all}", ctx -> {
            java.util.Set<String> subs = java.util.concurrent.ConcurrentHashMap.newKeySet();
            if (ctx.args.has("events") && ctx.args.get("events").isJsonArray()) {
                ctx.args.getAsJsonArray("events").forEach(e -> subs.add(e.getAsString()));
            } else {
                subs.add("*");
            }
            ctx.origin.attributes.put("subs", subs);
            JsonObject o = new JsonObject();
            JsonArray a = new JsonArray();
            subs.forEach(a::add);
            o.add("subscribed", a);
            return o;
        });

        d.register("unsubscribe", "stop streaming events to this connection", ctx -> {
            ctx.origin.attributes.remove("subs");
            JsonObject o = new JsonObject();
            o.addProperty("unsubscribed", true);
            return o;
        });

        d.register("events", "list every event type you can subscribe to", ctx -> {
            // Sourced from ApiSchema (single source of truth); `schema` returns the field shapes too.
            JsonObject o2 = new JsonObject();
            o2.add("events", ApiSchema.eventDescriptions());
            o2.addProperty("subscribeAll", "subscribe with no args (or events:['*']) for everything");
            return o2;
        });

        d.register("status", "bot, world and player status", ctx -> ctx.onMain(() -> {
            Minecraft mc = Minecraft.getInstance();
            HeadlessController hc = HeadlessController.get();
            JsonObject o = new JsonObject();
            o.addProperty("headless", hc.isHeadless());
            o.addProperty("noGl", hc.isNoGl());             // true = booted GPU-free (no OpenGL context)
            o.addProperty("noWindow", hc.isNoWindow());     // true = GLFW null platform (no display server)
            o.addProperty("skippedFrames", hc.skippedFrames());
            o.addProperty("controllers", ctx.server.connectionCount());
            o.addProperty("singleplayer", mc.isLocalServer());
            // Readable and remap-proof: the shipped client is obfuscated, so the raw class name is
            // `class_424` in production. `screenClass` keeps the raw one for modded screens.
            o.addProperty("screen", ScreenNames.of(mc.gui.screen()));
            o.addProperty("screenClass", ScreenNames.rawOf(mc.gui.screen()));
            o.addProperty("overlay", mc.gui.overlay() != null ? mc.gui.overlay().getClass().getSimpleName() : "none");
            if (mc.getCurrentServer() != null) {
                o.addProperty("server", mc.getCurrentServer().ip);
            }
            boolean inWorld = mc.level != null && mc.player != null;
            o.addProperty("inWorld", inWorld);
            if (inWorld) {
                o.add("player", playerStatus(mc));
                o.add("world", worldStatus(mc));
            }
            o.addProperty("navBackend", ctx.server.services.navigator.backend());
            o.addProperty("navActive", ctx.server.services.navigator.isActive());
            o.addProperty("screenshotBackend", ctx.server.services.screenshots.backend());
            o.add("protocol", ProtocolBridge.get().describeJson());
            return o;
        }));

        d.register("auth.status", "current logged-in identity", ctx -> ctx.onMain(() -> {
            User s = Minecraft.getInstance().getUser();
            JsonObject o = new JsonObject();
            o.addProperty("username", s.getName());
            o.addProperty("uuid", String.valueOf(s.getProfileId()));
            o.addProperty("type", (s.getProfileId() == null ? "LEGACY" : "MSA"));
            return o;
        }));

        d.register("control.rotateToken", "rotate the full-control WebSocket token and persist config", ctx -> {
            String token = ClefConfig.generateAuthToken();
            MezzoClef.config().control.authToken = token;
            MezzoClef.config().save(MezzoClef.configPath());
            JsonObject o = new JsonObject();
            o.addProperty("rotated", true);
            o.addProperty("authToken", token);
            return o;
        });

        d.register("connect", "join a server {host, port?, version?} — version: auto|native|1.12.2…", ctx -> {
            String host = ctx.requireStr("host");
            int port = ctx.i("port", 25565);
            String version = ctx.str("version", MezzoClef.config().connection.serverVersion);
            // Version selection can fail (unknown release, ViaFabricPlus missing); surface that as a
            // clean error instead of an async log line, so run it synchronously on the client thread.
            String selected = ctx.onMain(() -> {
                try {
                    return ServerConnector.connect(host, port, version);
                } catch (IllegalArgumentException | IllegalStateException e) {
                    throw ApiException.badArgs(e.getMessage());
                }
            });
            JsonObject o = new JsonObject();
            o.addProperty("connecting", true);
            o.addProperty("host", host);
            o.addProperty("port", port);
            o.addProperty("version", selected);
            return o;
        });

        d.register("protocol", "server-version support: ViaFabricPlus state, native version, current target, all joinable releases", ctx -> {
            ProtocolBridge bridge = ProtocolBridge.get();
            JsonObject o = bridge.describeJson();
            o.add("versions", bridge.supportedVersions());
            return o;
        });

        d.register("disconnect", "leave the current world", ctx -> ctx.onMain(() -> {
            // 26.x: disconnect(Screen returnTo, boolean transferring)
            Minecraft.getInstance().disconnect(new TitleScreen(), false);
            JsonObject o = new JsonObject();
            o.addProperty("disconnected", true);
            return o;
        }));

        d.register("chat", "send chat, or a /command if prefixed with '/' {message}", ctx -> {
            String msg = ctx.requireStr("message");
            return ctx.onMain(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.getConnection() == null) throw ApiException.notConnected();
                if (msg.startsWith("/")) mc.getConnection().sendCommand(msg.substring(1));
                else mc.getConnection().sendChat(msg);
                JsonObject o = new JsonObject();
                o.addProperty("sent", true);
                return o;
            });
        });

        d.register("look", "set view direction {yaw, pitch}", ctx -> {
            float yaw = ctx.has("yaw") ? ctx.f("yaw", 0) : Float.NaN;
            float pitch = ctx.has("pitch") ? ctx.f("pitch", 0) : Float.NaN;
            return ctx.onMain(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null) throw ApiException.notInWorld();
                if (!Float.isNaN(yaw)) {
                    mc.player.setYRot(yaw);
                    mc.player.setYHeadRot(yaw);
                    mc.player.setYBodyRot(yaw);
                }
                if (!Float.isNaN(pitch)) mc.player.setXRot(pitch);
                JsonObject o = new JsonObject();
                o.addProperty("yaw", mc.player.getYRot());
                o.addProperty("pitch", mc.player.getXRot());
                return o;
            });
        });

        d.register("players", "list tab-list players", ctx -> ctx.onMain(() -> {
            Minecraft mc = Minecraft.getInstance();
            JsonArray arr = new JsonArray();
            if (mc.getConnection() != null) {
                for (PlayerInfo e : mc.getConnection().getOnlinePlayers()) {
                    JsonObject p = new JsonObject();
                    p.addProperty("name", e.getProfile().name());
                    p.addProperty("id", e.getProfile().id().toString());
                    p.addProperty("ping", e.getLatency());
                    arr.add(p);
                }
            }
            return arr;
        }));

        d.register("headless", "toggle render-skip {enabled?}", ctx -> {
            if (ctx.has("enabled")) HeadlessController.get().setHeadless(ctx.bool("enabled", true));
            JsonObject o = new JsonObject();
            o.addProperty("headless", HeadlessController.get().isHeadless());
            return o;
        });

        d.register("screenshot",
                "render a PNG -> base64. {x,y,z,yaw,pitch,width,height,fov} for a free camera, "
                        + "{mode:'topdown',centerX,centerZ,radius} for an orthographic map, "
                        + "{annotate:true} to also get the camera and on-screen entity boxes",
                ctx -> {
                    String mode = ctx.str("mode", "normal");
                    if (!mode.equals("normal") && !mode.equals("topdown")) {
                        throw ApiException.badArgs("mode must be 'normal' or 'topdown'");
                    }
                    boolean annotate = ctx.bool("annotate", false);
                    ScreenshotService.CaptureRequest req = new ScreenshotService.CaptureRequest(
                            ctx.has("x") ? Double.valueOf(ctx.d("x", 0)) : null,
                            ctx.has("y") ? Double.valueOf(ctx.d("y", 0)) : null,
                            ctx.has("z") ? Double.valueOf(ctx.d("z", 0)) : null,
                            ctx.has("yaw") ? Float.valueOf(ctx.f("yaw", 0)) : null,
                            ctx.has("pitch") ? Float.valueOf(ctx.f("pitch", 0)) : null,
                            ctx.has("width") ? Integer.valueOf(ctx.i("width", 0)) : null,
                            ctx.has("height") ? Integer.valueOf(ctx.i("height", 0)) : null,
                            ctx.has("fov") ? Float.valueOf(ctx.f("fov", 0)) : null,
                            mode,
                            ctx.has("centerX") ? Double.valueOf(ctx.d("centerX", 0)) : null,
                            ctx.has("centerZ") ? Double.valueOf(ctx.d("centerZ", 0)) : null,
                            ctx.has("radius") ? Integer.valueOf(ctx.i("radius", 32)) : null,
                            annotate);
                    // capture() bounces to the client thread internally; software backend
                    // rasterizes off-thread so it won't stall ticks.
                    ScreenshotService.Capture capture = ctx.server.services.screenshots.captureAnnotated(req);
                    byte[] png = capture.png();
                    JsonObject o = new JsonObject();
                    o.addProperty("format", "png");
                    o.addProperty("backend", ctx.server.services.screenshots.backend());
                    o.addProperty("mode", mode);
                    o.addProperty("bytes", png.length);
                    ScreenshotService.CaptureMetrics m = capture.metrics();
                    if (m != null) {
                        o.addProperty("width", m.width());
                        o.addProperty("height", m.height());
                        o.addProperty("durationMs", m.totalMs());
                        o.addProperty("snapshotMs", m.snapshotMs());
                        o.addProperty("renderMs", m.renderMs());
                        o.addProperty("pngMs", m.pngMs());
                    }
                    if (annotate && capture.camera() != null) {
                        o.add("camera", cameraJson(capture.camera()));
                        JsonArray boxes = new JsonArray();
                        for (ScreenshotService.Annotation a : capture.annotations()) {
                            JsonObject box = new JsonObject();
                            box.addProperty("id", a.id());
                            box.addProperty("type", a.type());
                            box.addProperty("visible", a.visible());
                            if (a.visible()) {
                                JsonArray rect = new JsonArray();
                                rect.add(a.minX());
                                rect.add(a.minY());
                                rect.add(a.maxX());
                                rect.add(a.maxY());
                                box.add("box", rect);
                                box.addProperty("depth", a.depth());
                            }
                            boxes.add(box);
                        }
                        o.add("entities", boxes);
                    }
                    o.addProperty("base64", Base64.getEncoder().encodeToString(png));
                    return o;
                });

        d.register("goto",
                "Baritone path to {x,y,z} or {x,z}, within {reach?=1} blocks — completion arrives as nav.done/nav.failed",
                ctx -> {
                    Navigator nav = ctx.server.services.navigator;
                    int x = ctx.requireInt("x");
                    int z = ctx.requireInt("z");
                    boolean hasY = ctx.has("y");
                    int y = ctx.i("y", 0);
                    int reach = Math.max(1, ctx.i("reach", 1));
                    ctx.server.services.input.clear(); // hand movement to Baritone; stop fighting it
                    ctx.onMain(() -> {
                        if (hasY) nav.goTo(x, y, z, reach);
                        else nav.goToXZ(x, z, reach);
                        return Boolean.TRUE;
                    });
                    JsonObject o = new JsonObject();
                    o.addProperty("pathing", true);
                    o.addProperty("reach", reach);
                    o.addProperty("backend", nav.backend());
                    return o;
                });

        d.register("baritone",
                "run any Baritone command (full feature set, if Baritone is installed) "
                        + "{command, collectMs?=250} — returns the lines Baritone printed",
                ctx -> {
                    String cmd = ctx.requireStr("command");
                    int collectMs = Math.max(0, Math.min(5000, ctx.i("collectMs", 250)));
                    Navigator nav = ctx.server.services.navigator;

                    // A Baritone command naming a block (goto jungle_log, mine diamond_ore) parses
                    // it through BlockOptionalMeta, which deadlocks the client thread unless the
                    // shared registry future has already been resolved elsewhere. Wait for that
                    // HERE, on the control thread, while the client thread is still free to do the
                    // work — waiting for it after hopping onto the main thread is the deadlock.
                    if (namesABlock(cmd) && nav instanceof BaritoneNavigator baritone) {
                        if (!baritone.awaitBlockArguments(15_000)) {
                            throw new ApiException(ErrorCode.COMMAND_FAILED,
                                    "refusing '" + cmd + "': Baritone's block-argument support is "
                                            + baritone.blockArgumentsState() + ". Running it now would "
                                            + "park the client thread permanently. Use coordinates, or "
                                            + "findBlocks to locate the block yourself.");
                        }
                    }

                    ctx.server.services.input.clear(); // hand movement to Baritone
                    // Collect what Baritone prints in response, so commands whose entire output is
                    // a chat line (find, eta, "No known locations of ...") are actually usable.
                    java.util.List<String> lines = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
                    java.util.function.Consumer<String> tap = lines::add;
                    if (collectMs > 0 && nav instanceof BaritoneNavigator b) b.addLogTap(tap);
                    try {
                        ctx.onMain(() -> { nav.runCommand(cmd); return Boolean.TRUE; });
                        if (collectMs > 0) Thread.sleep(collectMs);
                    } finally {
                        if (collectMs > 0 && nav instanceof BaritoneNavigator b) b.removeLogTap(tap);
                    }

                    JsonObject o = new JsonObject();
                    o.addProperty("ran", cmd);
                    o.addProperty("backend", nav.backend());
                    JsonArray output = new JsonArray();
                    synchronized (lines) {
                        lines.forEach(output::add);
                    }
                    o.add("output", output);
                    return o;
                });

        d.register("nav.stop", "cancel Baritone pathing", ctx -> {
            Navigator nav = ctx.server.services.navigator;
            ctx.onMain(() -> { nav.stop(); return Boolean.TRUE; });
            JsonObject o = new JsonObject();
            o.addProperty("stopped", true);
            return o;
        });

        d.register("nav.status", "pathfinding availability + state", ctx -> {
            Navigator nav = ctx.server.services.navigator;
            JsonObject o = new JsonObject();
            o.addProperty("available", nav.isAvailable());
            o.addProperty("backend", nav.backend());
            o.addProperty("active", nav.isActive());
            if (nav instanceof BaritoneNavigator baritone) {
                // "ready" means commands naming a block (goto <block>, mine <block>) are safe.
                o.addProperty("blockArguments", baritone.blockArgumentsState());
            }
            Navigator.Goal goal = nav.goal();
            if (goal != null) {
                JsonObject g = new JsonObject();
                g.addProperty("x", goal.x());
                if (goal.y() != null) g.addProperty("y", goal.y());
                g.addProperty("z", goal.z());
                g.addProperty("reach", goal.reach());
                o.add("goal", g);
            }
            return o;
        });

        d.register("chatHistory", "recent chat lines this bot has seen {limit?=50}", ctx -> {
            int limit = Math.max(1, ctx.i("limit", 50));
            JsonObject o = new JsonObject();
            o.add("lines", ctx.server.services.chatLog.recent(limit));
            o.addProperty("stored", ctx.server.services.chatLog.size());
            return o;
        });

        d.register("whisper", "private-message a player, using whichever command this server has {player, text}",
                ctx -> {
                    String player = ctx.requireStr("player");
                    String text = ctx.requireStr("text");
                    return ctx.onMain(() -> {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.getConnection() == null) throw ApiException.notConnected();
                        String verb = whisperCommand(mc);
                        mc.getConnection().sendCommand(verb + " " + player + " " + text);
                        JsonObject o = new JsonObject();
                        o.addProperty("sent", true);
                        o.addProperty("command", verb);
                        o.addProperty("to", player);
                        return o;
                    });
                });

        d.register("batch",
                "run commands in order over one round-trip {commands:[{cmd,args}], continueOnError?=false}",
                ctx -> {
                    JsonArray requests = ctx.array("commands");
                    boolean continueOnError = ctx.bool("continueOnError", false);
                    JsonArray results = new JsonArray();
                    for (JsonElement element : requests) {
                        if (!element.isJsonObject()) {
                            throw ApiException.badArgs("each entry of 'commands' must be {cmd, args?}");
                        }
                        JsonObject request = element.getAsJsonObject();
                        JsonObject outcome = runSub(ctx, request);
                        results.add(outcome);
                        if (!outcome.get("ok").getAsBoolean() && !continueOnError) break;
                    }
                    JsonObject o = new JsonObject();
                    o.add("results", results);
                    o.addProperty("ran", results.size());
                    o.addProperty("requested", requests.size());
                    return o;
                });
    }

    /**
     * Runs one sub-command of a {@code batch} with exactly the checks the top-level dispatcher
     * applies: the connection's token scope, then schema argument validation. A batch must not be a
     * hole through which a read-only token drives the bot, and it must not accept arguments the same
     * command would reject on its own.
     *
     * <p>Errors are captured into the result array rather than thrown, so one failure in the middle
     * of a sequence still tells the caller what the earlier steps did.</p>
     */
    private static JsonObject runSub(CommandContext ctx, JsonObject request) {
        JsonObject out = new JsonObject();
        String name;
        try {
            name = request.get("cmd").getAsString();
        } catch (RuntimeException e) {
            out.addProperty("ok", false);
            out.addProperty("code", ErrorCode.BAD_ARGS.name());
            out.addProperty("error", "each entry of 'commands' needs a string 'cmd'");
            return out;
        }
        out.addProperty("cmd", name);
        JsonObject args = request.has("args") && request.get("args").isJsonObject()
                ? request.getAsJsonObject("args") : new JsonObject();
        try {
            if ("batch".equals(name)) {
                // Nesting would let a caller build an unbounded command tree inside one rate-limit
                // token, and buys nothing a flat list can't express.
                throw ApiException.badArgs("batch cannot contain batch");
            }
            if (!ctx.server.allowByScope(ctx.origin, name)) {
                throw new ApiException(ErrorCode.UNAUTHORIZED, "read-only token cannot run command: " + name);
            }
            ApiSchema.validateArgs(name, args);
            JsonElement result = ctx.server.dispatcher()
                    .dispatch(name, new CommandContext(ctx.server, ctx.origin, args));
            out.addProperty("ok", true);
            out.add("result", result == null ? com.google.gson.JsonNull.INSTANCE : result);
        } catch (Exception e) {
            ApiException coded = ApiException.find(e);
            out.addProperty("ok", false);
            out.addProperty("code", coded != null ? coded.code.name() : ErrorCode.COMMAND_FAILED.name());
            out.addProperty("error", coded != null ? coded.getMessage()
                    : (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        return out;
    }

    /**
     * Finds a private-message command this server actually has. Vanilla registers {@code msg} with
     * {@code tell} and {@code w} as aliases, but plenty of servers replace the lot, so we read the
     * command tree the server sent us instead of assuming.
     */
    private static String whisperCommand(Minecraft mc) {
        var dispatcher = mc.getConnection().getCommands();
        for (String candidate : new String[] { "msg", "tell", "w", "whisper", "pm" }) {
            if (dispatcher.getRoot().getChild(candidate) != null) return candidate;
        }
        throw ApiException.notFound("this server exposes no msg/tell/w/whisper command");
    }

    /** The resolved camera behind an annotated capture, so a caller can project its own points. */
    private static JsonObject cameraJson(dev.mezzo.clef.render.RenderCamera cam) {
        JsonObject c = new JsonObject();
        c.addProperty("x", cam.x());
        c.addProperty("y", cam.y());
        c.addProperty("z", cam.z());
        c.addProperty("yaw", cam.yaw());
        c.addProperty("pitch", cam.pitch());
        c.addProperty("width", cam.width());
        c.addProperty("height", cam.height());
        c.addProperty("maxDistance", cam.maxDistance());
        c.addProperty("projection", cam.orthographic() ? "orthographic" : "perspective");
        if (cam.orthographic()) c.addProperty("orthoHeight", cam.orthoHeight());
        else c.addProperty("fov", cam.fovDegrees());
        return c;
    }


    /**
     * True if {@code command}'s first argument looks like a block name rather than a coordinate.
     *
     * <p>Deliberately conservative: anything that isn't clearly numeric counts as a block, because
     * guessing wrong in that direction costs a 15 ms wait, and guessing wrong in the other
     * direction hard-locks the client. {@code goto 106 89 120} and {@code goto ~ ~ ~10} are
     * coordinates; {@code goto jungle_log} and a bare {@code mine} are not.</p>
     */
    static boolean namesABlock(String command) {
        String[] parts = command.trim().split("\\s+");
        if (parts.length < 2) return false;                   // no argument at all
        for (int i = 1; i < parts.length; i++) {
            String arg = parts[i];
            if (arg.isEmpty()) continue;
            char c = arg.charAt(0);
            // Coordinates and counts: `goto 106 89 120`, `goto ~ ~ ~10`, `mine 3 ...`.
            if (c == '~' || c == '^' || c == '-' || c == '+' || Character.isDigit(c)) continue;
            return true;                                      // a non-numeric argument: assume block
        }
        return false;
    }

    // ---- status detail ----------------------------------------------------------------

    /**
     * Everything about the bot's own body. The original position/health fields are unchanged —
     * what's new is the survival context an agent needs to make a decision without three more
     * round-trips: what it's wearing, what's affecting it, whether it's drowning, on fire, or
     * about to take fall damage.
     */
    private static JsonObject playerStatus(Minecraft mc) {
        var player = mc.player;
        JsonObject p = new JsonObject();
        p.addProperty("x", player.getX());
        p.addProperty("y", player.getY());
        p.addProperty("z", player.getZ());
        p.addProperty("yaw", player.getYRot());
        p.addProperty("pitch", player.getXRot());
        p.addProperty("health", player.getHealth());
        p.addProperty("maxHealth", player.getMaxHealth());
        p.addProperty("absorption", player.getAbsorptionAmount());
        p.addProperty("food", player.getFoodData().getFoodLevel());
        p.addProperty("saturation", player.getFoodData().getSaturationLevel());
        p.addProperty("xpLevel", player.experienceLevel);
        p.addProperty("xpProgress", player.experienceProgress);
        p.addProperty("onGround", player.onGround());
        p.addProperty("usingItem", player.isUsingItem());
        p.addProperty("selectedSlot", player.getInventory().getSelectedSlot());
        p.addProperty("dimension", mc.level.dimension().identifier().toString());

        var held = player.getMainHandItem();
        p.addProperty("heldItem", held.isEmpty() ? "empty"
                : BuiltInRegistries.ITEM.getKey(held.getItem()) + " x" + held.getCount());
        var offhand = player.getOffhandItem();
        p.addProperty("offhand", offhand.isEmpty() ? "empty"
                : BuiltInRegistries.ITEM.getKey(offhand.getItem()).toString());

        p.addProperty("onFire", player.isOnFire());
        p.addProperty("inWater", player.isInWater());
        p.addProperty("inLava", player.isInLava());
        p.addProperty("sleeping", player.isSleeping());
        p.addProperty("sneaking", player.isShiftKeyDown());
        p.addProperty("sprinting", player.isSprinting());
        p.addProperty("fallDistance", player.fallDistance);
        p.addProperty("air", player.getAirSupply());
        p.addProperty("armorPoints", player.getArmorValue());
        if (mc.gameMode != null) {
            p.addProperty("gamemode", mc.gameMode.getPlayerMode().getSerializedName());
        }

        // Armor as ids, helmet-to-boots, with "empty" for a bare slot so the array is positional.
        JsonArray armor = new JsonArray();
        for (EquipmentSlot slot : new EquipmentSlot[] {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
            var stack = player.getItemBySlot(slot);
            armor.add(stack.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        p.add("armor", armor);

        JsonArray effects = new JsonArray();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            JsonObject e = new JsonObject();
            effect.getEffect().unwrapKey().ifPresent(key -> e.addProperty("id", key.identifier().toString()));
            e.addProperty("amplifier", effect.getAmplifier());
            e.addProperty("ticks", effect.getDuration());
            effects.add(e);
        }
        p.add("effects", effects);
        return p;
    }

    /** Where and when the bot is: time of day, weather, biome, and the light at its feet. */
    private static JsonObject worldStatus(Minecraft mc) {
        var world = mc.level;
        JsonObject w = new JsonObject();

        JsonObject time = new JsonObject();
        long timeOfDay = world.getDefaultClockTime();
        time.addProperty("timeOfDay", Math.floorMod(timeOfDay, 24000L));
        time.addProperty("day", timeOfDay / 24000L);
        time.addProperty("phase", EventEmitter.phaseOf(timeOfDay));
        w.add("time", time);

        w.addProperty("weather", world.isThundering() ? "thunder" : world.isRaining() ? "rain" : "clear");
        w.addProperty("dimension", world.dimension().identifier().toString());

        BlockPos feet = mc.player.blockPosition();
        world.getBiome(feet).unwrapKey().ifPresent(key -> w.addProperty("biome", key.identifier().toString()));

        JsonObject light = new JsonObject();
        light.addProperty("block", world.getBrightness(LightLayer.BLOCK, feet));
        light.addProperty("sky", world.getBrightness(LightLayer.SKY, feet));
        w.add("light", light);
        return w;
    }

    private CoreCommands() {}
}
