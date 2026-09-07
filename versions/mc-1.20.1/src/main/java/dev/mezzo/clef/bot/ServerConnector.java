package dev.mezzo.clef.bot;

import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.version.ProtocolBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * Initiates a multiplayer connection programmatically (no GUI clicks). Must be called on the
 * client thread.
 *
 * <p>Before handing off to vanilla we tell ViaFabricPlus which protocol to speak (see
 * {@link ProtocolBridge}); that is what lets this one client join servers on any release from
 * 1.7.2 through its own version.
 *
 * <p>This release: {@code ConnectScreen.startConnecting(Screen, Minecraft, ServerAddress,
 * ServerData, boolean quickPlay)}. Newer releases append a cookie/transfer parameter.
 */
public final class ServerConnector {

    /** Connects using the configured {@code connection.serverVersion}. */
    public static void connect(String host, int port) {
        connect(host, port, MezzoClef.config().connection.serverVersion);
    }

    /**
     * Connects, speaking {@code version} ("auto", "native", or a release such as "1.12.2").
     * Returns the resolved selection for logging/reporting.
     */
    public static String connect(String host, int port, String version) {
        String selected = ProtocolBridge.get().select(version);
        MezzoClef.LOG.info("Connecting to {}:{} as protocol '{}'", host, port, selected);
        Minecraft mc = Minecraft.getInstance();
        ServerAddress address = new ServerAddress(host, port);
        // No ServerData.Type on this release; the trailing flag is "is a LAN/realms entry".
        ServerData info = new ServerData("clef-target", host + ":" + port, false);
        ConnectScreen.startConnecting(new TitleScreen(), mc, address, info, false);
        return selected;
    }

    private ServerConnector() {}
}
