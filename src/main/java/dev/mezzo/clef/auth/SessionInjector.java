package dev.mezzo.clef.auth;

import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.mixin.client.MinecraftClientAccessor;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

/**
 * Swaps the live {@link User} on the client so the bot connects as our authenticated
 * identity. Must run on the client thread, after the {@code Minecraft} instance exists
 * but before connecting to a server.
 *
 * <p>Caveat: this updates the identity used for the server-join handshake (access token +
 * profile), which is what online-mode servers verify. It does NOT rebuild the
 * {@code UserApiService}, so 1.19+ <i>signed chat</i> on strict servers may need extra work —
 * tracked as a known limitation in the README.
 */
public final class SessionInjector {

    public static void inject(MinecraftSession s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            throw new IllegalStateException("MinecraftClient not ready for session injection");
        }
        // 26.x ctor: (name, uuid, accessToken, Optional<xuid>, Optional<clientId>) — the old AccountType
        // parameter is gone; offline vs. Microsoft is now implied by whether a real token/UUID is present.
        User vanilla = new User(
                s.username(),
                s.uuid(),
                s.accessToken(),
                Optional.ofNullable(s.xuid()),
                Optional.empty());

        ((MinecraftClientAccessor) (Object) mc).clef$setSession(vanilla);
        MezzoClef.LOG.info("Injected {} session for {} ({})",
                s.isOnline() ? "MSA" : "LEGACY", s.username(), s.uuid());
    }

    private SessionInjector() {}
}
