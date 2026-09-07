package dev.mezzo.clef.world;

import java.util.Base64;

/**
 * Encodes a dense cuboid of palette indices as base64 LEB128 varints — the wire format of
 * {@code blocksIn}'s {@code data} field.
 *
 * <p>Index order is <b>x-major</b>: x varies slowest, z fastest, i.e.</p>
 * <pre>i = ((x - minX) * sizeY + (y - minY)) * sizeZ + (z - minZ)</pre>
 *
 * <p>Varints keep the common case small — a 64³ region of mostly air and stone has a two-entry
 * palette, so every index is a single byte and the payload compresses to ~262 KB before base64
 * rather than the 4× that fixed-width ints would cost. Pure and Minecraft-free so it can be
 * unit-tested against a hand-built array.</p>
 */
public final class PaletteCodec {

    /** LEB128-encodes {@code indices} (all non-negative) and base64s the result. */
    public static String encode(int[] indices) {
        // Worst case is 5 bytes per index; palettes that big are pathological, but size for them.
        byte[] buf = new byte[indices.length * 5];
        int n = 0;
        for (int value : indices) {
            if (value < 0) throw new IllegalArgumentException("palette index must be non-negative: " + value);
            int v = value;
            while ((v & ~0x7F) != 0) {
                buf[n++] = (byte) ((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            buf[n++] = (byte) v;
        }
        return Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(buf, n));
    }

    /** Inverse of {@link #encode}, for tests and for clients that want a reference decoder. */
    public static int[] decode(String base64, int count) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        int[] out = new int[count];
        int p = 0;
        for (int i = 0; i < count; i++) {
            int value = 0;
            int shift = 0;
            while (true) {
                if (p >= bytes.length) throw new IllegalArgumentException("truncated varint stream");
                byte b = bytes[p++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 28) throw new IllegalArgumentException("varint too long");
            }
            out[i] = value;
        }
        return out;
    }

    /** Flat index of (x,y,z) within a cuboid of {@code sizeY × sizeZ}, in the x-major order above. */
    public static int index(int dx, int dy, int dz, int sizeY, int sizeZ) {
        return (dx * sizeY + dy) * sizeZ + dz;
    }

    private PaletteCodec() {}
}
