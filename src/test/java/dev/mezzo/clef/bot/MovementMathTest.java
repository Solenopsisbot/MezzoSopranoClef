package dev.mezzo.clef.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MovementMathTest {

    @Test
    void forwardIsPositive() {
        float[] v = MovementMath.vector(true, false, false, false);
        assertEquals(0f, v[0]);
        assertEquals(1f, v[1]);
    }

    @Test
    void backwardIsNegative() {
        assertEquals(-1f, MovementMath.vector(false, true, false, false)[1]);
    }

    @Test
    void strafeLeftRight() {
        assertEquals(1f, MovementMath.vector(false, false, true, false)[0]);
        assertEquals(-1f, MovementMath.vector(false, false, false, true)[0]);
    }

    @Test
    void opposingInputsCancel() {
        float[] v = MovementMath.vector(true, true, true, true);
        assertEquals(0f, v[0]);
        assertEquals(0f, v[1]);
    }

    @Test
    void diagonalCombines() {
        float[] v = MovementMath.vector(true, false, true, false);
        assertEquals(1f, v[0]);
        assertEquals(1f, v[1]);
    }
}
