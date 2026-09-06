package dev.mezzo.clef;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.minecraft.client.Minecraft;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NeoForge's client wiring: adapts NeoForge's game-bus events onto {@link ClefBotCore}.
 *
 * <p>The counterpart to the Fabric {@code ClefClient}. Same core, same wire protocol — only the
 * event sources differ, which is the whole point of keeping the core loader-neutral.
 *
 * <p>NeoForge splits chat receive into {@code Player} and {@code System} subclasses of one event,
 * where Fabric has two separate events; {@code System} is the one that carries the action-bar
 * ({@code overlay}) flag.
 *
 * <p>The core is started on the first client tick rather than in the mod constructor. NeoForge
 * constructs mods during loading, while {@code Minecraft.getInstance()} is still null — Fabric's
 * client entrypoint runs later, after the instance exists. Starting early here meant the auth
 * thread had no client to inject its session into. There is no ClientStartedEvent on this release,
 * and the first tick is the first moment the instance is guaranteed.
 */
public final class ClefClient {

    private final ClefBotCore core = new ClefBotCore();
    private final AtomicBoolean started = new AtomicBoolean(false);

    /** Registers against the game event bus. Called from the mod constructor. */
    public void register(IEventBus gameBus) {
        // This release predates the ClientTickEvent.Pre/Post split: there is one TickEvent
        // carrying a phase, and END is the equivalent of Post.
        gameBus.addListener(TickEvent.ClientTickEvent.class, e -> {
            if (e.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            if (started.compareAndSet(false, true)) core.start();
            core.onClientTick(mc);
        });
        gameBus.addListener(GameShuttingDownEvent.class, e -> core.onStopping());

        gameBus.addListener(ClientChatReceivedEvent.Player.class, e -> {
            // getSender() is a UUID here, so resolve the display name off the bound chat type,
            // which the server already filled in with the speaker's name.
            String sender = e.getBoundChatType() != null ? e.getBoundChatType().name().getString() : null;
            core.onChatReceived(e.getMessage().getString(), sender, "chat", false);
        });
        gameBus.addListener(ClientChatReceivedEvent.System.class,
                e -> core.onChatReceived(e.getMessage().getString(), null, "game", e.isOverlay()));

        gameBus.addListener(ClientPlayerNetworkEvent.LoggingIn.class, e -> core.onConnected());
        gameBus.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> core.onDisconnected());
    }
}
