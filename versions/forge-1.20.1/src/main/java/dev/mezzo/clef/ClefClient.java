package dev.mezzo.clef;

import dev.mezzo.clef.bot.EventEmitter;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Forge's client wiring: adapts Forge's game-bus events onto {@link ClefBotCore}.
 *
 * <p>Same core and same wire protocol as the Fabric and NeoForge targets; only the event sources
 * differ. Forge at this release has one {@code ClientChatReceivedEvent} carrying a bound chat type
 * rather than NeoForge's Player/System split, and one {@code TickEvent} carrying a phase.
 *
 * <p>The core starts on the first client tick because Forge, like NeoForge, constructs mods while
 * {@code Minecraft.getInstance()} is still null.
 */
public final class ClefClient {

    private final ClefBotCore core = new ClefBotCore();
    private final AtomicBoolean started = new AtomicBoolean(false);

    /** Registers against the Forge game event bus. Called from the mod constructor. */
    public void register(IEventBus gameBus) {
        gameBus.addListener((TickEvent.ClientTickEvent e) -> {
            if (e.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            if (started.compareAndSet(false, true)) core.start();
            core.onClientTick(mc);
        });

        gameBus.addListener((ClientChatReceivedEvent e) -> {
            // Forge has a single chat event; a null bound type is how a system message presents.
            if (e.getBoundChatType() == null) {
                core.onSystemMessage(e.getMessage(), false);
            } else {
                core.onChatMessage(EventEmitter.kindOf(e.getBoundChatType()),
                        e.getBoundChatType().name().getString(), e.getMessage());
            }
        });

        gameBus.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> core.onConnected());
        gameBus.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> core.onDisconnected());
    }
}
