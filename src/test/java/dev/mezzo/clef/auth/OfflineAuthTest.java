package dev.mezzo.clef.auth;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OfflineAuthTest {

    @Test
    void offlineUuidIsDeterministicNameBasedV3() {
        UUID a = OfflineAuth.offlineUuid("ClefBot");
        UUID b = OfflineAuth.offlineUuid("ClefBot");
        assertEquals(a, b, "same name must map to same UUID");
        assertEquals(3, a.version(), "offline UUIDs are name-based (v3)");
    }

    @Test
    void differentNamesDifferentUuids() {
        assertNotEquals(OfflineAuth.offlineUuid("Alice"), OfflineAuth.offlineUuid("Bob"));
    }

    @Test
    void createPopulatesLegacySession() {
        MinecraftSession s = OfflineAuth.create("Steve");
        assertEquals("Steve", s.username());
        assertEquals(MinecraftSession.Type.LEGACY, s.type());
        assertFalse(s.isOnline());
        assertEquals("0", s.accessToken());
        assertEquals(OfflineAuth.offlineUuid("Steve"), s.uuid());
    }

    @Test
    void blankNameFallsBackToDefault() {
        assertEquals("ClefBot", OfflineAuth.create("  ").username());
    }
}
