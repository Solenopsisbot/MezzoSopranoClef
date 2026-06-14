package bench.baseline;

import dev.mezzo.clef.render.VoxelView;

/** Verbatim pre-optimization copy of ArrayVoxelView (the baseline for benchmarking). */
public final class BaselineVoxelView implements VoxelView {

    private final int minX, minY, minZ;
    private final int sizeX, sizeY, sizeZ;
    private final int[] argb;

    public BaselineVoxelView(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new IllegalArgumentException("voxel region dims must be positive");
        }
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.argb = new int[sizeX * sizeY * sizeZ];
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x < minX + sizeX
                && y >= minY && y < minY + sizeY
                && z >= minZ && z < minZ + sizeZ;
    }

    public void set(int x, int y, int z, int color) {
        if (contains(x, y, z)) argb[index(x, y, z)] = color;
    }

    @Override
    public int colorAt(int x, int y, int z) {
        return contains(x, y, z) ? argb[index(x, y, z)] : 0;
    }

    private int index(int x, int y, int z) {
        return ((x - minX) * sizeY + (y - minY)) * sizeZ + (z - minZ);
    }

    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
}
