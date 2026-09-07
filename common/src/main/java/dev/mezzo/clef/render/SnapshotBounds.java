package dev.mezzo.clef.render;

/**
 * Pure computation of the cubic block region captured for a software screenshot, clamped to the
 * world's height. Extracted from {@code WorldSnapshotter} so the (easy-to-get-wrong) min/max/size
 * arithmetic is unit-testable without a Minecraft world.
 */
public record SnapshotBounds(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {

    public static SnapshotBounds compute(int centerX, int centerY, int centerZ, int radius,
                                         int worldBottom, int worldTop) {
        int minX = centerX - radius;
        int minZ = centerZ - radius;
        int minY = Math.max(worldBottom, centerY - radius);
        int maxY = Math.min(worldTop, centerY + radius);
        int sizeX = radius * 2 + 1;
        int sizeZ = radius * 2 + 1;
        int sizeY = Math.max(1, maxY - minY + 1);
        return new SnapshotBounds(minX, minY, minZ, sizeX, sizeY, sizeZ);
    }
}
