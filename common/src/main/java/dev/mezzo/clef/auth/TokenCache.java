package dev.mezzo.clef.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.mezzo.clef.MezzoClef;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

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
        Path tmp = null;
        try {
            Path dir = file.getParent();
            Files.createDirectories(dir);
            tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
            setOwnerOnly(tmp);
            Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
            moveIntoPlace(tmp, file);
            tmp = null;
            setOwnerOnly(file);
        } catch (Exception e) {
            MezzoClef.LOG.warn("Could not write auth cache {} ({})", file, e.toString());
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        }
    }

    public boolean hasRefreshToken() {
        return refreshToken != null && !refreshToken.isBlank();
    }

    private static void moveIntoPlace(Path tmp, Path file) throws java.io.IOException {
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void setOwnerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
            return;
        } catch (UnsupportedOperationException ignored) {
            // Windows / non-POSIX filesystem fallback below.
        } catch (Exception ignored) {
            // Best effort; keep fallback below in case java.io.File can still help.
        }
        try {
            java.io.File f = file.toFile();
            f.setReadable(false, false);
            f.setWritable(false, false);
            f.setReadable(true, true);
            f.setWritable(true, true);
        } catch (Exception ignored) {
        }
    }
}
