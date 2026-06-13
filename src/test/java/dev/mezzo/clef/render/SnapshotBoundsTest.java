package dev.mezzo.clef.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotBoundsTest {

    @Test
    void centeredCube() {
        SnapshotBounds b = SnapshotBounds.compute(0, 100, 0, 8, -64, 319);
        assertEquals(-8, b.minX());
        assertEquals(-8, b.minZ());
        assertEquals(92, b.minY());
        assertEquals(17, b.sizeX());
        assertEquals(17, b.sizeZ());
        assertEquals(17, b.sizeY());
    }

    @Test
    void clampsToWorldFloor() {
        SnapshotBounds b = SnapshotBounds.compute(0, -60, 0, 16, -64, 319);
        assertEquals(-64, b.minY(), "minY clamps to world bottom");
        assertEquals(-44, b.minY() + b.sizeY() - 1, "maxY unaffected by the clamp");
    }

    @Test
    void clampsToWorldCeiling() {
        SnapshotBounds b = SnapshotBounds.compute(0, 315, 0, 16, -64, 319);
        assertEquals(319, b.minY() + b.sizeY() - 1, "maxY clamps to world top");
    }

    @Test
    void sizeYNeverZero() {
        SnapshotBounds b = SnapshotBounds.compute(0, 0, 0, 1, 0, 0);
        assertTrue(b.sizeY() >= 1);
    }
}
