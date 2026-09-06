package dev.mezzo.clef.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The day-phase boundaries behind the {@code time} event and {@code status.world.time.phase}.
 * Worth pinning: a bot that decides when to sleep or when it's safe to walk home reads this, and
 * the vanilla cycle's landmark numbers are not obvious from the code.
 */
class DayPhaseTest {

    @Test
    void namesTheVanillaDayCycle() {
        assertEquals("dawn", EventEmitter.phaseOf(0));        // sunrise
        assertEquals("day", EventEmitter.phaseOf(6000));      // noon
        assertEquals("dusk", EventEmitter.phaseOf(12500));    // sunset
        assertEquals("night", EventEmitter.phaseOf(18000));   // midnight
        assertEquals("dawn", EventEmitter.phaseOf(23500));    // pre-sunrise
    }

    @Test
    void wrapsAcrossDaysAndHandlesNegativeTimes() {
        assertEquals(EventEmitter.phaseOf(6000), EventEmitter.phaseOf(6000 + 24000L * 137));
        // Some servers send a negative time to freeze the cycle; floorMod must not produce a gap.
        assertEquals("night", EventEmitter.phaseOf(-6000));
    }

    @Test
    void everyTickOfTheCycleHasAPhase() {
        for (long t = 0; t < 24000; t++) {
            assertNotNull(EventEmitter.phaseOf(t), "no phase at time " + t);
        }
    }
}
