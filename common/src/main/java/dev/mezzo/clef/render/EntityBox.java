package dev.mezzo.clef.render;

/**
 * An axis-aligned box (an entity's bounding box) with a flat color, fed to the raycaster so
 * screenshots show players/mobs/items — not just terrain. Pure (no Minecraft types) so the
 * raycaster stays unit-testable.
 */
public record EntityBox(
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ,
        int colorArgb
) {}
