package dev.mezzo.clef.render;

import java.util.List;

/**
 * A tiny CPU voxel raycaster. For each pixel it shoots a ray from the camera, walks the voxel
 * grid (Amanatides–Woo DDA) for terrain and intersects entity bounding boxes (slab test),
 * takes whichever is nearer, shades it, and fades to sky with distance. No OpenGL, no GPU, no
 * window — deterministic and unit-tested.
 *
 * <p>This is the "GPU-free" screenshot backend. It won't reproduce vanilla textures/shaders, but
 * it renders the real world geometry <i>and entities/players</i> from any position/angle/res.
 */
public final class SoftwareRaycaster {

    /** Direction the camera faces, Minecraft yaw/pitch convention. Exposed for tests. */
    public static double[] rotationVector(float yaw, float pitch) {
        double yawR = Math.toRadians(yaw);
        double pitchR = Math.toRadians(pitch);
        double cosP = Math.cos(pitchR), sinP = Math.sin(pitchR);
        double sinY = Math.sin(yawR), cosY = Math.cos(yawR);
        return new double[] { -cosP * sinY, -sinP, cosP * cosY };
    }

    /** Convenience overload (no entities). */
    public static int[] render(VoxelView view, RenderCamera cam, int skyTopArgb, int skyBottomArgb) {
        return render(view, cam, List.of(), skyTopArgb, skyBottomArgb);
    }

    public static int[] render(VoxelView view, RenderCamera cam, List<EntityBox> entities,
                               int skyTopArgb, int skyBottomArgb) {
        final int w = cam.width(), h = cam.height();
        final int[] out = new int[w * h];

        double[] f = rotationVector(cam.yaw(), cam.pitch());
        double[] right = normalize(cross(f, new double[] { 0, 1, 0 }));
        if (Double.isNaN(right[0])) right = new double[] { 1, 0, 0 }; // looking straight up/down
        double[] up = normalize(cross(right, f));

        double tanHalf = Math.tan(Math.toRadians(cam.fovDegrees()) / 2.0);
        double aspect = (double) w / (double) h;
        EntityBox[] ents = entities.toArray(new EntityBox[0]);

        for (int py = 0; py < h; py++) {
            double ndcY = 1.0 - 2.0 * ((py + 0.5) / h);
            for (int px = 0; px < w; px++) {
                double ndcX = 2.0 * ((px + 0.5) / w) - 1.0;
                double dx = f[0] + right[0] * (ndcX * tanHalf * aspect) + up[0] * (ndcY * tanHalf);
                double dy = f[1] + right[1] * (ndcX * tanHalf * aspect) + up[1] * (ndcY * tanHalf);
                double dz = f[2] + right[2] * (ndcX * tanHalf * aspect) + up[2] * (ndcY * tanHalf);
                double inv = 1.0 / Math.sqrt(dx * dx + dy * dy + dz * dz);
                out[py * w + px] = trace(view, cam, ents, dx * inv, dy * inv, dz * inv,
                        skyTopArgb, skyBottomArgb);
            }
        }
        return out;
    }

    private static int trace(VoxelView view, RenderCamera cam, EntityBox[] ents,
                             double dx, double dy, double dz, int skyTop, int skyBottom) {
        double ox = cam.x(), oy = cam.y(), oz = cam.z();

        // --- terrain via DDA ---
        int ix = (int) Math.floor(ox), iy = (int) Math.floor(oy), iz = (int) Math.floor(oz);
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
        double tDeltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
        double tDeltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);
        double tMaxX = dx == 0 ? Double.MAX_VALUE : (stepX > 0 ? (ix + 1 - ox) : (ox - ix)) * tDeltaX;
        double tMaxY = dy == 0 ? Double.MAX_VALUE : (stepY > 0 ? (iy + 1 - oy) : (oy - iy)) * tDeltaY;
        double tMaxZ = dz == 0 ? Double.MAX_VALUE : (stepZ > 0 ? (iz + 1 - oz) : (oz - iz)) * tDeltaZ;

        int blockColor = 0;
        int blockAxis = -1;
        double blockDist = Double.MAX_VALUE;
        double dist = 0;
        int axis = -1;
        for (int i = 0; i < 4096; i++) {
            int c = view.colorAt(ix, iy, iz);
            if ((c >>> 24) != 0) {
                blockColor = c;
                blockAxis = axis;
                blockDist = dist;
                break;
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                ix += stepX; dist = tMaxX; tMaxX += tDeltaX; axis = 0;
            } else if (tMaxY < tMaxZ) {
                iy += stepY; dist = tMaxY; tMaxY += tDeltaY; axis = 1;
            } else {
                iz += stepZ; dist = tMaxZ; tMaxZ += tDeltaZ; axis = 2;
            }
            if (dist > cam.maxDistance()) break;
        }

        // --- entities via slab test (only nearer than the terrain hit) ---
        double limit = Math.min(blockDist, cam.maxDistance());
        int entColor = 0;
        double entDist = Double.MAX_VALUE;
        for (EntityBox b : ents) {
            double t = intersectBox(ox, oy, oz, dx, dy, dz, b);
            if (t >= 0 && t < entDist && t < limit) {
                entDist = t;
                entColor = b.colorArgb();
            }
        }

        if (entDist < blockDist) {
            // Flat shade for entities (axis -1) — they're small; per-face shading adds little.
            return shade(entColor, -1, stepY, entDist, cam.maxDistance(), dy, skyTop, skyBottom);
        }
        if (blockColor != 0) {
            return shade(blockColor, blockAxis, stepY, blockDist, cam.maxDistance(), dy, skyTop, skyBottom);
        }
        return sky(dy, skyTop, skyBottom);
    }

    /** Ray/AABB slab intersection. Returns entry distance (&ge;0) or -1 for a miss. Pure/stateless. */
    private static double intersectBox(double ox, double oy, double oz,
                                       double dx, double dy, double dz, EntityBox b) {
        double tNear = Double.NEGATIVE_INFINITY, tFar = Double.POSITIVE_INFINITY;
        double[] o = { ox, oy, oz }, d = { dx, dy, dz };
        double[] mn = { b.minX(), b.minY(), b.minZ() }, mx = { b.maxX(), b.maxY(), b.maxZ() };
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-9) {
                if (o[a] < mn[a] || o[a] > mx[a]) return -1; // parallel and outside slab
            } else {
                double t1 = (mn[a] - o[a]) / d[a];
                double t2 = (mx[a] - o[a]) / d[a];
                if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
                if (t1 > tNear) tNear = t1;
                if (t2 < tFar) tFar = t2;
                if (tNear > tFar) return -1;
            }
        }
        if (tFar < 0) return -1;
        return Math.max(tNear, 0);
    }

    private static int shade(int color, int axis, int stepY, double dist, double maxDist,
                             double dy, int skyTop, int skyBottom) {
        double factor = switch (axis) {
            case 1 -> stepY < 0 ? 1.0 : 0.55;
            case 0 -> 0.78;
            case 2 -> 0.62;
            default -> 0.85;
        };
        int r = (int) (((color >> 16) & 0xFF) * factor);
        int g = (int) (((color >> 8) & 0xFF) * factor);
        int b = (int) ((color & 0xFF) * factor);
        double fog = clamp01(dist / maxDist);
        fog *= fog;
        int s = sky(dy, skyTop, skyBottom);
        r = lerp(r, (s >> 16) & 0xFF, fog);
        g = lerp(g, (s >> 8) & 0xFF, fog);
        b = lerp(b, s & 0xFF, fog);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int sky(double dy, int skyTop, int skyBottom) {
        double t = clamp01(dy * 0.5 + 0.5);
        int r = lerp((skyBottom >> 16) & 0xFF, (skyTop >> 16) & 0xFF, t);
        int g = lerp((skyBottom >> 8) & 0xFF, (skyTop >> 8) & 0xFF, t);
        int b = lerp(skyBottom & 0xFF, skyTop & 0xFF, t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int lerp(int a, int b, double t) {
        return (int) Math.round(a + (b - a) * t);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
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

    private SoftwareRaycaster() {}
}
