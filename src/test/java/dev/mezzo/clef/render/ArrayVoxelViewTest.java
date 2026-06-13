package dev.mezzo.clef.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArrayVoxelViewTest {

    @Test
    void storesAndReadsColors() {
        ArrayVoxelView v = new ArrayVoxelView(-2, 0, -2, 5, 4, 5);
        v.set(0, 1, 0, 0xFF112233);
        assertEquals(0xFF112233, v.colorAt(0, 1, 0));
    }

    @Test
    void outOfBoundsReadsAsEmpty() {
        ArrayVoxelView v = new ArrayVoxelView(0, 0, 0, 2, 2, 2);
        assertEquals(0, v.colorAt(100, 100, 100));
        assertEquals(0, v.colorAt(-1, 0, 0));
        assertFalse(v.contains(-1, 0, 0));
        assertTrue(v.contains(1, 1, 1));
    }

    @Test
    void rejectsNonPositiveDims() {
        assertThrows(IllegalArgumentException.class, () -> new ArrayVoxelView(0, 0, 0, 0, 1, 1));
    }
}
