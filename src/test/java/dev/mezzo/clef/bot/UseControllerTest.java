package dev.mezzo.clef.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UseControllerTest {

    @Test
    void holdThenRelease() {
        UseController u = new UseController();
        assertFalse(u.isHolding());
        u.hold(5);
        assertTrue(u.isHolding());
        u.release();
        assertFalse(u.isHolding());
    }

    @Test
    void holdClampsToAtLeastOneTick() {
        UseController u = new UseController();
        u.hold(0);
        assertTrue(u.isHolding(), "hold(0) should still register at least one tick");
    }
}
