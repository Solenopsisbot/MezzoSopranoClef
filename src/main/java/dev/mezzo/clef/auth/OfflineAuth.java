package dev.mezzo.clef.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Offline ("cracked") identity. The UUID is derived exactly the way the vanilla server
 * computes it for offline players — a name-based (v3) UUID over {@code "OfflinePlayer:<name>"} —
 * so the bot keeps a stable identity across sessions and matches what offline servers expect.
 */
public final class OfflineAuth {

    public static MinecraftSession create(String username) {
        if (username == null || username.isBlank()) {
            username = "ClefBot";
        }
        UUID uuid = offlineUuid(username);
        // Offline sessions carry no real token; vanilla uses "0"/empty and servers in
        // offline mode never call the session server.
        return new MinecraftSession(username, uuid, "0", null, MinecraftSession.Type.LEGACY);
    }

    public static UUID offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }

    private OfflineAuth() {}
}
