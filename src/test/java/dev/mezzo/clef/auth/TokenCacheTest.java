package dev.mezzo.clef.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TokenCacheTest {

    @Test
    void saveRoundTripsAndRestrictsPosixPermissions(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("auth-cache.json");
        TokenCache cache = new TokenCache();
        cache.refreshToken = "refresh";
        cache.username = "ClefBot";
        cache.uuid = "00000000-0000-0000-0000-000000000000";
        cache.savedAtEpochSec = 123;

        cache.save(file);

        TokenCache loaded = TokenCache.load(file);
        assertEquals("refresh", loaded.refreshToken);
        assertEquals("ClefBot", loaded.username);
        assertTrue(loaded.hasRefreshToken());
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(0, files.filter(p -> p.getFileName().toString().endsWith(".tmp")).count());
        }

        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms);
        } catch (UnsupportedOperationException ignored) {
            assertTrue(Files.isReadable(file), "non-POSIX fallback should still leave the cache readable");
        }
    }
}
