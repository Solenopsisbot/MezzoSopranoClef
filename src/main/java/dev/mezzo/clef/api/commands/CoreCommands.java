package dev.mezzo.clef.api.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mezzo.clef.api.CommandDispatcher;
import dev.mezzo.clef.bot.ServerConnector;
import dev.mezzo.clef.headless.HeadlessController;
import dev.mezzo.clef.nav.Navigator;
import dev.mezzo.clef.screenshot.ScreenshotService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.session.Session;

import java.util.Base64;

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

        d.register("stats", "runtime perf counters: process CPU time, suppressed sound work, skipped frames", ctx -> {
            HeadlessController hc = HeadlessController.get();
            JsonObject o = new JsonObject();
            o.addProperty("uptimeMs", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());
            ProcessHandle.current().info().totalCpuDuration()
                    .ifPresent(cpu -> o.addProperty("cpuMs", cpu.toMillis()));
            o.addProperty("soundsSuppressed", hc.soundsSuppressed());
            o.addProperty("skippedFrames", hc.skippedFrames());
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
            JsonObject o = new JsonObject();
            o.addProperty("chat", "a chat or system message was received {text, sender?, kind}");
            o.addProperty("health", "health or hunger changed {health, food}");
            o.addProperty("damage", "the bot took damage {amount, health}");
            o.addProperty("death", "the bot died");
            o.addProperty("respawn", "the bot respawned");
            o.addProperty("join", "a player entered the tab list {name}");
            o.addProperty("leave", "a player left the tab list {name}");
            o.addProperty("connected", "the bot connected to a server");
            o.addProperty("disconnected", "the bot left a server");
            o.addProperty("tick", "throttled state snapshot ~1/s {x,y,z,yaw,pitch,health,food,dimension}");
            o.addProperty("screenOpen", "a container/screen opened {screen}");
            o.addProperty("screenClose", "the open screen closed {screen}");
            o.addProperty("entitySpawn", "an entity appeared nearby {id,type,x,y,z}");
            o.addProperty("entityRemove", "a nearby entity left {id}");
            o.addProperty("auth.prompt|auth.ok|auth.error", "auth lifecycle (always delivered)");
            JsonObject o2 = new JsonObject();
            o2.add("events", o);
            o2.addProperty("subscribeAll", "subscribe with no args (or events:['*']) for everything");
            return o2;
        });

        d.register("status", "bot, world and player status", ctx -> ctx.onMain(() -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            HeadlessController hc = HeadlessController.get();
            JsonObject o = new JsonObject();
            o.addProperty("headless", hc.isHeadless());
            o.addProperty("noGl", hc.isNoGl());             // true = booted GPU-free (no OpenGL context)
            o.addProperty("noWindow", hc.isNoWindow());     // true = GLFW null platform (no display server)
            o.addProperty("skippedFrames", hc.skippedFrames());
            o.addProperty("controllers", ctx.server.connectionCount());
            o.addProperty("singleplayer", mc.isInSingleplayer());
            o.addProperty("screen", mc.currentScreen != null ? mc.currentScreen.getClass().getSimpleName() : "none");
            o.addProperty("overlay", mc.getOverlay() != null ? mc.getOverlay().getClass().getSimpleName() : "none");
            if (mc.getCurrentServerEntry() != null) {
                o.addProperty("server", mc.getCurrentServerEntry().address);
            }
            boolean inWorld = mc.world != null && mc.player != null;
            o.addProperty("inWorld", inWorld);
            if (inWorld) {
                JsonObject p = new JsonObject();
                p.addProperty("x", mc.player.getX());
                p.addProperty("y", mc.player.getY());
                p.addProperty("z", mc.player.getZ());
                p.addProperty("yaw", mc.player.getYaw());
                p.addProperty("pitch", mc.player.getPitch());
                p.addProperty("health", mc.player.getHealth());
                p.addProperty("food", mc.player.getHungerManager().getFoodLevel());
                p.addProperty("xpLevel", mc.player.experienceLevel);
                p.addProperty("xpProgress", mc.player.experienceProgress);
                p.addProperty("onGround", mc.player.isOnGround());
                p.addProperty("usingItem", mc.player.isUsingItem());
                p.addProperty("selectedSlot", mc.player.getInventory().getSelectedSlot());
                var held = mc.player.getMainHandStack();
                p.addProperty("heldItem", held.isEmpty() ? "empty"
                        : net.minecraft.registry.Registries.ITEM.getId(held.getItem()) + " x" + held.getCount());
                p.addProperty("dimension", mc.world.getRegistryKey().getValue().toString());
                o.add("player", p);
            }
            o.addProperty("navBackend", ctx.server.services.navigator.backend());
            o.addProperty("navActive", ctx.server.services.navigator.isActive());
            o.addProperty("screenshotBackend", ctx.server.services.screenshots.backend());
            return o;
        }));

        d.register("auth.status", "current logged-in identity", ctx -> ctx.onMain(() -> {
            Session s = MinecraftClient.getInstance().getSession();
            JsonObject o = new JsonObject();
            o.addProperty("username", s.getUsername());
            o.addProperty("uuid", String.valueOf(s.getUuidOrNull()));
            o.addProperty("type", s.getAccountType().name());
            return o;
        }));

        d.register("connect", "join a server {host, port}", ctx -> {
            String host = ctx.requireStr("host");
            int port = ctx.i("port", 25565);
            ctx.onMain(() -> { ServerConnector.connect(host, port); return Boolean.TRUE; });
            JsonObject o = new JsonObject();
            o.addProperty("connecting", true);
            o.addProperty("host", host);
            o.addProperty("port", port);
            return o;
        });

        d.register("disconnect", "leave the current world", ctx -> ctx.onMain(() -> {
            // 1.21.x: disconnect(Screen returnTo, boolean transferring)
            MinecraftClient.getInstance().disconnect(new TitleScreen(), false);
            JsonObject o = new JsonObject();
            o.addProperty("disconnected", true);
            return o;
        }));

        d.register("chat", "send chat, or a /command if prefixed with '/' {message}", ctx -> {
            String msg = ctx.requireStr("message");
            return ctx.onMain(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.getNetworkHandler() == null) throw new IllegalStateException("not connected");
                if (msg.startsWith("/")) mc.getNetworkHandler().sendChatCommand(msg.substring(1));
                else mc.getNetworkHandler().sendChatMessage(msg);
                JsonObject o = new JsonObject();
                o.addProperty("sent", true);
                return o;
            });
        });

        d.register("look", "set view direction {yaw, pitch}", ctx -> {
            float yaw = ctx.has("yaw") ? ctx.f("yaw", 0) : Float.NaN;
            float pitch = ctx.has("pitch") ? ctx.f("pitch", 0) : Float.NaN;
            return ctx.onMain(() -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player == null) throw new IllegalStateException("not in world");
                if (!Float.isNaN(yaw)) {
                    mc.player.setYaw(yaw);
                    mc.player.setHeadYaw(yaw);
                    mc.player.setBodyYaw(yaw);
                }
                if (!Float.isNaN(pitch)) mc.player.setPitch(pitch);
                JsonObject o = new JsonObject();
                o.addProperty("yaw", mc.player.getYaw());
                o.addProperty("pitch", mc.player.getPitch());
                return o;
            });
        });

        d.register("players", "list tab-list players", ctx -> ctx.onMain(() -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            JsonArray arr = new JsonArray();
            if (mc.getNetworkHandler() != null) {
                for (PlayerListEntry e : mc.getNetworkHandler().getPlayerList()) {
                    JsonObject p = new JsonObject();
                    p.addProperty("name", e.getProfile().getName());
                    p.addProperty("id", e.getProfile().getId().toString());
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
                "render a PNG at any {x,y,z,yaw,pitch,width,height,fov} -> base64 (defaults: player view)",
                ctx -> {
                    ScreenshotService.CaptureRequest req = new ScreenshotService.CaptureRequest(
                            ctx.has("x") ? Double.valueOf(ctx.d("x", 0)) : null,
                            ctx.has("y") ? Double.valueOf(ctx.d("y", 0)) : null,
                            ctx.has("z") ? Double.valueOf(ctx.d("z", 0)) : null,
                            ctx.has("yaw") ? Float.valueOf(ctx.f("yaw", 0)) : null,
                            ctx.has("pitch") ? Float.valueOf(ctx.f("pitch", 0)) : null,
                            ctx.has("width") ? Integer.valueOf(ctx.i("width", 0)) : null,
                            ctx.has("height") ? Integer.valueOf(ctx.i("height", 0)) : null,
                            ctx.has("fov") ? Float.valueOf(ctx.f("fov", 0)) : null);
                    // capture() bounces to the client thread internally; software backend
                    // rasterizes off-thread so it won't stall ticks.
                    byte[] png = ctx.server.services.screenshots.capture(req);
                    JsonObject o = new JsonObject();
                    o.addProperty("format", "png");
                    o.addProperty("backend", ctx.server.services.screenshots.backend());
                    o.addProperty("bytes", png.length);
                    o.addProperty("base64", Base64.getEncoder().encodeToString(png));
                    return o;
                });

        d.register("goto", "Baritone path to {x,y,z} or {x,z}", ctx -> {
            Navigator nav = ctx.server.services.navigator;
            int x = ctx.i("x", 0);
            int z = ctx.i("z", 0);
            boolean hasY = ctx.has("y");
            int y = ctx.i("y", 0);
            ctx.server.services.input.clear(); // hand movement to Baritone; stop fighting it
            ctx.onMain(() -> {
                if (hasY) nav.goTo(x, y, z);
                else nav.goToXZ(x, z);
                return Boolean.TRUE;
            });
            JsonObject o = new JsonObject();
            o.addProperty("pathing", true);
            o.addProperty("backend", nav.backend());
            return o;
        });

        d.register("baritone", "run any Baritone command (full feature set, if Baritone is installed) {command}", ctx -> {
            String cmd = ctx.requireStr("command");
            Navigator nav = ctx.server.services.navigator;
            ctx.server.services.input.clear(); // hand movement to Baritone
            ctx.onMain(() -> { nav.runCommand(cmd); return Boolean.TRUE; });
            JsonObject o = new JsonObject();
            o.addProperty("ran", cmd);
            o.addProperty("backend", nav.backend());
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
            return o;
        });
    }

    private CoreCommands() {}
}
