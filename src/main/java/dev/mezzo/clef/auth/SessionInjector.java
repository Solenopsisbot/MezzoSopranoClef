package dev.mezzo.clef.auth;

import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.mixin.client.MinecraftClientAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.session.Session;

import java.util.Optional;

/**
 * Swaps the live {@link Session} on the client so the bot connects as our authenticated
 * identity. Must run on the client thread, after the {@code MinecraftClient} instance exists
 * but before connecting to a server.
 *
 * <p>Caveat: this updates the identity used for the server-join handshake (access token +
 * profile), which is what online-mode servers verify. It does NOT rebuild the
 * {@code UserApiService}, so 1.19+ <i>signed chat</i> on strict servers may need extra work —
 * tracked as a known limitation in the README.
 */
public final class SessionInjector {

    public static void inject(MinecraftSession s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) {
            throw new IllegalStateException("MinecraftClient not ready for session injection");
        }
        Session.AccountType type = s.isOnline()
                ? Session.AccountType.MSA
                : Session.AccountType.LEGACY;

        // 1.20.5+/1.21 ctor: (name, uuid, accessToken, Optional<xuid>, Optional<clientId>, AccountType)
        Session vanilla = new Session(
                s.username(),
                s.uuid(),
                s.accessToken(),
                Optional.ofNullable(s.xuid()),
                Optional.empty(),
                type);

        ((MinecraftClientAccessor) (Object) mc).clef$setSession(vanilla);
        MezzoClef.LOG.info("Injected {} session for {} ({})",
                type, s.username(), s.uuid());
    }

    private SessionInjector() {}
}
