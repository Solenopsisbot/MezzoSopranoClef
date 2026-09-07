package dev.mezzo.clef.render;

/**
 * An axis-aligned box (an entity's bounding box) with a flat color, fed to the raycaster so
 * screenshots show players/mobs/items — not just terrain. Pure (no Minecraft types) so the
 * raycaster stays unit-testable.
 *
 * <p>{@code id} and {@code type} are carried along purely so an annotated screenshot can label the
 * boxes it drew; the raycaster itself never looks at them.</p>
 */
public record EntityBox(
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ,
        int colorArgb,
        int id, String type
) {
    /** Anonymous box — for tests and for renders that don't need labels. */
    public EntityBox(double minX, double minY, double minZ,
                     double maxX, double maxY, double maxZ, int colorArgb) {
        this(minX, minY, minZ, maxX, maxY, maxZ, colorArgb, -1, "");
    }
}
