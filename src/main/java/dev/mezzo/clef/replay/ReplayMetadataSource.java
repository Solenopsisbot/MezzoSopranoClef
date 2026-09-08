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

    /** How often the tab list is walked. Everything else is refreshed every tick. */
    private static final int PLAYER_LIST_TICKS = 20;

    private static int ticks;
    /** Last tab-list walk, reused between rebuilds so the per-tick refresh stays cheap. */
    private static List<String> players = List.of();
    /** Which recording {@link #players} was walked for; a new one must not inherit it. */
    private static long playersSession;

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
        // Every tick, not every second. Login success arrives on the netty thread and the metadata
        // snapshot is only valid for the recording it was taken during, so a one-second refresh
        // meant a replay sealed early in a session carried the *previous* server's name, player
        // list and selfId — or nothing at all on a first connect. A tick-wide window closes that;
        // the recorder's session check covers what is left.
        //
        // The cached player list gets the same treatment: it is the one thing here that outlives a
        // connection, and a replay of server B sealed in its first second must not carry server A's
        // UUIDs. A session change forces a fresh walk (or an honest empty list) before the first
        // snapshot of the new recording is pushed.
        long current = recorder.sessionId();
        if (current != playersSession) {
            playersSession = current;
            players = List.of();
            ticks = PLAYER_LIST_TICKS;
        }
        if (++ticks >= PLAYER_LIST_TICKS) {
            ticks = 0;
            players = readPlayers(mc);
        }
        recorder.refresh(capture(mc));
    }

    /**
     * Reads the current session facts. Client thread only.
     *
     * <p>Uses the cached player list: the tab-list walk is the only part that scales with the
     * server, and a UUID appearing a second late in a replay's metadata costs nothing, while the
     * server name and {@code selfId} being a second stale costs correctness.</p>
     */
    public static ReplaySessionInfo capture(Minecraft mc) {
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

    /** Walks the tab list. The one part of a snapshot that is worth doing less often. */
    private static List<String> readPlayers(Minecraft mc) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) return List.of();
        List<String> uuids = new ArrayList<>();
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            uuids.add(info.getProfile().id().toString());
        }
        return uuids;
    }

    private static String serverName(Minecraft mc) {
        ServerData server = mc.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) return server.ip;
        return mc.isLocalServer() ? "singleplayer" : "unknown";
    }

    private ReplayMetadataSource() {}
}
