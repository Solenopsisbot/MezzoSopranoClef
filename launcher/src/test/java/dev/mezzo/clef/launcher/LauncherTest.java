package dev.mezzo.clef.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LauncherTest {

    @Test
    void sha1MatchesKnownContent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("artifact.jar");
        Files.writeString(file, "clef", StandardCharsets.UTF_8);

        assertEquals("651080f619d369236fb8464f3ef1ca6bc6989a02", invokeSha1(file));
    }

    @Test
    void fileMatchesChecksSizeAndHash(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("artifact.jar");
        Files.writeString(file, "clef", StandardCharsets.UTF_8);
        String hash = invokeSha1(file);

        assertTrue(invokeFileMatches(file, 4, hash));
        assertFalse(invokeFileMatches(file, 5, hash));
        assertFalse(invokeFileMatches(file, 4, "0000000000000000000000000000000000000000"));
    }

    @Test
    void mavenPathPreservesClassifier() throws Exception {
        Method m = Launcher.class.getDeclaredMethod("mavenPath", String.class);
        m.setAccessible(true);
        assertEquals("org/example/lib/1.2.3/lib-1.2.3-natives-linux.jar",
                m.invoke(null, "org.example:lib:1.2.3:natives-linux"));
    }

    private static String invokeSha1(Path file) throws Exception {
        Method m = Launcher.class.getDeclaredMethod("sha1", Path.class);
        m.setAccessible(true);
        return (String) m.invoke(null, file);
    }

    private static boolean invokeFileMatches(Path file, long size, String sha1) throws Exception {
        Method m = Launcher.class.getDeclaredMethod("fileMatches", Path.class, long.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, file, size, sha1);
    }
}
