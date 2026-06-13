package dev.mezzo.clef.auth;

import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.config.ClefConfig;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Picks the auth strategy from config and produces a {@link MinecraftSession}:
 * <ul>
 *   <li><b>offline</b> — instant, deterministic UUID, works on offline-mode servers.</li>
 *   <li><b>microsoft</b> — silent refresh from {@link TokenCache} if possible, otherwise the
 *       interactive device-code flow (the prompt is surfaced via {@code onPrompt}).</li>
 * </ul>
 */
public final class AuthManager {

    private final ClefConfig config;
    private final Path cacheFile;

    public AuthManager(ClefConfig config) {
        this.config = config;
        this.cacheFile = MezzoClef.dataDir().getParent() // gameDir
                .resolve(config.auth.tokenCacheFile);
    }

    /** True if configured for the Microsoft (online) auth flow. */
    public boolean isMicrosoftMode() {
        String mode = config.auth.mode == null ? "offline" : config.auth.mode.trim().toLowerCase();
        return mode.equals("microsoft") || mode.equals("msa") || mode.equals("online");
    }

    /**
     * @param onPrompt invoked (microsoft mode, first login only) with the device code + URL
     *                 the operator must visit. Surface it via logs/control plane.
     */
    public MinecraftSession authenticate(Consumer<MicrosoftAuth.DevicePrompt> onPrompt) {
        return isMicrosoftMode() ? microsoft(onPrompt) : offline();
    }

    private MinecraftSession offline() {
        MinecraftSession s = OfflineAuth.create(config.auth.offlineUsername);
        MezzoClef.LOG.info("Offline session: {} ({})", s.username(), s.uuid());
        return s;
    }

    /**
     * Silent, non-interactive refresh using the cached refresh token. Used by the periodic
     * background refresher so a long-lived bot's Minecraft access token never goes stale (it
     * otherwise expires in ~24h, which would break the next server join). Throws if not in
     * microsoft mode or no cached token exists.
     */
    public MinecraftSession refreshSession() {
        if (!isMicrosoftMode()) {
            throw new IllegalStateException("token refresh only applies to microsoft auth mode");
        }
        TokenCache cache = TokenCache.load(cacheFile);
        if (!cache.hasRefreshToken()) {
            throw new IllegalStateException("no cached refresh token to refresh from");
        }
        MicrosoftAuth.AuthResult r =
                new MicrosoftAuth(config.auth.azureClientId).loginWithRefreshToken(cache.refreshToken);
        persist(cache, r);
        return r.session();
    }

    private MinecraftSession microsoft(Consumer<MicrosoftAuth.DevicePrompt> onPrompt) {
        MicrosoftAuth msa = new MicrosoftAuth(config.auth.azureClientId);
        TokenCache cache = TokenCache.load(cacheFile);

        if (cache.hasRefreshToken()) {
            try {
                MezzoClef.LOG.info("Refreshing cached Microsoft session…");
                MicrosoftAuth.AuthResult r = msa.loginWithRefreshToken(cache.refreshToken);
                persist(cache, r);
                return r.session();
            } catch (RuntimeException e) {
                MezzoClef.LOG.warn("Refresh failed ({}); falling back to device-code login.", e.getMessage());
            }
        }

        MicrosoftAuth.AuthResult r = msa.login(onPrompt);
        persist(cache, r);
        return r.session();
    }

    private void persist(TokenCache cache, MicrosoftAuth.AuthResult r) {
        if (r.tokens().refreshToken() != null) {
            cache.refreshToken = r.tokens().refreshToken();
        }
        cache.username = r.session().username();
        cache.uuid = r.session().uuid().toString();
        cache.savedAtEpochSec = System.currentTimeMillis() / 1000L;
        cache.save(cacheFile);
    }
}
