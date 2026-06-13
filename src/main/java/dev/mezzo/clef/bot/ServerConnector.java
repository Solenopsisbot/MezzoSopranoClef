package dev.mezzo.clef.bot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

/**
 * Initiates a multiplayer connection programmatically (no GUI clicks). Must be called on the
 * client thread.
 *
 * <p>yarn (1.21): {@code ConnectScreen.connect(Screen, MinecraftClient, ServerAddress,
 * ServerInfo, boolean quickPlay, CookieStorage cookies)}. If the signature drifts in a future
 * mapping, this one call is the thing to fix.
 */
public final class ServerConnector {

    public static void connect(String host, int port) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ServerAddress address = new ServerAddress(host, port);
        ServerInfo info = new ServerInfo("clef-target", host + ":" + port, ServerInfo.ServerType.OTHER);
        ConnectScreen.connect(new TitleScreen(), mc, address, info, false, null);
    }

    private ServerConnector() {}
}
