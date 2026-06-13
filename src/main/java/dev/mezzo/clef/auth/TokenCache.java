package dev.mezzo.clef.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.mezzo.clef.MezzoClef;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists the MSA refresh token (and a little profile info for logging) so restarts don't
 * re-prompt. This file is a credential — it's in {@code .gitignore} and you should treat it
 * like a password.
 */
public final class TokenCache {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String refreshToken;
    public String username;
    public String uuid;
    public long savedAtEpochSec;

    public static TokenCache load(Path file) {
        try {
            if (Files.exists(file)) {
                TokenCache c = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), TokenCache.class);
                if (c != null) return c;
            }
        } catch (Exception e) {
            MezzoClef.LOG.warn("Could not read auth cache {} ({})", file, e.toString());
        }
        return new TokenCache();
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
            // best-effort tighten perms on POSIX
            try { file.toFile().setReadable(false, false); file.toFile().setReadable(true, true); } catch (Exception ignored) {}
        } catch (Exception e) {
            MezzoClef.LOG.warn("Could not write auth cache {} ({})", file, e.toString());
        }
    }

    public boolean hasRefreshToken() {
        return refreshToken != null && !refreshToken.isBlank();
    }
}
