package dev.mezzo.clef.world;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code blocksIn} wire format. A client in another language has to be able to decode this from
 * the description in the schema alone, so the encoding's edges — the varint boundary at 128, a
 * palette bigger than one byte, the index ordering — are pinned here rather than assumed.
 */
class PaletteCodecTest {

    @Test
    void roundTripsSmallIndices() {
        int[] indices = { 0, 1, 2, 3, 0, 0, 1 };
        assertArrayEquals(indices, PaletteCodec.decode(PaletteCodec.encode(indices), indices.length));
    }

    @Test
    void singleByteBelowTheVarintBoundaryAndTwoAbove() {
        // 127 is the largest single-byte LEB128 value; 128 is the first that needs a continuation.
        assertEquals(1, java.util.Base64.getDecoder().decode(PaletteCodec.encode(new int[] { 127 })).length);
        assertEquals(2, java.util.Base64.getDecoder().decode(PaletteCodec.encode(new int[] { 128 })).length);
    }

    @Test
    void roundTripsLargePalettes() {
        Random random = new Random(20260906L);
        int[] indices = new int[4096];
        for (int i = 0; i < indices.length; i++) indices[i] = random.nextInt(70_000);
        assertArrayEquals(indices, PaletteCodec.decode(PaletteCodec.encode(indices), indices.length));
    }

    @Test
    void indexIsXMajorWithZVaryingFastest() {
        int sizeY = 4, sizeZ = 5;
        assertEquals(0, PaletteCodec.index(0, 0, 0, sizeY, sizeZ));
        assertEquals(1, PaletteCodec.index(0, 0, 1, sizeY, sizeZ));   // z first
        assertEquals(sizeZ, PaletteCodec.index(0, 1, 0, sizeY, sizeZ));
        assertEquals(sizeY * sizeZ, PaletteCodec.index(1, 0, 0, sizeY, sizeZ)); // x slowest
    }

    @Test
    void rejectsNegativeIndices() {
        assertThrows(IllegalArgumentException.class, () -> PaletteCodec.encode(new int[] { -1 }));
    }

    @Test
    void rejectsTruncatedStreams() {
        String encoded = PaletteCodec.encode(new int[] { 1, 2 });
        assertThrows(IllegalArgumentException.class, () -> PaletteCodec.decode(encoded, 5));
    }
}
