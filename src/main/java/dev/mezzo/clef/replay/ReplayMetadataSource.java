package dev.mezzo.clef.replay;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;

/**
 * The Minecraft-facing half of replay recording: everything {@code metaData.json} needs that only
 * the running client knows.
 *
 * <p>Deliberately the <i>only</i> replay class that names a Minecraft type. Recording itself is
 * byte copying and zip writing, which is why {@link ReplayRecorder} and friends sit in
 * {@code common/} and compile unchanged on every target; this file is the per-release glue and is
 * the thing a version port has to look at.</p>
 *
 * <p>{@link #tick} runs on the client thread once a second. Pushing the answer in beats letting
 * the recorder pull it out: a replay can be sealed from a control-plane thread, or from the netty
 * thread when the connection drops, and neither may read the world.</p>
 */
public final class ReplayMetadataSource {

    /** Once a second is plenty: none of this changes fast, and the tab list walk is the only cost. */
    private static final int INTERVAL_TICKS = 20;

    private static int ticks;

    /** Called from the client tick. Skipped entirely while replay capture is disarmed. */
    public static void tick(Minecraft mc) {
        ReplayRecorder recorder = ReplayRecorder.get();
        if (!recorder.isArmed()) return;
        // Pose every tick (five field reads, and markers want it accurate); the rest once a second.
        LocalPlayer player = mc.player;
        if (player != null) {
            recorder.pose(player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot());
        }
        if (++ticks < INTERVAL_TICKS) return;
        ticks = 0;
        recorder.refresh(capture(mc));
    }

    /** Reads the current session facts. Client thread only. */
    public static ReplaySessionInfo capture(Minecraft mc) {
        ClientPacketListener connection = mc.getConnection();
        List<String> players = new ArrayList<>();
        if (connection != null) {
            for (PlayerInfo info : connection.getOnlinePlayers()) {
                players.add(info.getProfile().id().toString());
            }
        }
        return new ReplaySessionInfo(
                mc.isLocalServer(),
                serverName(mc),
                SharedConstants.getCurrentVersion().name(),
                // The protocol of the packets on disk, which is this client's own even when
                // ViaFabricPlus translated an older server — the tap reads downstream of it.
                SharedConstants.getProtocolVersion(),
                mc.player != null ? mc.player.getId() : -1,
                players);
    }

    private static String serverName(Minecraft mc) {
        ServerData server = mc.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) return server.ip;
        return mc.isLocalServer() ? "singleplayer" : "unknown";
    }

    private ReplayMetadataSource() {}
}
