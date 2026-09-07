package dev.mezzo.clef;

import dev.mezzo.clef.bot.EventEmitter;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Fabric's client entrypoint: adapts Fabric API events onto {@link ClefBotCore}.
 *
 * <p>Everything the bot actually does lives in the core, which names no loader types and is shared
 * with this release's NeoForge target. This class is only the wiring.
 */
public final class ClefClient implements ClientModInitializer {

    private final ClefBotCore core = new ClefBotCore();

    @Override
    public void onInitializeClient() {
        core.start();

        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> core.onStopping());
        ClientTickEvents.END_CLIENT_TICK.register(core::onClientTick);

        // System/game messages (server broadcasts, /say, join messages, ...).
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> core.onSystemMessage(message, overlay));
        // Player chat. Fabric hands us the bound chat type, which is what classifies the message.
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, timestamp) ->
                core.onChatMessage(EventEmitter.kindOf(params),
                        sender != null ? sender.name() : null, message));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> core.onConnected());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> core.onDisconnected());
    }
}
