package dev.mezzo.clef.auth;

import java.util.UUID;

/**
 * Auth-agnostic representation of a logged-in Minecraft identity. {@link SessionInjector}
 * turns this into the vanilla {@code com.mojang.authlib} session the client actually uses.
 *
 * @param accessToken the Minecraft service access token (real for MSA, "0" for offline)
 * @param xuid        Xbox user id (MSA only; null for offline)
 * @param type        MSA (online, server auth works) or LEGACY (offline / cracked servers)
 */
public record MinecraftSession(
        String username,
        UUID uuid,
        String accessToken,
        String xuid,
        Type type
) {
    public enum Type { MSA, LEGACY }

    public boolean isOnline() {
        return type == Type.MSA;
    }
}
