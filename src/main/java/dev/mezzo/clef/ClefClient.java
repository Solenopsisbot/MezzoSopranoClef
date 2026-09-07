package dev.mezzo.clef;

import com.google.gson.JsonObject;
import dev.mezzo.clef.api.ClefServices;
import dev.mezzo.clef.api.ControlServer;
import dev.mezzo.clef.api.Events;
import dev.mezzo.clef.auth.AuthManager;
import dev.mezzo.clef.auth.MinecraftSession;
import dev.mezzo.clef.auth.SessionInjector;
import dev.mezzo.clef.bot.EventEmitter;
import dev.mezzo.clef.bot.ServerConnector;
import dev.mezzo.clef.config.ClefConfig;
import dev.mezzo.clef.headless.HeadlessController;
import dev.mezzo.clef.nav.BaritoneNavigator;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.sound.SoundCategory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client entrypoint — assembles the bot:
 * <ol>
 *   <li>Builds services (screenshots, navigation, actions, crafting) and starts the WebSocket
 *       control plane.</li>
 *   <li>Authenticates on a background thread (offline = instant; Microsoft = device code).</li>
 *   <li>Injects the resulting session on the client thread.</li>
 *   <li>Optionally auto-connects to a server once the client has warmed up.</li>
 * </ol>
 *
 * <p>The pushed event stream itself lives in {@link EventEmitter}; this class only wires the
 * sources to it and drives the per-tick subsystems.</p>
 */
public final class ClefClient implements ClientModInitializer {

    private ControlServer control;
    private ClefServices services;
    private EventEmitter eventEmitter;
    private volatile boolean authReady = false;
    private final AtomicBoolean connectStarted = new AtomicBoolean(false);
    private int warmupTicks = 0;
    private ScheduledExecutorService tokenRefresher;

    private boolean audioMuted = false;

    @Override
    public void onInitializeClient() {
        ClefConfig cfg = MezzoClef.config();

        this.services = ClefServices.standard(cfg);
        ControlServer server = new ControlServer(cfg, services);
        this.control = server;
        // Packet-sourced events are raised from mixins, which have no route to this object graph.
        Events.bind(server);
        this.eventEmitter = new EventEmitter(server, services, cfg);

        if (cfg.control.enabled) {
            try {
                server.start();
            } catch (Exception e) {
                MezzoClef.LOG.error("Control plane failed to start on {}:{}",
                        cfg.control.host, cfg.control.port, e);
            }
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            Events.unbind();
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
        // System/game messages (server broadcasts, /say, join messages, action bar, ...).
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (eventEmitter == null) return;
            eventEmitter.onMessage(EventEmitter.kindOfSystem(message, overlay), null, message);
            // A refused bed only ever arrives as a translated system message; that's the one place
            // a sleep failure is observable client-side.
            eventEmitter.onSystemMessageForSleep(message);
        });
        // Player chat (this is how other players' — and our own echoed — messages arrive).
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, timestamp) -> {
            if (eventEmitter == null) return;
            eventEmitter.onMessage(EventEmitter.kindOf(params),
                    sender != null ? sender.getName() : null, message);
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (control != null) control.emitEvent("connected", new JsonObject());
            // Baritone's block-argument parsing must be initialised off the client thread or the
            // first `goto <block>` deadlocks it permanently. Do it on join, when the registries it
            // needs exist and there is time before anyone issues a command.
            if (services != null && services.navigator instanceof BaritoneNavigator baritone) {
                baritone.warmUpBlockArguments();
                baritone.addLogTap(line -> {
                    if (control == null || !control.hasSubscribers("baritone.log")) return;
                    JsonObject d = new JsonObject();
                    d.addProperty("text", line);
                    control.emitEvent("baritone.log", d);
                });
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (control != null) control.emitEvent("disconnected", new JsonObject());
            if (eventEmitter != null) eventEmitter.reset();
            if (services != null) {
                services.craft.cancel("screen_changed");
                services.combat.cancel("disconnected");
            }
        });
    }

    /** Per-tick driver: actuation, crafting, world-state events, then deferred auto-connect. */
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
        // After `use`, because a combat loop owns the use key outright while it is drawing a bow
        // and must not have a stale `useHold` unpress it half a tick before the release.
        services.combat.tick(mc);
        services.craft.tick(mc);
        eventEmitter.tick(mc);

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
}
