package dev.mezzo.clef.screenshot;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Window;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

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

    /**
     * Null fields fall back to the player's current eye position / rotation / configured FOV.
     *
     * @param mode      {@code "normal"} (default) for the free-flying perspective camera, or
     *                  {@code "topdown"} for an orthographic map looking straight down
     * @param centerX   topdown only: map centre, defaulting to the player
     * @param centerZ   topdown only: map centre, defaulting to the player
     * @param radius    topdown only: how many blocks the map covers north/south of the centre
     * @param annotate  also return the camera parameters and screen-space entity boxes
     */
    public record CaptureRequest(Double x, Double y, Double z,
                                 Float yaw, Float pitch,
                                 Integer width, Integer height, Float fov,
                                 String mode, Double centerX, Double centerZ, Integer radius,
                                 boolean annotate) {

        /** Plain perspective capture — the shape every caller used before topdown/annotate existed. */
        public CaptureRequest(Double x, Double y, Double z, Float yaw, Float pitch,
                              Integer width, Integer height, Float fov) {
            this(x, y, z, yaw, pitch, width, height, fov, null, null, null, null, false);
        }

        public boolean topDown() {
            return "topdown".equalsIgnoreCase(mode);
        }
    }

    public record CaptureMetrics(String backend, int width, int height, int bytes,
                                 double totalMs, double snapshotMs, double renderMs, double pngMs) {}

    /** One entity's screen-space footprint, for callers that want to draw labels on the PNG. */
    public record Annotation(int id, String type, double minX, double minY, double maxX, double maxY,
                             double depth, boolean visible) {}

    /**
     * A capture plus, when {@link CaptureRequest#annotate} was set, everything needed to draw on top
     * of it: the resolved camera and the entity boxes as they landed in image space.
     */
    public record Capture(byte[] png, CaptureMetrics metrics, RenderCamera camera,
                          java.util.List<Annotation> annotations) {}

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
        return captureAnnotated(req).png();
    }

    /**
     * Capture a PNG plus (optionally) its camera and entity annotations. Safe to call from any
     * thread; bounces to the client thread internally.
     */
    public Capture captureAnnotated(CaptureRequest req) throws Exception {
        if (!captureSlot.tryAcquire()) {
            throw new IllegalStateException("a screenshot is already in progress");
        }
        try {
            if (backend().equals("gl")) {
                if (req.topDown()) {
                    throw new IllegalArgumentException(
                            "mode=topdown is a software-backend camera; set screenshot.backend=software");
                }
                byte[] png = captureGl(req);
                return new Capture(png, lastMetrics, null, java.util.List.of());
            }
            return captureSoftware(req);
        } finally {
            captureSlot.release();
        }
    }

    // ===== software backend (GPU-free, default) ======================================

    private record Snapshot(RenderCamera cam, ArrayVoxelView view, List<EntityBox> entities,
                            int skyTop, int skyBottom) {}

    private Capture captureSoftware(CaptureRequest req) throws Exception {
        Minecraft mc = Minecraft.getInstance();
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
        CaptureMetrics metrics = new CaptureMetrics("software", s.cam().width(), s.cam().height(), png.length,
                nanosToMs(pngEnd - totalStart), nanosToMs(snapshotEnd - snapshotStart),
                nanosToMs(renderEnd - renderStart), nanosToMs(pngEnd - renderEnd));
        lastMetrics = metrics;
        MezzoClef.LOG.debug("Software screenshot {}x{} ({} bytes) at ({},{},{}) yaw={} pitch={}",
                s.cam().width(), s.cam().height(), png.length,
                s.cam().x(), s.cam().y(), s.cam().z(), s.cam().yaw(), s.cam().pitch());
        return new Capture(png, metrics, s.cam(),
                req.annotate() ? annotate(s.cam(), s.entities()) : java.util.List.of());
    }

    /**
     * Projects each rendered entity box into image space. Done here rather than client-side because
     * only we know the exact basis the raycaster used, and re-deriving it from yaw/pitch is the kind
     * of thing that is subtly wrong for months.
     */
    private static java.util.List<Annotation> annotate(RenderCamera cam, List<EntityBox> entities) {
        dev.mezzo.clef.render.CameraProjection projection = new dev.mezzo.clef.render.CameraProjection(cam);
        java.util.List<Annotation> out = new java.util.ArrayList<>(entities.size());
        for (EntityBox box : entities) {
            var screen = projection.projectBox(box.minX(), box.minY(), box.minZ(),
                    box.maxX(), box.maxY(), box.maxZ());
            out.add(new Annotation(box.id(), box.type(), screen.minX(), screen.minY(),
                    screen.maxX(), screen.maxY(), screen.depth(), screen.visible()));
        }
        return out;
    }

    /** Runs on the client thread: resolves the camera and snapshots the surrounding blocks. */
    private Snapshot buildSnapshot(Minecraft mc, CaptureRequest req) {
        ClientLevel world = mc.level;
        if (world == null) {
            throw new IllegalStateException("not in a world — connect to a server first");
        }
        Entity camEntity = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        int width = clamp(req.width() != null ? req.width() : config.screenshot.defaultWidth,
                1, config.screenshot.maxWidth);
        int height = clamp(req.height() != null ? req.height() : config.screenshot.defaultHeight,
                1, config.screenshot.maxHeight);
        validatePixelBudget(width, height);

        RenderCamera cam = req.topDown()
                ? topDownCamera(world, camEntity, req, width, height)
                : freeCamera(world, camEntity, req, width, height);

        ArrayVoxelView view = WorldSnapshotter.snapshotBlocks(world, cam.x(), cam.y(), cam.z(), (int) cam.maxDistance());
        List<EntityBox> entities = WorldSnapshotter.snapshotEntities(
                world, req.topDown() ? null : camEntity, cam.x(), cam.y(), cam.z(),
                (int) cam.maxDistance(), Math.max(0, config.screenshot.maxEntities));
        int sky = WorldSnapshotter.skyColor(world, new Vec3(cam.x(), cam.y(), cam.z()));
        return new Snapshot(cam, view, entities, sky, lightenTowardWhite(sky, 0.35));
    }

    /** The normal camera: player eye position and rotation unless the request overrides them. */
    private RenderCamera freeCamera(ClientLevel world, Entity camEntity, CaptureRequest req,
                                    int width, int height) {
        boolean haveFullPos = req.x() != null && req.y() != null && req.z() != null;
        if (camEntity == null && !haveFullPos) {
            throw new IllegalStateException("no player/camera entity to derive position from");
        }
        Vec3 eye = camEntity != null ? camEntity.getEyePosition() : new Vec3(req.x(), req.y(), req.z());

        double x = req.x() != null ? req.x() : eye.x;
        double y = req.y() != null ? req.y() : eye.y;
        double z = req.z() != null ? req.z() : eye.z;
        float yaw = req.yaw() != null ? req.yaw() : (camEntity != null ? camEntity.getYRot() : 0f);
        float pitch = req.pitch() != null ? req.pitch() : (camEntity != null ? camEntity.getXRot() : 0f);
        float fov = clampFov(req.fov() != null ? req.fov() : config.screenshot.defaultFov);
        int radius = clampRadiusToSnapshotBudget(world, x, y, z, clamp(config.screenshot.maxRayDistance, 8, 192));
        return new RenderCamera(x, y, z, yaw, pitch, fov, width, height, radius);
    }

    /**
     * An orthographic map camera hanging straight above {@code centerX/centerZ}, oriented
     * <b>east-right, north-up</b> like every other map you have ever read.
     *
     * <p>The viewport is {@code 2 * radius} blocks tall and follows the aspect ratio horizontally,
     * so a block covers the same pixels in every corner of the image.</p>
     *
     * <p>The yaw of 180° is load-bearing and worth explaining. Looking straight down, the camera's
     * forward vector is (0,-1,0) and the "right" axis is derived by crossing it with world up —
     * which is very nearly the zero vector, so which way is right comes down to the residue of
     * cos(90°). At yaw 0 that lands on {@code right = -X}, producing a map that is west-right and
     * south-up: correct, and upside down. Yaw 180 flips it to the conventional orientation.
     * {@code CameraProjectionTest} pins this, so it can't silently invert.</p>
     */
    private RenderCamera topDownCamera(ClientLevel world, Entity camEntity, CaptureRequest req,
                                       int width, int height) {
        if (camEntity == null && (req.centerX() == null || req.centerZ() == null)) {
            throw new IllegalStateException("topdown needs centerX/centerZ when there is no player");
        }
        double centerX = req.centerX() != null ? req.centerX() : camEntity.getX();
        double centerZ = req.centerZ() != null ? req.centerZ() : camEntity.getZ();
        double groundY = req.y() != null ? req.y() : (camEntity != null ? camEntity.getY() : 64);
        int radius = clamp(req.radius() != null ? req.radius() : 32, 1, config.screenshot.maxRayDistance);

        // Sit far enough above the ground that terrain (and any hill between us and it) is in front
        // of the camera, not behind it.
        double eyeY = groundY + radius + 32;
        double aspect = (double) width / (double) height;
        // The snapshot cube is centred on the camera, so it has to reach both sideways to the map
        // edge and downward past the ground.
        int needed = (int) Math.ceil(Math.max(radius * aspect, eyeY - (groundY - 32))) + 2;
        int snapshotRadius = clampRadiusToSnapshotBudget(world, centerX, eyeY, centerZ,
                clamp(needed, 8, 192));
        return new RenderCamera(centerX, eyeY, centerZ, 180f, 90f,
                clampFov(config.screenshot.defaultFov), width, height,
                snapshotRadius, radius * 2.0);
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
            Minecraft mc = Minecraft.getInstance();
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

    private void renderGl(Minecraft mc, CaptureRequest req, CompletableFuture<byte[]> out) {
        if (!RenderSystem.isOnRenderThread()) {
            out.completeExceptionally(new IllegalStateException("GL capture not on render thread"));
            return;
        }
        if (mc.level == null) {
            out.completeExceptionally(new IllegalStateException("not in a world"));
            return;
        }
        Entity cam = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        if (cam == null && (req.x() == null || req.yaw() == null)) {
            out.completeExceptionally(new IllegalStateException("no player/camera entity"));
            return;
        }

        double x = req.x() != null ? req.x() : cam.getEyePosition().x;
        double y = req.y() != null ? req.y() : cam.getEyePosition().y;
        double z = req.z() != null ? req.z() : cam.getEyePosition().z;
        float yaw = req.yaw() != null ? req.yaw() : cam.getYRot();
        float pitch = req.pitch() != null ? req.pitch() : cam.getXRot();
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
        int oldW = window.getWidth();
        int oldH = window.getHeight();
        int oldFov = mc.options.fov().get();
        RenderTarget oldFb = mc.getMainRenderTarget();

        TextureTarget fb = new TextureTarget(width, height, true, true);
        ACTIVE_OVERRIDE = new CameraOverride(x, y, z, yaw, pitch);
        try {
            mc.options.fov().set((int) fov);
            winAcc.clef$setFramebufferWidth(width);
            winAcc.clef$setFramebufferHeight(height);
            mcAcc.clef$setFramebuffer(fb);

            mc.gameRenderer.renderLevel(mc.getFrameTime(), mc.getFrameTimeNs(), new com.mojang.blaze3d.vertex.PoseStack());

            // This release returns the image synchronously instead of taking a consumer.
            try (com.mojang.blaze3d.platform.NativeImage image = Screenshot.takeScreenshot(fb)) {
                Path tmp = Files.createTempFile("clef-shot-", ".png");
                image.writeToFile(tmp);
                byte[] bytes = Files.readAllBytes(tmp);
                Files.deleteIfExists(tmp);
                out.complete(bytes);
            } catch (Throwable t) {
                out.completeExceptionally(t);
            } finally {
                fb.destroyBuffers();
            }
        } finally {
            // Safe to restore now — takeScreenshot captured `fb`; it's deleted in the consumer.
            ACTIVE_OVERRIDE = null;
            mcAcc.clef$setFramebuffer(oldFb);
            winAcc.clef$setFramebufferWidth(oldW);
            winAcc.clef$setFramebufferHeight(oldH);
            try {
                mc.options.fov().set(oldFov);
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

    private int clampRadiusToSnapshotBudget(ClientLevel world, double x, double y, double z, int radius) {
        int budget = Math.max(1, config.screenshot.maxSnapshotBlocks);
        int worldBottom = world.getMinBuildHeight();
        int worldTop = world.getMinBuildHeight() + world.getHeight() - 1;
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
