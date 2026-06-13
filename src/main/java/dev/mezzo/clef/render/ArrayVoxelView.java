package dev.mezzo.clef.render;

/**
 * A dense, array-backed {@link VoxelView} snapshot of a cubic world region. Coordinates outside
 * the captured region read as empty (alpha 0). Pure — no Minecraft types — so it doubles as the
 * test fixture for the raycaster.
 */
public final class ArrayVoxelView implements VoxelView {

    private final int minX, minY, minZ;
    private final int sizeX, sizeY, sizeZ;
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
