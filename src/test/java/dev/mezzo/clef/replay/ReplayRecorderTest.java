package dev.mezzo.clef.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * The recorder's game-free half. Everything else about it needs a connection, and is covered by
 * {@link ReplayTapTest} and the live E2E script.
 */
class ReplayRecorderTest {

    /** Replay names come from the control plane, so they are attacker-shaped until proven otherwise. */
    @Test
    void sanitizeKeepsNamesInsideTheReplayDirectory() {
        assertEquals("mc.example_25565", ReplayRecorder.sanitize("mc.example:25565"));
        assertEquals("_.._etc_passwd", ReplayRecorder.sanitize("../../etc/passwd"));
        assertFalse(ReplayRecorder.sanitize("../../etc/passwd").contains("/"));
        assertEquals("hidden", ReplayRecorder.sanitize("...hidden"), "a leading dot would hide the file");
        assertEquals("replay", ReplayRecorder.sanitize(""));
        assertEquals("replay", ReplayRecorder.sanitize(null));
        assertEquals("replay", ReplayRecorder.sanitize("..."));
    }

    @Test
    void sanitizeCapsTheLength() {
        assertEquals(96, ReplayRecorder.sanitize("x".repeat(500)).length());
    }
}
