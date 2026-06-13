package dev.mezzo.clef;

import com.google.gson.JsonObject;
import dev.mezzo.clef.api.ClefServices;
import dev.mezzo.clef.api.ControlServer;
import dev.mezzo.clef.auth.AuthManager;
import dev.mezzo.clef.auth.MinecraftSession;
import dev.mezzo.clef.auth.SessionInjector;
import dev.mezzo.clef.bot.ActionManager;
import dev.mezzo.clef.bot.InputController;
import dev.mezzo.clef.bot.ServerConnector;
import dev.mezzo.clef.bot.UseController;
import dev.mezzo.clef.config.ClefConfig;
import dev.mezzo.clef.headless.HeadlessController;
import dev.mezzo.clef.nav.BaritoneNavigator;
import dev.mezzo.clef.nav.Navigator;
import dev.mezzo.clef.screenshot.ScreenshotService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.sound.SoundCategory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client entrypoint — assembles the bot:
 * <ol>
 *   <li>Builds services (screenshots, navigation) and starts the WebSocket control plane.</li>
 *   <li>Authenticates on a background thread (offline = instant; Microsoft = device code).</li>
 *   <li>Injects the resulting session on the client thread.</li>
 *   <li>Optionally auto-connects to a server once the client has warmed up.</li>
 * </ol>
 */
public final class ClefClient implements ClientModInitializer {

    private ControlServer control;
    private ClefServices services;
    private volatile boolean authReady = false;
    private final AtomicBoolean connectStarted = new AtomicBoolean(false);
    private int warmupTicks = 0;
    private ScheduledExecutorService tokenRefresher;

    private boolean audioMuted = false;

    // event-emission state (read/written on the client thread)
    private static final int TICK_EVENT_INTERVAL = 20;   // "tick" state event cadence (~1s)
    private static final int ENTITY_EVENT_INTERVAL = 10; // entity spawn/remove diff cadence
    private static final double ENTITY_EVENT_RADIUS = 24.0;
    private float lastHealth = Float.NaN;
    private int lastFood = -1;
    private boolean wasDead = false;
    private Set<String> lastPlayers;
    private String lastScreen = "none";
    private Set<Integer> lastEntities;
    private int eventTickCounter;

    @Override
    public void onInitializeClient() {
        ClefConfig cfg = MezzoClef.config();

        ScreenshotService screenshots = new ScreenshotService(cfg);
        Navigator navigator = new BaritoneNavigator();
        this.services = new ClefServices(screenshots, navigator, new InputController(), new ActionManager(), new UseController());
        ControlServer server = new ControlServer(cfg, services);
        this.control = server;

        if (cfg.control.enabled) {
            try {
                server.start();
            } catch (Exception e) {
                MezzoClef.LOG.error("Control plane failed to start on {}:{}",
                        cfg.control.host, cfg.control.port, e);
            }
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            if (control != null) control.stop();
            if (tokenRefresher != null) tokenRefresher.shutdownNow();
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        registerEventSources();

        Thread auth = new Thread(() -> authenticate(cfg), "clef-auth");
        auth.setDaemon(true);
        auth.start();

        MezzoClef.LOG.info("MezzoSopranoClef client init complete — headless={}, control={}",
                cfg.headless, cfg.control.enabled ? cfg.control.host + ":" + cfg.control.port : "disabled");
    }

    private void authenticate(ClefConfig cfg) {
        try {
            AuthManager am = new AuthManager(cfg);
            MinecraftSession session = am.authenticate(prompt -> {
                MezzoClef.LOG.info("================ MICROSOFT SIGN-IN ================");
                MezzoClef.LOG.info("  Visit : {}", prompt.verificationUri());
                MezzoClef.LOG.info("  Code  : {}", prompt.userCode());
                MezzoClef.LOG.info("===================================================");
                if (control != null) {
                    JsonObject d = new JsonObject();
                    d.addProperty("verificationUri", prompt.verificationUri());
                    d.addProperty("userCode", prompt.userCode());
                    if (prompt.message() != null) d.addProperty("message", prompt.message());
                    control.broadcastEvent("auth.prompt", d);
                }
            });

            MinecraftClient.getInstance().execute(() -> {
                try {
                    SessionInjector.inject(session);
                    authReady = true;
                    if (control != null) {
                        JsonObject d = new JsonObject();
                        d.addProperty("username", session.username());
                        d.addProperty("uuid", session.uuid().toString());
                        d.addProperty("type", session.type().name());
                        control.broadcastEvent("auth.ok", d);
                    }
                } catch (Throwable t) {
                    MezzoClef.LOG.error("Session injection failed — check the Session ctor mapping", t);
                    if (control != null) {
                        JsonObject d = new JsonObject();
                        d.addProperty("error", String.valueOf(t.getMessage()));
                        control.broadcastEvent("auth.error", d);
                    }
                }
            });

            // Keep the Minecraft access token fresh for a long-lived bot (it expires ~24h,
            // which would otherwise break the next server join). Offline mode never expires.
            if (am.isMicrosoftMode()) {
                startTokenRefresher(am);
            }
        } catch (Throwable e) {
            MezzoClef.LOG.error("Authentication failed: {}", e.getMessage());
            if (control != null) {
                JsonObject d = new JsonObject();
                d.addProperty("error", String.valueOf(e.getMessage()));
                control.broadcastEvent("auth.error", d);
            }
        }
    }

    /** Periodically re-derives a fresh Minecraft session from the cached refresh token. */
    private void startTokenRefresher(AuthManager am) {
        tokenRefresher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "clef-token-refresh");
            t.setDaemon(true);
            return t;
        });
        // 12h interval gives comfortable margin under the ~24h MC token lifetime.
        tokenRefresher.scheduleAtFixedRate(() -> {
            try {
                MinecraftSession refreshed = am.refreshSession();
                MinecraftClient.getInstance().execute(() -> {
                    try {
                        SessionInjector.inject(refreshed);
                    } catch (Throwable t) {
                        MezzoClef.LOG.warn("Re-injecting refreshed session failed: {}", t.toString());
                    }
                });
                MezzoClef.LOG.info("Periodic Microsoft token refresh OK.");
            } catch (Throwable t) {
                MezzoClef.LOG.warn("Periodic token refresh failed (will retry): {}", t.toString());
            }
        }, 12, 12, TimeUnit.HOURS);
    }

    /** Streams chat + lifecycle events to subscribed control-plane clients. */
    private void registerEventSources() {
        // System/game messages (server broadcasts, /say, join messages, ...).
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (control == null) return;
            JsonObject d = new JsonObject();
            d.addProperty("text", message.getString());
            d.addProperty("overlay", overlay);
            d.addProperty("kind", "game");
            control.emitEvent("chat", d);
        });
        // Player chat (this is how other players' — and our own echoed — messages arrive).
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, timestamp) -> {
            if (control == null) return;
            JsonObject d = new JsonObject();
            d.addProperty("text", message.getString());
            if (sender != null) d.addProperty("sender", sender.getName());
            d.addProperty("kind", "chat");
            control.emitEvent("chat", d);
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (control != null) control.emitEvent("connected", new JsonObject());
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (control != null) control.emitEvent("disconnected", new JsonObject());
            lastHealth = Float.NaN;
            lastFood = -1;
            lastPlayers = null;
            lastEntities = null;
            lastScreen = "none";
        });
    }

    /** Per-tick driver: actuation, world-state events, then deferred auto-connect. */
    private void onClientTick(MinecraftClient mc) {
        // Mute audio cleanly (master volume -> 0) once options exist. We do NOT cancel the sound
        // engine — doing so crashes gameplay sounds (block breaks etc.) on a half-initialised
        // OpenAL. Volume 0 = silent, no crash. Headless Linux disables audio on its own anyway.
        if (!audioMuted && HeadlessController.get().isMuteAudio() && mc.options != null) {
            try {
                mc.options.getSoundVolumeOption(SoundCategory.MASTER).setValue(0.0);
            } catch (Throwable ignored) {
            }
            audioMuted = true;
        }

        services.input.tick(mc);
        services.actions.tick(mc);
        services.use.tick(mc);
        emitWorldEvents(mc);

        // First-ever launch shows a one-time accessibility onboarding screen BEFORE the title;
        // a headless bot has no GUI to dismiss it, so do it ourselves (and stop it re-showing).
        // Without this, auto-connect never fires on a fresh run dir.
        if (mc.options != null && mc.options.onboardAccessibility) {
            mc.options.onboardAccessibility = false;
            if (mc.currentScreen instanceof AccessibilityOnboardingScreen) {
                mc.setScreen(new TitleScreen());
            }
        }

        if (!authReady) return;
        if (!MezzoClef.config().connection.autoConnect) return;
        if (connectStarted.get()) return;

        if (mc.world != null) {                 // already in a world
            connectStarted.set(true);
            return;
        }
        // Ready = the resource reload finished (no overlay) and SOME menu screen is up. Don't
        // require TitleScreen specifically — first launch may sit on the onboarding screen.
        if (mc.getOverlay() != null || mc.currentScreen == null) return;
        if (++warmupTicks < 20) return;          // ~1s of grace

        if (connectStarted.compareAndSet(false, true)) {
            ClefConfig cfg = MezzoClef.config();
            MezzoClef.LOG.info("Auto-connecting to {}:{}", cfg.connection.serverHost, cfg.connection.serverPort);
            try {
                ServerConnector.connect(cfg.connection.serverHost, cfg.connection.serverPort);
            } catch (Throwable t) {
                MezzoClef.LOG.error("Auto-connect failed", t);
            }
        }
    }

    /**
     * Emits the world/state event stream to subscribers: screenOpen/screenClose, health, damage,
     * death, respawn, a throttled tick snapshot, player join/leave, and nearby entitySpawn/remove.
     * The expensive sources (tick snapshot, entity diff) only run when something is subscribed.
     */
    private void emitWorldEvents(MinecraftClient mc) {
        if (control == null) return;

        // screen open/close (handy for UI automation — react to a chest/furnace/trade opening)
        String screen = mc.currentScreen != null ? mc.currentScreen.getClass().getSimpleName() : "none";
        if (!screen.equals(lastScreen)) {
            JsonObject d = new JsonObject();
            d.addProperty("screen", screen);
            if (!"none".equals(screen) && mc.currentScreen instanceof HandledScreen) {
                control.emitEvent("screenOpen", d);
            } else if ("none".equals(screen)) {
                control.emitEvent("screenClose", d);
            }
            lastScreen = screen;
        }

        if (mc.player == null || mc.world == null) return;
        eventTickCounter++;

        float hp = mc.player.getHealth();
        int food = mc.player.getHungerManager().getFoodLevel();
        float prevHp = lastHealth;
        if (hp != lastHealth || food != lastFood) {
            JsonObject d = new JsonObject();
            d.addProperty("health", hp);
            d.addProperty("food", food);
            control.emitEvent("health", d);
        }
        if (!Float.isNaN(prevHp) && hp < prevHp) {
            JsonObject d = new JsonObject();
            d.addProperty("amount", prevHp - hp);
            d.addProperty("health", hp);
            control.emitEvent("damage", d);
        }
        lastHealth = hp;
        lastFood = food;

        boolean dead = hp <= 0f;
        if (dead && !wasDead) {
            wasDead = true;
            control.emitEvent("death", new JsonObject());
            if (MezzoClef.config().connection.autoRespawn) {
                try {
                    mc.player.requestRespawn();
                } catch (Throwable t) {
                    MezzoClef.LOG.warn("Auto-respawn failed: {}", t.toString());
                }
            }
        } else if (!dead) {
            if (wasDead) control.emitEvent("respawn", new JsonObject());
            wasDead = false;
        }

        // throttled position/state snapshot — only computed if someone subscribes to "tick"
        if (eventTickCounter % TICK_EVENT_INTERVAL == 0 && control.hasSubscribers("tick")) {
            JsonObject d = new JsonObject();
            d.addProperty("x", mc.player.getX());
            d.addProperty("y", mc.player.getY());
            d.addProperty("z", mc.player.getZ());
            d.addProperty("yaw", mc.player.getYaw());
            d.addProperty("pitch", mc.player.getPitch());
            d.addProperty("health", hp);
            d.addProperty("food", food);
            d.addProperty("dimension", mc.world.getRegistryKey().getValue().toString());
            control.emitEvent("tick", d);
        }

        // player join/leave (tab-list diff)
        if (eventTickCounter % 10 == 0 && mc.getNetworkHandler() != null) {
            Set<String> current = new HashSet<>();
            for (PlayerListEntry e : mc.getNetworkHandler().getPlayerList()) {
                current.add(e.getProfile().getName());
            }
            if (lastPlayers != null) {
                for (String name : current) {
                    if (!lastPlayers.contains(name)) {
                        JsonObject d = new JsonObject();
                        d.addProperty("name", name);
                        control.emitEvent("join", d);
                    }
                }
                for (String name : lastPlayers) {
                    if (!current.contains(name)) {
                        JsonObject d = new JsonObject();
                        d.addProperty("name", name);
                        control.emitEvent("leave", d);
                    }
                }
            }
            lastPlayers = current;
        }

        // nearby entity spawn/remove (expensive scan — only if subscribed)
        if (eventTickCounter % ENTITY_EVENT_INTERVAL == 0
                && (control.hasSubscribers("entitySpawn") || control.hasSubscribers("entityRemove"))) {
            Set<Integer> current = new HashSet<>();
            java.util.Map<Integer, Entity> byId = new java.util.HashMap<>();
            double r2 = ENTITY_EVENT_RADIUS * ENTITY_EVENT_RADIUS;
            for (Entity e : mc.world.getEntities()) {
                if (e == mc.player || e.squaredDistanceTo(mc.player) > r2) continue;
                current.add(e.getId());
                byId.put(e.getId(), e);
            }
            if (lastEntities != null) {
                for (Integer id : current) {
                    if (!lastEntities.contains(id)) {
                        Entity e = byId.get(id);
                        JsonObject d = new JsonObject();
                        d.addProperty("id", id);
                        d.addProperty("type", EntityType.getId(e.getType()).toString());
                        d.addProperty("x", e.getX());
                        d.addProperty("y", e.getY());
                        d.addProperty("z", e.getZ());
                        control.emitEvent("entitySpawn", d);
                    }
                }
                for (Integer id : lastEntities) {
                    if (!current.contains(id)) {
                        JsonObject d = new JsonObject();
                        d.addProperty("id", id);
                        control.emitEvent("entityRemove", d);
                    }
                }
            }
            lastEntities = current;
        }
    }
}
