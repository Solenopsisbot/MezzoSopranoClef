package dev.mezzo.clef.render;

/**
 * A dense, array-backed {@link VoxelView} snapshot of a cubic world region. Coordinates outside
 * the captured region read as empty (alpha 0). Pure — no Minecraft types — so it doubles as the
 * test fixture for the raycaster.
 *
 * <p>Hot path: {@link #colorAt} is called once per DDA step per pixel by the raycaster (millions
 * of times per frame), so it is written to do a <i>single</i> subtraction + compare per axis and
 * a flat index multiply against a precomputed row stride — no redundant {@code contains()} pass,
 * no per-call object churn. Index math is integer-identical to the obvious form
 * {@code ((x-minX)*sizeY+(y-minY))*sizeZ+(z-minZ)}.
 */
public final class ArrayVoxelView implements VoxelView {

    private final int minX, minY, minZ;
    private final int sizeX, sizeY, sizeZ;
    /** Precomputed = sizeY * sizeZ (the per-X-slice stride). Avoids a multiply chain per lookup. */
    private final int strideX;
    private final int[] argb;

    public ArrayVoxelView(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new IllegalArgumentException("voxel region dims must be positive");
        }
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.strideX = sizeY * sizeZ;
        this.argb = new int[Math.multiplyExact(strideX, sizeX)];
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x < minX + sizeX
                && y >= minY && y < minY + sizeY
                && z >= minZ && z < minZ + sizeZ;
    }

    public void set(int x, int y, int z, int color) {
        int lx = x - minX, ly = y - minY, lz = z - minZ;
        if (lx >= 0 && lx < sizeX && ly >= 0 && ly < sizeY && lz >= 0 && lz < sizeZ) {
            argb[(lx * sizeY + ly) * sizeZ + lz] = color;
        }
    }

    /**
     * Store without a bounds check. Callers <b>must</b> guarantee {@code (x,y,z)} is inside the
     * region (e.g. the snapshotter, whose loops are clamped to the region). Skips the per-voxel
     * range test on the tick-blocking snapshot path.
     */
    public void setUnchecked(int x, int y, int z, int color) {
        argb[((x - minX) * sizeY + (y - minY)) * sizeZ + (z - minZ)] = color;
    }

    @Override
    public int colorAt(int x, int y, int z) {
        int lx = x - minX;
        if (lx < 0 || lx >= sizeX) return 0;
        int ly = y - minY;
        if (ly < 0 || ly >= sizeY) return 0;
        int lz = z - minZ;
        if (lz < 0 || lz >= sizeZ) return 0;
        return argb[(lx * sizeY + ly) * sizeZ + lz];
    }

    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
}
