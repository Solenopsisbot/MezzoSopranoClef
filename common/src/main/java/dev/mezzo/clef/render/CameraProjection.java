package dev.mezzo.clef.render;

/**
 * World → screen projection matching {@link SoftwareRaycaster}'s ray generation exactly, so the
 * boxes an annotated screenshot reports line up with the pixels it actually drew.
 *
 * <p>This is the inverse of what the raycaster does per pixel. The raycaster turns a pixel into a
 * direction; this turns a direction back into a pixel. Keeping both in one file would be tidier,
 * but the raycaster's inner loop is performance-shaped and this is not, so they stay apart and
 * share the convention instead: yaw/pitch as Minecraft defines them, a right-handed basis built
 * from the forward vector, +Y up on screen.</p>
 *
 * <p>Pure and Minecraft-free, so the projection is unit-testable against the raycaster's own
 * {@link SoftwareRaycaster#rotationVector}.</p>
 */
public final class CameraProjection {

    /**
     * A projected point.
     *
     * @param x     pixel column (may be outside [0,width) when the point is off-screen)
     * @param y     pixel row
     * @param depth distance along the camera's forward axis; negative means behind the camera
     */
    public record Point(double x, double y, double depth) {}

    /** Screen-space axis-aligned box of a world-space box, clamped to nothing (callers clamp). */
    public record Box(double minX, double minY, double maxX, double maxY, double depth, boolean visible) {}

    private final RenderCamera cam;
    private final double[] forward;
    private final double[] right;
    private final double[] up;
    private final double tanHalf;
    private final double aspect;

    public CameraProjection(RenderCamera cam) {
        this.cam = cam;
        this.forward = SoftwareRaycaster.rotationVector(cam.yaw(), cam.pitch());
        double[] r = normalize(cross(forward, new double[] { 0, 1, 0 }));
        if (Double.isNaN(r[0])) r = new double[] { 1, 0, 0 };   // looking straight up/down
        this.right = r;
        this.up = normalize(cross(r, forward));
        this.tanHalf = Math.tan(Math.toRadians(cam.fovDegrees()) / 2.0);
        this.aspect = (double) cam.width() / (double) cam.height();
    }

    /** Camera basis, in world space: forward, right, up. Handy for clients doing their own math. */
    public double[] forward() { return forward.clone(); }
    public double[] right() { return right.clone(); }
    public double[] up() { return up.clone(); }

    public Point project(double wx, double wy, double wz) {
        double dx = wx - cam.x(), dy = wy - cam.y(), dz = wz - cam.z();
        double depth = dot(dx, dy, dz, forward);
        double sideways = dot(dx, dy, dz, right);
        double vertical = dot(dx, dy, dz, up);

        double ndcX;
        double ndcY;
        if (cam.orthographic()) {
            double halfH = cam.orthoHeight() / 2.0;
            double halfW = halfH * aspect;
            ndcX = sideways / halfW;
            ndcY = vertical / halfH;
        } else {
            if (Math.abs(depth) < 1e-9) return new Point(Double.NaN, Double.NaN, depth);
            ndcX = (sideways / depth) / (tanHalf * aspect);
            ndcY = (vertical / depth) / tanHalf;
        }
        // Inverse of the raycaster's pixel→ndc mapping: ndcX = 2*((px+0.5)/w) - 1, ndcY = 1 - 2*((py+0.5)/h).
        double px = (ndcX + 1.0) * 0.5 * cam.width() - 0.5;
        double py = (1.0 - ndcY) * 0.5 * cam.height() - 0.5;
        return new Point(px, py, depth);
    }

    /**
     * Screen bounds of a world-space AABB, from its eight corners.
     *
     * <p>{@code visible} is a coarse test — the box has some depth in front of the camera and its
     * screen rectangle overlaps the image. It does not consider occlusion by terrain; an annotation
     * consumer that cares should cross-check against the rendered depth itself.</p>
     */
    public Box projectBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double sMinX = Double.POSITIVE_INFINITY, sMinY = Double.POSITIVE_INFINITY;
        double sMaxX = Double.NEGATIVE_INFINITY, sMaxY = Double.NEGATIVE_INFINITY;
        double nearest = Double.POSITIVE_INFINITY;
        boolean anyInFront = false;

        for (int corner = 0; corner < 8; corner++) {
            double x = (corner & 1) == 0 ? minX : maxX;
            double y = (corner & 2) == 0 ? minY : maxY;
            double z = (corner & 4) == 0 ? minZ : maxZ;
            Point p = project(x, y, z);
            if (p.depth() > 0) {
                anyInFront = true;
                nearest = Math.min(nearest, p.depth());
            }
            if (Double.isNaN(p.x())) continue;
            // A corner behind the camera projects to a mirrored, meaningless position; skip it and
            // let the in-front corners define the rectangle.
            if (!cam.orthographic() && p.depth() <= 0) continue;
            sMinX = Math.min(sMinX, p.x());
            sMinY = Math.min(sMinY, p.y());
            sMaxX = Math.max(sMaxX, p.x());
            sMaxY = Math.max(sMaxY, p.y());
        }
        if (!anyInFront || sMinX > sMaxX) {
            return new Box(0, 0, 0, 0, Double.NaN, false);
        }
        boolean onScreen = sMaxX >= 0 && sMinX < cam.width() && sMaxY >= 0 && sMinY < cam.height();
        return new Box(sMinX, sMinY, sMaxX, sMaxY, nearest, onScreen);
    }

    private static double dot(double x, double y, double z, double[] v) {
        return x * v[0] + y * v[1] + z * v[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]
        };
    }

    private static double[] normalize(double[] v) {
        double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[] { v[0] / len, v[1] / len, v[2] / len };
    }
}
