package dev.mezzo.clef.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The annotation projection has to be the exact inverse of the raycaster's pixel→ray mapping,
 * otherwise labels drift from the things they label. So rather than assert hand-computed pixel
 * values, these tests generate a ray the way {@code SoftwareRaycaster} does, walk along it, and
 * check the projection puts the point back on the pixel it came from.
 */
class CameraProjectionTest {

    /** Rebuilds the raycaster's ray for a pixel, using its own basis construction. */
    private static double[] rayFor(RenderCamera cam, int px, int py) {
        double[] f = SoftwareRaycaster.rotationVector(cam.yaw(), cam.pitch());
        double[] right = normalize(cross(f, new double[] { 0, 1, 0 }));
        if (Double.isNaN(right[0])) right = new double[] { 1, 0, 0 };
        double[] up = normalize(cross(right, f));
        double tanHalf = Math.tan(Math.toRadians(cam.fovDegrees()) / 2.0);
        double aspect = (double) cam.width() / cam.height();
        double ndcX = 2.0 * ((px + 0.5) / cam.width()) - 1.0;
        double ndcY = 1.0 - 2.0 * ((py + 0.5) / cam.height());
        double xta = ndcX * tanHalf * aspect, nyt = ndcY * tanHalf;
        double dx = f[0] + right[0] * xta + up[0] * nyt;
        double dy = f[1] + right[1] * xta + up[1] * nyt;
        double dz = f[2] + right[2] * xta + up[2] * nyt;
        double inv = 1.0 / Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new double[] { dx * inv, dy * inv, dz * inv };
    }

    @Test
    void projectionInvertsTheRaycastersPixelMapping() {
        RenderCamera cam = new RenderCamera(10.5, 70.0, -3.25, 37f, -12f, 70f, 320, 180, 64);
        CameraProjection projection = new CameraProjection(cam);
        for (int[] pixel : new int[][] { {0, 0}, {160, 90}, {319, 179}, {40, 150} }) {
            double[] dir = rayFor(cam, pixel[0], pixel[1]);
            double t = 25.0;
            CameraProjection.Point p = projection.project(
                    cam.x() + dir[0] * t, cam.y() + dir[1] * t, cam.z() + dir[2] * t);
            assertEquals(pixel[0], p.x(), 1e-6, "pixel x round-trip");
            assertEquals(pixel[1], p.y(), 1e-6, "pixel y round-trip");
            assertTrue(p.depth() > 0, "a point in front of the camera has positive depth");
        }
    }

    @Test
    void pointsBehindTheCameraHaveNegativeDepthAndAreNotVisible() {
        RenderCamera cam = new RenderCamera(0, 64, 0, 0f, 0f, 70f, 64, 64, 64);
        CameraProjection projection = new CameraProjection(cam);
        // yaw 0 faces +Z, so -Z is behind us.
        assertTrue(projection.project(0, 64, -10).depth() < 0);
        assertFalse(projection.projectBox(-1, 63, -11, 1, 65, -9).visible());
    }

    @Test
    void orthographicProjectionIsScaleInvariantAcrossTheImage() {
        // Top-down ortho, 32 blocks tall. Two same-sized boxes at opposite edges must project to the
        // same on-screen size — that is the whole reason the map mode isn't a perspective camera.
        RenderCamera cam = new RenderCamera(0, 100, 0, 180f, 90f, 70f, 256, 256, 128, 32);
        CameraProjection projection = new CameraProjection(cam);
        CameraProjection.Box centre = projection.projectBox(-0.5, 64, -0.5, 0.5, 65, 0.5);
        CameraProjection.Box edge = projection.projectBox(13.5, 64, 13.5, 14.5, 65, 14.5);
        assertEquals(centre.maxX() - centre.minX(), edge.maxX() - edge.minX(), 1e-9);
        assertTrue(centre.visible() && edge.visible());
    }

    /**
     * Pins the map orientation produced by {@code screenshot mode:topdown}. Looking straight down,
     * the camera basis is decided by the floating-point residue of cos(90°), so this is exactly the
     * kind of thing that inverts silently. See {@code ScreenshotService.topDownCamera}.
     */
    @Test
    void topDownIsEastRightAndNorthUp() {
        RenderCamera cam = new RenderCamera(0, 100, 0, 180f, 90f, 70f, 256, 256, 128, 32);
        CameraProjection projection = new CameraProjection(cam);
        CameraProjection.Point east = projection.project(8, 64, 0);    // +X
        CameraProjection.Point north = projection.project(0, 64, -8);  // -Z
        assertTrue(east.x() > cam.width() / 2.0, "+X should be to the right of centre");
        assertTrue(north.y() < cam.height() / 2.0, "-Z (north) should be above centre");
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
    }

    private static double[] normalize(double[] v) {
        double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[] { v[0] / len, v[1] / len, v[2] / len };
    }
}
