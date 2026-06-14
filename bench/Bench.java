package bench;

import bench.baseline.BaselinePng;
import bench.baseline.BaselineRaycaster;
import bench.baseline.BaselineVoxelView;
import dev.mezzo.clef.render.ArrayVoxelView;
import dev.mezzo.clef.render.EntityBox;
import dev.mezzo.clef.render.PngEncoder;
import dev.mezzo.clef.render.RenderCamera;
import dev.mezzo.clef.render.SoftwareRaycaster;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Standalone A/B benchmark for the MezzoSopranoClef CPU render pipeline. Compares the verbatim
 * pre-optimization code ({@code bench.baseline.*}) against the optimized production classes, in the
 * same JVM, with warmup. Asserts the optimized raycaster is <b>bit-identical</b> to the baseline
 * (so we prove no behavior was lost) and that custom-PNG output round-trips to the exact pixels.
 *
 * No JMH dependency on purpose — it must run with plain {@code javac}/{@code java} against the pure
 * render classes (no Minecraft toolchain required).
 */
public final class Bench {

    static long SINK; // consume results so the JIT can't delete the work

    public static void main(String[] args) throws Exception {
        int radius = 48;
        int sizeXZ = radius * 2 + 1;
        int sizeY = 96;
        int minX = -radius, minY = 0, minZ = -radius;

        // Build identical terrain into both the optimized and baseline voxel views.
        ArrayVoxelView opt = new ArrayVoxelView(minX, minY, minZ, sizeXZ, sizeY, sizeXZ);
        BaselineVoxelView base = new BaselineVoxelView(minX, minY, minZ, sizeXZ, sizeY, sizeXZ);
        fillTerrain(minX, minZ, sizeXZ, sizeY, (x, y, z, c) -> { opt.set(x, y, z, c); base.set(x, y, z, c); });

        List<EntityBox> entities = buildEntities(radius);
        int skyTop = 0xFF87CEEB, skyBottom = 0xFFCFEAF5;

        int W = intProp("bench.w", 256), H = intProp("bench.h", 256);
        RenderCamera cam = new RenderCamera(0.5, 55.0, 0.5, 35f, 22f, 70f, W, H, radius);

        System.out.println("=================================================================");
        System.out.println(" MezzoSopranoClef render pipeline benchmark");
        System.out.println("=================================================================");
        System.out.printf(" jvm cores=%d  parallel=%s  threads=%s  png.level=%s  png.filter=%s%n",
                Runtime.getRuntime().availableProcessors(),
                System.getProperty("mezzoclef.render.parallel", "true"),
                System.getProperty("mezzoclef.render.threads", "(cores)"),
                System.getProperty("mezzoclef.png.level", "4(default)"),
                System.getProperty("mezzoclef.png.filter", "up"));
        System.out.printf(" scene: voxel region %dx%dx%d (%,d voxels), %d entities, image %dx%d%n",
                sizeXZ, sizeY, sizeXZ, sizeXZ * sizeY * sizeXZ, entities.size(), W, H);
        System.out.println();

        // ---- correctness: optimized output must equal the baseline, bit for bit ----
        System.out.println("--- correctness ---");
        boolean allIdentical = true;
        float[][] cams = {{35, 22}, {0, 0}, {120, -10}, {0, 90}, {0, -90}, {255, 45}};
        for (float[] yp : cams) {
            RenderCamera c = new RenderCamera(0.5, 55.0, 0.5, yp[0], yp[1], 70f, 96, 96, radius);
            int[] a = BaselineRaycaster.render(base, c, entities, skyTop, skyBottom);
            int[] b = SoftwareRaycaster.render(opt, c, entities, skyTop, skyBottom);
            boolean eq = Arrays.equals(a, b);
            allIdentical &= eq;
            System.out.printf("  raycast yaw=%-4.0f pitch=%-4.0f  identical=%s%n", yp[0], yp[1], eq);
        }
        System.out.println("  => raycaster bit-identical to baseline: " + (allIdentical ? "YES" : "NO!!!"));
        if (!allIdentical) throw new AssertionError("optimized raycaster diverged from baseline");

        // colorAt parity over the whole region + out-of-bounds halo
        boolean voxEq = true;
        java.util.Random rnd = new java.util.Random(99);
        for (int i = 0; i < 2_000_000; i++) {
            int x = minX - 4 + rnd.nextInt(sizeXZ + 8);
            int y = minY - 4 + rnd.nextInt(sizeY + 8);
            int z = minZ - 4 + rnd.nextInt(sizeXZ + 8);
            if (opt.colorAt(x, y, z) != base.colorAt(x, y, z)) { voxEq = false; break; }
        }
        System.out.println("  => ArrayVoxelView.colorAt identical to baseline: " + (voxEq ? "YES" : "NO!!!"));
        if (!voxEq) throw new AssertionError("optimized voxel view diverged");

        // PNG: custom encoder must round-trip to the exact source pixels
        int[] frame = SoftwareRaycaster.render(opt, cam, entities, skyTop, skyBottom);
        byte[] customPng = PngEncoder.toPng(frame, W, H);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(customPng));
        boolean pngEq = decoded != null && decoded.getWidth() == W && decoded.getHeight() == H;
        if (pngEq) {
            for (int y = 0; y < H && pngEq; y++)
                for (int x = 0; x < W; x++)
                    if ((decoded.getRGB(x, y) & 0xFFFFFFFF) != frame[y * W + x]) { pngEq = false; break; }
        }
        byte[] basePng = BaselinePng.toPng(frame, W, H);
        System.out.println("  => custom PNG decodes to exact pixels: " + (pngEq ? "YES" : "NO!!!"));
        if (!pngEq) throw new AssertionError("custom PNG did not round-trip");
        System.out.printf("  => png size custom=%,d B  baseline(ImageIO)=%,d B  (%.0f%% of baseline)%n",
                customPng.length, basePng.length, 100.0 * customPng.length / basePng.length);
        System.out.println();

        // ---- timing ----
        int warm = intProp("bench.warm", 60), iters = intProp("bench.iter", 120);
        System.out.printf("--- timing (warmup=%d, measured=%d, median ms/op) ---%n", warm, iters);

        double rcBaseEnt = bench(warm, iters, () -> {
            int[] px = BaselineRaycaster.render(base, cam, entities, skyTop, skyBottom);
            return px[px.length / 2] + px[0];
        });
        double rcOptEnt = bench(warm, iters, () -> {
            int[] px = SoftwareRaycaster.render(opt, cam, entities, skyTop, skyBottom);
            return px[px.length / 2] + px[0];
        });
        row("raycast  (with " + entities.size() + " entities)", rcBaseEnt, rcOptEnt);

        List<EntityBox> none = List.of();
        double rcBase0 = bench(warm, iters, () -> {
            int[] px = BaselineRaycaster.render(base, cam, none, skyTop, skyBottom);
            return px[px.length / 2] + px[0];
        });
        double rcOpt0 = bench(warm, iters, () -> {
            int[] px = SoftwareRaycaster.render(opt, cam, none, skyTop, skyBottom);
            return px[px.length / 2] + px[0];
        });
        row("raycast  (terrain only, 0 entities)", rcBase0, rcOpt0);

        double pngBase = bench(warm, iters, () -> {
            try { return BaselinePng.toPng(frame, W, H).length; } catch (Exception e) { throw new RuntimeException(e); }
        });
        double pngOpt = bench(warm, iters, () -> {
            try { return PngEncoder.toPng(frame, W, H).length; } catch (Exception e) { throw new RuntimeException(e); }
        });
        row("png encode (" + W + "x" + H + ")", pngBase, pngOpt);

        // end-to-end frame: render + encode (the actual screenshot cost off the client thread)
        double e2eBase = bench(warm, iters, () -> {
            try {
                int[] px = BaselineRaycaster.render(base, cam, entities, skyTop, skyBottom);
                return BaselinePng.toPng(px, W, H).length;
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        double e2eOpt = bench(warm, iters, () -> {
            try {
                int[] px = SoftwareRaycaster.render(opt, cam, entities, skyTop, skyBottom);
                return PngEncoder.toPng(px, W, H).length;
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        row("END-TO-END frame (render+encode)", e2eBase, e2eOpt);

        System.out.println();
        System.out.println(" sink=" + SINK + " (ignore)");
    }

    private static void row(String label, double baseMs, double optMs) {
        System.out.printf("  %-38s  baseline %8.3f ms | optimized %8.3f ms | %5.2fx faster%n",
                label, baseMs, optMs, baseMs / optMs);
    }

    /** Warmup then time {@code iters} runs; returns the median wall time in milliseconds. */
    private static double bench(int warm, int iters, LongTask task) {
        for (int i = 0; i < warm; i++) SINK += task.run();
        long[] t = new long[iters];
        for (int i = 0; i < iters; i++) {
            long s = System.nanoTime();
            SINK += task.run();
            t[i] = System.nanoTime() - s;
        }
        Arrays.sort(t);
        return t[t.length / 2] / 1_000_000.0;
    }

    private interface LongTask { long run(); }

    private interface VoxelSink { void set(int x, int y, int z, int color); }

    private static void fillTerrain(int minX, int minZ, int sizeXZ, int sizeY, VoxelSink sink) {
        int grass = 0xFF5B8C3A, dirt = 0xFF6B4A2B, stone = 0xFF7F7F7F, wood = 0xFF5A3A1A, leaf = 0xFF3C7A2E;
        for (int x = minX; x < minX + sizeXZ; x++) {
            for (int z = minZ; z < minZ + sizeXZ; z++) {
                int height = (int) (24 + 8 * Math.sin(x * 0.15) + 8 * Math.cos(z * 0.13)
                        + 6 * Math.sin((x + z) * 0.07));
                if (height < 1) height = 1;
                if (height > sizeY - 8) height = sizeY - 8;
                for (int y = 0; y <= height && y < sizeY; y++) {
                    int c = (y == height) ? grass : (y > height - 4 ? dirt : stone);
                    sink.set(x, y, z, c);
                }
                if ((hash(x, z) & 0x1F) == 0) { // sparse trees -> vertical occluders
                    int top = Math.min(sizeY - 1, height + 5);
                    for (int y = height + 1; y <= top; y++) sink.set(x, y, z, wood);
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dz = -1; dz <= 1; dz++)
                            if (top + 1 < sizeY) sink.set(x + dx, top, z + dz, leaf);
                }
            }
        }
    }

    private static List<EntityBox> buildEntities(int radius) {
        List<EntityBox> list = new ArrayList<>();
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 24; i++) {
            double ex = (r.nextDouble() * 2 - 1) * radius * 0.8;
            double ez = (r.nextDouble() * 2 - 1) * radius * 0.8;
            double ey = 30 + r.nextDouble() * 20;
            int color = 0xFF000000 | r.nextInt(0xFFFFFF);
            list.add(new EntityBox(ex, ey, ez, ex + 0.6, ey + 1.8, ez + 0.6, color));
        }
        return list;
    }

    private static int hash(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) & 0x7fffffff;
    }

    private static int intProp(String k, int def) {
        String v = System.getProperty(k);
        return v == null ? def : Integer.parseInt(v);
    }

    private Bench() {}
}
