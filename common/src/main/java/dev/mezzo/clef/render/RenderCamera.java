package dev.mezzo.clef.render;

/**
 * Immutable camera description for a CPU render. Yaw/pitch follow Minecraft conventions
 * (yaw 0 = +Z/south, yaw 90 = -X/west, pitch 90 = straight down).
 */
public record RenderCamera(
        double x, double y, double z,
        float yaw, float pitch,
        float fovDegrees,
        int width, int height,
        double maxDistance
) {
    public RenderCamera {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("resolution must be positive");
        if (fovDegrees <= 0 || fovDegrees >= 180) throw new IllegalArgumentException("fov must be in (0,180)");
        if (maxDistance <= 0) throw new IllegalArgumentException("maxDistance must be positive");
    }
}
