package dev.mezzo.clef.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ClefConfigTest {

    @Test
    void createsDefaultFileWhenMissing(@TempDir Path dir) {
        Path cfgPath = dir.resolve("mezzoclef.json");
        ClefConfig cfg = ClefConfig.loadOrCreate(cfgPath);
        assertTrue(Files.exists(cfgPath), "default config should be written");
        assertEquals("offline", cfg.auth.mode);
        assertEquals(8731, cfg.control.port);
        assertFalse(cfg.control.authToken.isBlank(), "new configs should require a random control token");
        assertEquals("software", cfg.screenshot.backend);
    }

    @Test
    void roundTripsValues(@TempDir Path dir) {
        Path cfgPath = dir.resolve("mezzoclef.json");
        ClefConfig cfg = new ClefConfig();
        cfg.auth.mode = "microsoft";
        cfg.control.port = 9000;
        cfg.save(cfgPath);

        ClefConfig reloaded = ClefConfig.loadOrCreate(cfgPath);
        assertEquals("microsoft", reloaded.auth.mode);
        assertEquals(9000, reloaded.control.port);
    }

    @Test
    void systemPropertyOverridesWin(@TempDir Path dir) {
        Path cfgPath = dir.resolve("mezzoclef.json");
        System.setProperty("mezzoclef.ws.port", "12345");
        System.setProperty("mezzoclef.headless", "false");
        try {
            ClefConfig cfg = ClefConfig.loadOrCreate(cfgPath);
            assertEquals(12345, cfg.control.port);
            assertFalse(cfg.headless);
        } finally {
            System.clearProperty("mezzoclef.ws.port");
            System.clearProperty("mezzoclef.headless");
        }
    }
}
