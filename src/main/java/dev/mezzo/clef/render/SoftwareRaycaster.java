package dev.mezzo.clef.render;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;

/**
 * A tiny CPU voxel raycaster. For each pixel it shoots a ray from the camera, walks the voxel
 * grid (Amanatides–Woo DDA) for terrain and intersects entity bounding boxes (slab test),
 * takes whichever is nearer, shades it, and fades to sky with distance. No OpenGL, no GPU, no
 * window — deterministic and unit-tested.
 *
 * <p>This is the "GPU-free" screenshot backend. It won't reproduce vanilla textures/shaders, but
 * it renders the real world geometry <i>and entities/players</i> from any position/angle/res.
 *
 * <h2>Performance</h2>
 * <ul>
 *   <li><b>Zero per-ray allocation.</b> The slab test ({@link #intersectBox}) is fully scalar —
 *       the previous version allocated four {@code double[]} arrays <i>per entity per pixel</i>
 *       (millions of short-lived arrays a frame, hammering the young-gen GC). Same arithmetic,
 *       same order — bit-identical output.</li>
 *   <li><b>Row-parallel.</b> Rays are independent, so rows are split across cores writing into
 *       disjoint slices of the output buffer. Output is identical regardless of thread count.
 *       Toggle with {@code -Dmezzoclef.render.parallel=false}; cap threads with
 *       {@code -Dmezzoclef.render.threads=N}. Small images render inline (no pool overhead).</li>
 *   <li>Redundant per-pixel sub-expressions are hoisted out of the inner loop, keeping the exact
 *       floating-point grouping of the original so results don't drift by even one ULP.</li>
 * </ul>
 */
public final class SoftwareRaycaster {

    private static final boolean PARALLEL_ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("mezzoclef.render.parallel"));
    private static final int MAX_THREADS =
            Math.max(1, Integer.getInteger("mezzoclef.render.threads",
                    Runtime.getRuntime().availableProcessors()));
    /** Below this pixel count, threading overhead outweighs the work — render inline. */
    private static final int PARALLEL_PIXEL_THRESHOLD = 1 << 14; // ~128×128
    /**
     * Row-bands per thread. {@literal >}1 means we submit more, smaller bands than there are
     * threads so the ForkJoinPool can work-steal and balance uneven row costs (cheap sky rows vs
     * expensive terrain rows). Tunable via {@code -Dmezzoclef.render.bandfactor}.
     */
    private static final int BAND_FACTOR =
            Math.max(1, Integer.getInteger("mezzoclef.render.bandfactor", 8));

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

        Scene scene = new Scene(view, ents, cam.x(), cam.y(), cam.z(), cam.maxDistance(), w, h,
                f[0], f[1], f[2], right[0], right[1], right[2], up[0], up[1], up[2],
                tanHalf, aspect, skyTopArgb, skyBottomArgb);

        int bands = bandCount(w, h);
        if (bands <= 1) {
            renderRows(out, 0, h, scene);
            return out;
        }

        // Split rows into contiguous bands; each band writes a disjoint slice of `out`, so the
        // result is independent of how the work is scheduled (deterministic, bit-identical).
        ForkJoinPool pool = ForkJoinPool.commonPool();
        int rowsPer = (h + bands - 1) / bands;
        List<ForkJoinTask<?>> tasks = new ArrayList<>(bands);
        for (int y0 = 0; y0 < h; y0 += rowsPer) {
            final int yStart = y0, yEnd = Math.min(h, y0 + rowsPer);
            tasks.add(pool.submit(() -> renderRows(out, yStart, yEnd, scene)));
        }
        for (ForkJoinTask<?> t : tasks) t.join();
        return out;
    }

    private static int bandCount(int w, int h) {
        if (!PARALLEL_ENABLED || MAX_THREADS <= 1) return 1;
        if ((long) w * h < PARALLEL_PIXEL_THRESHOLD) return 1;
        return Math.max(1, Math.min(MAX_THREADS * BAND_FACTOR, h)); // at most one band per scanline
    }

    /** Renders scanlines [{@code y0}, {@code y1}) into {@code out}. Reads only immutable scene state. */
    private static void renderRows(int[] out, int y0, int y1, Scene s) {
        final int w = s.w, h = s.h;
        final double tanHalf = s.tanHalf, aspect = s.aspect;
        final double f0 = s.f0, f1 = s.f1, f2 = s.f2;
        final double r0 = s.r0, r1 = s.r1, r2 = s.r2;
        final double u0 = s.u0, u1 = s.u1, u2 = s.u2;
        for (int py = y0; py < y1; py++) {
            double ndcY = 1.0 - 2.0 * ((py + 0.5) / h);
            double nyt = ndcY * tanHalf;            // identical sub-expression, computed once per row
            int rowBase = py * w;
            for (int px = 0; px < w; px++) {
                double ndcX = 2.0 * ((px + 0.5) / w) - 1.0;
                double xta = ndcX * tanHalf * aspect; // identical sub-expression, computed once per pixel
                double dx = f0 + r0 * xta + u0 * nyt;
                double dy = f1 + r1 * xta + u1 * nyt;
                double dz = f2 + r2 * xta + u2 * nyt;
                double inv = 1.0 / Math.sqrt(dx * dx + dy * dy + dz * dz);
                out[rowBase + px] = trace(s, dx * inv, dy * inv, dz * inv);
            }
        }
    }

    private static int trace(Scene s, double dx, double dy, double dz) {
        final VoxelView view = s.view;
        final double ox = s.ox, oy = s.oy, oz = s.oz;
        final double maxDistance = s.maxDistance;

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
            if (dist > maxDistance) break;
        }

        // --- entities via slab test (only nearer than the terrain hit) ---
        final EntityBox[] ents = s.ents;
        double limit = Math.min(blockDist, maxDistance);
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
            return shade(entColor, -1, stepY, entDist, maxDistance, dy, s.skyTop, s.skyBottom);
        }
        if (blockColor != 0) {
            return shade(blockColor, blockAxis, stepY, blockDist, maxDistance, dy, s.skyTop, s.skyBottom);
        }
        return sky(dy, s.skyTop, s.skyBottom);
    }

    /**
     * Ray/AABB slab intersection. Returns entry distance (&ge;0) or -1 for a miss. Pure/stateless.
     * Fully scalar (no arrays) — bit-identical to the array form but with no per-call allocation.
     */
    private static double intersectBox(double ox, double oy, double oz,
                                       double dx, double dy, double dz, EntityBox b) {
        double tNear = Double.NEGATIVE_INFINITY, tFar = Double.POSITIVE_INFINITY;

        double mnX = b.minX(), mxX = b.maxX();
        if (Math.abs(dx) < 1e-9) {
            if (ox < mnX || ox > mxX) return -1;
        } else {
            double t1 = (mnX - ox) / dx, t2 = (mxX - ox) / dx;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            if (t1 > tNear) tNear = t1;
            if (t2 < tFar) tFar = t2;
            if (tNear > tFar) return -1;
        }

        double mnY = b.minY(), mxY = b.maxY();
        if (Math.abs(dy) < 1e-9) {
            if (oy < mnY || oy > mxY) return -1;
        } else {
            double t1 = (mnY - oy) / dy, t2 = (mxY - oy) / dy;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            if (t1 > tNear) tNear = t1;
            if (t2 < tFar) tFar = t2;
            if (tNear > tFar) return -1;
        }

        double mnZ = b.minZ(), mxZ = b.maxZ();
        if (Math.abs(dz) < 1e-9) {
            if (oz < mnZ || oz > mxZ) return -1;
        } else {
            double t1 = (mnZ - oz) / dz, t2 = (mxZ - oz) / dz;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            if (t1 > tNear) tNear = t1;
            if (t2 < tFar) tFar = t2;
            if (tNear > tFar) return -1;
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

    /** Immutable per-render scene, built once and shared read-only across render threads. */
    private static final class Scene {
        final VoxelView view;
        final EntityBox[] ents;
        final double ox, oy, oz, maxDistance;
        final int w, h;
        final double f0, f1, f2, r0, r1, r2, u0, u1, u2;
        final double tanHalf, aspect;
        final int skyTop, skyBottom;

        Scene(VoxelView view, EntityBox[] ents, double ox, double oy, double oz, double maxDistance,
              int w, int h, double f0, double f1, double f2, double r0, double r1, double r2,
              double u0, double u1, double u2, double tanHalf, double aspect, int skyTop, int skyBottom) {
            this.view = view; this.ents = ents;
            this.ox = ox; this.oy = oy; this.oz = oz; this.maxDistance = maxDistance;
            this.w = w; this.h = h;
            this.f0 = f0; this.f1 = f1; this.f2 = f2;
            this.r0 = r0; this.r1 = r1; this.r2 = r2;
            this.u0 = u0; this.u1 = u1; this.u2 = u2;
            this.tanHalf = tanHalf; this.aspect = aspect;
            this.skyTop = skyTop; this.skyBottom = skyBottom;
        }
    }

    private SoftwareRaycaster() {}
}
