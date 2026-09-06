package dev.mezzo.clef.render;

/**
 * Immutable camera description for a CPU render. Yaw/pitch follow Minecraft conventions
 * (yaw 0 = +Z/south, yaw 90 = -X/west, pitch 90 = straight down).
 *
 * <p>{@code orthoHeight} selects the projection: {@code 0} (the default, and what the convenience
 * constructor gives you) is the normal perspective camera driven by {@code fovDegrees}; anything
 * greater than zero switches to an <b>orthographic</b> camera whose viewport is {@code orthoHeight}
 * blocks tall, with the width following the aspect ratio. That is what the top-down map mode uses:
 * a map wants parallel rays so that a block is the same size everywhere in the image, and a
 * perspective camera pointed straight down gives you a fisheye instead.</p>
 */
public record RenderCamera(
        double x, double y, double z,
        float yaw, float pitch,
        float fovDegrees,
        int width, int height,
        double maxDistance,
        double orthoHeight
) {
    public RenderCamera {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("resolution must be positive");
        if (fovDegrees <= 0 || fovDegrees >= 180) throw new IllegalArgumentException("fov must be in (0,180)");
        if (maxDistance <= 0) throw new IllegalArgumentException("maxDistance must be positive");
        if (orthoHeight < 0) throw new IllegalArgumentException("orthoHeight must be >= 0");
    }

    /** Perspective camera (the common case). */
    public RenderCamera(double x, double y, double z, float yaw, float pitch,
                        float fovDegrees, int width, int height, double maxDistance) {
        this(x, y, z, yaw, pitch, fovDegrees, width, height, maxDistance, 0.0);
    }

    public boolean orthographic() {
        return orthoHeight > 0;
    }
}
