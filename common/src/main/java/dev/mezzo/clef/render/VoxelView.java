package dev.mezzo.clef.render;

/**
 * A read-only 3D grid of block colors for the CPU raycaster. Pure and Minecraft-free so it can
 * be unit-tested with synthetic worlds. The Minecraft adapter is {@code WorldSnapshotter}.
 */
@FunctionalInterface
public interface VoxelView {

    /**
     * @return packed ARGB color of the block at (x,y,z). An alpha of 0 means empty/air — the ray
     *         passes straight through. Anything with alpha &gt; 0 is treated as a solid hit.
     */
    int colorAt(int x, int y, int z);
}
