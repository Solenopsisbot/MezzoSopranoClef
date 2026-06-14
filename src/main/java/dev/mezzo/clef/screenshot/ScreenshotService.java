package dev.mezzo.clef.screenshot;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.config.ClefConfig;
import dev.mezzo.clef.mixin.client.MinecraftClientAccessor;
import dev.mezzo.clef.mixin.client.WindowAccessor;
import dev.mezzo.clef.render.ArrayVoxelView;
import dev.mezzo.clef.render.EntityBox;
import dev.mezzo.clef.render.PngEncoder;
import dev.mezzo.clef.render.RenderCamera;
import dev.mezzo.clef.render.SnapshotBounds;
import dev.mezzo.clef.render.SoftwareRaycaster;
import dev.mezzo.clef.render.WorldSnapshotter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.util.Window;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Produces a PNG of the world from an arbitrary position, angle and resolution. Two backends:
 *
 * <ul>
 *   <li><b>software</b> (default) — a CPU voxel raycaster ({@link SoftwareRaycaster}).
 *       <b>Requires no GPU and no GL context.</b> We snapshot a cube of the loaded world on the
 *       client thread ({@link WorldSnapshotter}), then raycast + PNG-encode off-thread so game
 *       ticks aren't stalled. Deterministic and unit-tested. Map-style colors, not vanilla
 *       textures.</li>
 *   <li><b>gl</b> (opt-in) — high-fidelity render through the real client renderer into an
 *       offscreen framebuffer. Needs a working GL context (real or software/Mesa). Experimental;
 *       see README.</li>
 * </ul>
 */
public final class ScreenshotService {

    /** position + look direction for a one-shot GL capture. */
    public record CameraOverride(double x, double y, double z, float yaw, float pitch) {}

    /** Null fields fall back to the player's current eye position / rotation / configured FOV. */
    public record CaptureRequest(Double x, Double y, Double z,
                                 Float yaw, Float pitch,
                                 Integer width, Integer height, Float fov) {}

    public record CaptureMetrics(String backend, int width, int height, int bytes,
                                 double totalMs, double snapshotMs, double renderMs, double pngMs) {}

    private static volatile CameraOverride ACTIVE_OVERRIDE;

    private final ClefConfig config;
    private final Semaphore captureSlot = new Semaphore(1);
    private volatile CaptureMetrics lastMetrics;

    public ScreenshotService(ClefConfig config) {
        this.config = config;
    }

    /** Read by {@code CameraMixin} on the render thread; non-null only during a GL capture. */
    public static CameraOverride activeOverride() {
        return ACTIVE_OVERRIDE;
    }

    /** The active backend name, for status reporting. */
    public String backend() {
        // The gl backend needs a real GL context; in no-GL mode there is none, so the GPU-free
        // software raycaster is the only option (and what makes screenshots work GPU-free anyway).
        if (dev.mezzo.clef.headless.HeadlessController.get().isNoGl()) {
            return "software";
        }
        return "gl".equalsIgnoreCase(config.screenshot.backend) ? "gl" : "software";
    }

    public CaptureMetrics lastMetrics() {
        return lastMetrics;
    }

    /** Capture a PNG. Safe to call from any thread; bounces to the client thread internally. */
    public byte[] capture(CaptureRequest req) throws Exception {
        if (!captureSlot.tryAcquire()) {
            throw new IllegalStateException("a screenshot is already in progress");
        }
        try {
            return backend().equals("gl") ? captureGl(req) : captureSoftware(req);
        } finally {
            captureSlot.release();
        }
    }

    // ===== software backend (GPU-free, default) ======================================

    private record Snapshot(RenderCamera cam, ArrayVoxelView view, List<EntityBox> entities,
                            int skyTop, int skyBottom) {}

    private byte[] captureSoftware(CaptureRequest req) throws Exception {
        MinecraftClient mc = MinecraftClient.getInstance();
        CompletableFuture<Snapshot> snapFuture = new CompletableFuture<>();
        long totalStart = System.nanoTime();
        long snapshotStart = totalStart;
        mc.execute(() -> {
            try {
                snapFuture.complete(buildSnapshot(mc, req));
            } catch (Throwable t) {
                snapFuture.completeExceptionally(t);
            }
        });
        Snapshot s = snapFuture.get(20, TimeUnit.SECONDS);
        long snapshotEnd = System.nanoTime();

        // Heavy work off the client thread — does not touch Minecraft or GL.
        long renderStart = snapshotEnd;
        int[] argb = SoftwareRaycaster.render(s.view(), s.cam(), s.entities(), s.skyTop(), s.skyBottom());
        long renderEnd = System.nanoTime();
        byte[] png = PngEncoder.toPng(argb, s.cam().width(), s.cam().height());
        long pngEnd = System.nanoTime();
        lastMetrics = new CaptureMetrics("software", s.cam().width(), s.cam().height(), png.length,
                nanosToMs(pngEnd - totalStart), nanosToMs(snapshotEnd - snapshotStart),
                nanosToMs(renderEnd - renderStart), nanosToMs(pngEnd - renderEnd));
        MezzoClef.LOG.debug("Software screenshot {}x{} ({} bytes) at ({},{},{}) yaw={} pitch={}",
                s.cam().width(), s.cam().height(), png.length,
                s.cam().x(), s.cam().y(), s.cam().z(), s.cam().yaw(), s.cam().pitch());
        return png;
    }

    /** Runs on the client thread: resolves the camera and snapshots the surrounding blocks. */
    private Snapshot buildSnapshot(MinecraftClient mc, CaptureRequest req) {
        ClientWorld world = mc.world;
        if (world == null) {
            throw new IllegalStateException("not in a world — connect to a server first");
        }
        Entity camEntity = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        boolean haveFullPos = req.x() != null && req.y() != null && req.z() != null;
        if (camEntity == null && !haveFullPos) {
            throw new IllegalStateException("no player/camera entity to derive position from");
        }
        Vec3d eye = camEntity != null ? camEntity.getEyePos() : new Vec3d(req.x(), req.y(), req.z());

        double x = req.x() != null ? req.x() : eye.x;
        double y = req.y() != null ? req.y() : eye.y;
        double z = req.z() != null ? req.z() : eye.z;
        float yaw = req.yaw() != null ? req.yaw() : (camEntity != null ? camEntity.getYaw() : 0f);
        float pitch = req.pitch() != null ? req.pitch() : (camEntity != null ? camEntity.getPitch() : 0f);

        int width = clamp(req.width() != null ? req.width() : config.screenshot.defaultWidth,
                1, config.screenshot.maxWidth);
        int height = clamp(req.height() != null ? req.height() : config.screenshot.defaultHeight,
                1, config.screenshot.maxHeight);
        validatePixelBudget(width, height);
        float fov = clampFov(req.fov() != null ? req.fov() : config.screenshot.defaultFov);
        int radius = clampRadiusToSnapshotBudget(world, x, y, z, clamp(config.screenshot.maxRayDistance, 8, 192));

        ArrayVoxelView view = WorldSnapshotter.snapshotBlocks(world, x, y, z, radius);
        List<EntityBox> entities = WorldSnapshotter.snapshotEntities(
                world, camEntity, x, y, z, radius, Math.max(0, config.screenshot.maxEntities));
        int sky = WorldSnapshotter.skyColor(world, new Vec3d(x, y, z));
        RenderCamera cam = new RenderCamera(x, y, z, yaw, pitch, fov, width, height, radius);
        return new Snapshot(cam, view, entities, sky, lightenTowardWhite(sky, 0.35));
    }

    // ===== gl backend (high fidelity, needs a GL context) ============================

    // The GL path mutates global state (the static camera override + the client framebuffer
    // swap), so it is NOT reentrant — serialize concurrent GL captures.
    private final java.util.concurrent.locks.ReentrantLock glLock = new java.util.concurrent.locks.ReentrantLock();

    private byte[] captureGl(CaptureRequest req) throws Exception {
        if (!glLock.tryLock()) {
            throw new IllegalStateException("a GL screenshot is already in progress (backend is not reentrant)");
        }
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            CompletableFuture<byte[]> out = new CompletableFuture<>();
            long start = System.nanoTime();
            mc.execute(() -> renderGl(mc, req, out));
            byte[] png = out.get(30, TimeUnit.SECONDS);
            lastMetrics = new CaptureMetrics("gl", 0, 0, png.length, nanosToMs(System.nanoTime() - start), 0, 0, 0);
            return png;
        } finally {
            glLock.unlock();
        }
    }

    private void renderGl(MinecraftClient mc, CaptureRequest req, CompletableFuture<byte[]> out) {
        if (!RenderSystem.isOnRenderThread()) {
            out.completeExceptionally(new IllegalStateException("GL capture not on render thread"));
            return;
        }
        if (mc.world == null) {
            out.completeExceptionally(new IllegalStateException("not in a world"));
            return;
        }
        Entity cam = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        if (cam == null && (req.x() == null || req.yaw() == null)) {
            out.completeExceptionally(new IllegalStateException("no player/camera entity"));
            return;
        }

        double x = req.x() != null ? req.x() : cam.getEyePos().x;
        double y = req.y() != null ? req.y() : cam.getEyePos().y;
        double z = req.z() != null ? req.z() : cam.getEyePos().z;
        float yaw = req.yaw() != null ? req.yaw() : cam.getYaw();
        float pitch = req.pitch() != null ? req.pitch() : cam.getPitch();
        int width = clamp(req.width() != null ? req.width() : config.screenshot.defaultWidth, 1, config.screenshot.maxWidth);
        int height = clamp(req.height() != null ? req.height() : config.screenshot.defaultHeight, 1, config.screenshot.maxHeight);
        try {
            validatePixelBudget(width, height);
        } catch (Throwable t) {
            out.completeExceptionally(t);
            return;
        }
        float fov = clampFov(req.fov() != null ? req.fov() : config.screenshot.defaultFov);

        Window window = mc.getWindow();
        WindowAccessor winAcc = (WindowAccessor) (Object) window;
        MinecraftClientAccessor mcAcc = (MinecraftClientAccessor) (Object) mc;
        int oldW = window.getFramebufferWidth();
        int oldH = window.getFramebufferHeight();
        int oldFov = mc.options.getFov().getValue();
        Framebuffer oldFb = mc.getFramebuffer();

        SimpleFramebuffer fb = new SimpleFramebuffer("clef-screenshot", width, height, true);
        ACTIVE_OVERRIDE = new CameraOverride(x, y, z, yaw, pitch);
        try {
            mc.options.getFov().setValue((int) fov);
            winAcc.clef$setFramebufferWidth(width);
            winAcc.clef$setFramebufferHeight(height);
            mcAcc.clef$setFramebuffer(fb);

            mc.gameRenderer.renderWorld(mc.getRenderTickCounter());

            ScreenshotRecorder.takeScreenshot(fb, image -> {
                try {
                    Path tmp = Files.createTempFile("clef-shot-", ".png");
                    image.writeTo(tmp);
                    byte[] bytes = Files.readAllBytes(tmp);
                    Files.deleteIfExists(tmp);
                    out.complete(bytes);
                } catch (Throwable t) {
                    out.completeExceptionally(t);
                } finally {
                    image.close();
                    fb.delete();
                }
            });
        } finally {
            // Safe to restore now — takeScreenshot captured `fb`; it's deleted in the consumer.
            ACTIVE_OVERRIDE = null;
            mcAcc.clef$setFramebuffer(oldFb);
            winAcc.clef$setFramebufferWidth(oldW);
            winAcc.clef$setFramebufferHeight(oldH);
            try {
                mc.options.getFov().setValue(oldFov);
            } catch (Exception e) {
                MezzoClef.LOG.debug("Could not restore FOV after GL capture: {}", e.toString());
            }
        }
    }

    // ===== helpers ===================================================================

    private static int lightenTowardWhite(int argb, double t) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r = (int) (r + (255 - r) * t);
        g = (int) (g + (255 - g) * t);
        b = (int) (b + (255 - b) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clampFov(float fov) {
        if (!Float.isFinite(fov)) return 70.0f;
        return Math.max(1.0f, Math.min(179.0f, fov));
    }

    private int clampRadiusToSnapshotBudget(ClientWorld world, double x, double y, double z, int radius) {
        int budget = Math.max(1, config.screenshot.maxSnapshotBlocks);
        int worldBottom = world.getBottomY();
        int worldTop = world.getBottomY() + world.getHeight() - 1;
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        while (radius > 8) {
            SnapshotBounds b = SnapshotBounds.compute(bx, by, bz, radius, worldBottom, worldTop);
            long volume = (long) b.sizeX() * b.sizeY() * b.sizeZ();
            if (volume <= budget) break;
            radius--;
        }
        return radius;
    }

    private void validatePixelBudget(int width, int height) {
        int maxPixels = Math.max(1, config.screenshot.maxPixels);
        long pixels = (long) width * height;
        if (pixels > maxPixels) {
            throw new IllegalArgumentException("screenshot " + width + "x" + height
                    + " exceeds maxPixels=" + maxPixels);
        }
    }

    private static double nanosToMs(long nanos) {
        return nanos / 1_000_000.0;
    }
}
