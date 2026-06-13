package dev.mezzo.clef.headless;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HeadlessControllerTest {

    @Test
    void togglesAndPacingClamp() {
        HeadlessController hc = HeadlessController.get();
        hc.setHeadless(true);
        assertTrue(hc.isHeadless());
        hc.setHeadless(false);
        assertFalse(hc.isHeadless());

        hc.setLoopSleepMillis(-5);
        assertEquals(0, hc.loopSleepMillis(), "negative sleep clamps to 0");
        hc.setLoopSleepMillis(7);
        assertEquals(7, hc.loopSleepMillis());
    }

    @Test
    void skippedFrameCounterIncreases() {
        HeadlessController hc = HeadlessController.get();
        long before = hc.skippedFrames();
        hc.noteSkippedFrame();
        assertEquals(before + 1, hc.skippedFrames());
    }
}
