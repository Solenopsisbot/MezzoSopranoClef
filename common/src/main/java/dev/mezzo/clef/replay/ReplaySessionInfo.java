package dev.mezzo.clef.replay;

import java.util.List;

/**
 * The parts of a replay's metadata that only the running Minecraft client can answer.
 *
 * <p>This exists so {@code common/} can write a complete {@code .mcpr} without naming a single
 * Minecraft class. The version module fills one of these in on the client thread (see
 * {@code ReplayRecorder#refresh}) and the recorder keeps the most recent one, so sealing a replay
 * — which can happen on a control-plane thread, or on the netty thread when the connection drops —
 * never has to reach into the game from the wrong thread.</p>
 *
 * @param singleplayer   whether the recorded connection was to an integrated server
 * @param serverName     what ReplayMod labels the replay with; normally {@code host:port}
 * @param mcVersion      the Minecraft version of the <i>packets in the file</i>, which is the
 *                       client's own version even when ViaFabricPlus translated an older server —
 *                       we capture downstream of the translation, so what lands on disk is native
 * @param protocolVersion the protocol number matching {@code mcVersion}; mandatory for file format
 *                       14, and ReplayMod refuses a replay whose protocol it cannot map
 * @param selfEntityId   the recording player's entity id, or {@code -1} if not in a world yet.
 *                       ReplayMod uses it to hide the camera's own body in first person
 * @param playerUuids    every player seen in the tab list, as ReplayMod's "players" list
 */
public record ReplaySessionInfo(boolean singleplayer, String serverName, String mcVersion,
                                int protocolVersion, int selfEntityId, List<String> playerUuids) {

    /** What gets written when a replay is sealed before the client ever reached a world. */
    public static ReplaySessionInfo unknown(String mcVersion, int protocolVersion) {
        return new ReplaySessionInfo(false, "unknown", mcVersion, protocolVersion, -1, List.of());
    }

    public ReplaySessionInfo {
        playerUuids = playerUuids == null ? List.of() : List.copyOf(playerUuids);
    }
}
