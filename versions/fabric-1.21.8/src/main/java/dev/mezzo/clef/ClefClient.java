package dev.mezzo.clef;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Fabric's client entrypoint: adapts Fabric API events onto {@link ClefBotCore}.
 *
 * <p>Everything the bot actually does lives in the core, which names no loader types and is shared
 * with the NeoForge 1.21.8 target. This class is only the wiring.
 */
public final class ClefClient implements ClientModInitializer {

    private final ClefBotCore core = new ClefBotCore();

    @Override
    public void onInitializeClient() {
        core.start();

        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> core.onStopping());
        ClientTickEvents.END_CLIENT_TICK.register(core::onClientTick);

        // System/game messages (server broadcasts, /say, join messages, ...).
        ClientReceiveMessageEvents.GAME.register(
                (message, overlay) -> core.onChatReceived(message.getString(), null, "game", overlay));
        // Player chat (this is how other players' — and our own echoed — messages arrive).
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, timestamp) ->
                core.onChatReceived(message.getString(), sender != null ? sender.getName() : null, "chat", false));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> core.onConnected());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> core.onDisconnected());
    }
}
