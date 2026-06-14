package dev.mezzo.clef.mixin.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.headless.HeadlessController;
import dev.mezzo.clef.headless.nogl.NoGlDevice;
import net.minecraft.client.gl.DynamicUniforms;
import net.minecraft.util.TimeSupplier;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Boots Minecraft with <b>no OpenGL context and (optionally) no display server</b>.
 *
 * <p>Two surgical hooks, both gated on {@link HeadlessController}:
 *
 * <ol>
 *   <li><b>{@code initBackendSystem} (HEAD)</b> — runs immediately before GLFW is initialised.
 *       In no-window mode we request the GLFW <i>null platform</i>, so GLFW needs neither X11/
 *       Wayland nor a GPU; the game can boot on a bare server with no {@code xvfb}.</li>
 *   <li><b>{@code initRenderer} (HEAD, cancelled)</b> — Minecraft's only call site that does
 *       {@code DEVICE = new GlBackend(...)}, and {@code GlBackend}'s constructor is exactly what
 *       calls {@code glfwMakeContextCurrent} + {@code GL.createCapabilities()} and starts hitting
 *       the driver. We install a {@link NoGlDevice} instead and reproduce the handful of statics
 *       the original method leaves behind, then cancel — so the GL backend is never constructed
 *       and not a single GL call is ever made.</li>
 * </ol>
 *
 * <p>Verified against 1.21.8 Yarn ({@code RenderSystem#initRenderer} sets {@code DEVICE},
 * {@code apiDescription}, {@code dynamicUniforms} and {@code QUAD_VERTEX_BUFFER}; their getters
 * throw if left null, which is why we populate them).
 */
@Mixin(com.mojang.blaze3d.systems.RenderSystem.class)
public class RenderSystemNoGlMixin {

    @Shadow private static GpuDevice DEVICE;
    @Shadow private static String apiDescription;
    @Shadow private static DynamicUniforms dynamicUniforms;
    @Shadow private static GpuBuffer QUAD_VERTEX_BUFFER;

    @Inject(method = "initBackendSystem", at = @At("HEAD"))
    private static void clef$selectNullPlatform(CallbackInfoReturnable<TimeSupplier.Nanoseconds> cir) {
        if (!HeadlessController.get().isNoWindow()) {
            return;
        }
        try {
            // GLFW_PLATFORM = null platform: no window system, no GPU, no display needed.
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_NULL);
            MezzoClef.LOG.info("[no-gl] Requested GLFW null platform (no display server required).");
        } catch (Throwable t) {
            MezzoClef.LOG.warn("[no-gl] Could not request GLFW null platform; falling back to default: {}",
                    t.toString());
        }
    }

    @Inject(method = "initRenderer(JIZLjava/util/function/BiFunction;Z)V", at = @At("HEAD"), cancellable = true)
    private static void clef$installNoGlDevice(CallbackInfo ci) {
        if (!HeadlessController.get().isNoGl()) {
            return;
        }
        GpuDevice device = new NoGlDevice();
        DEVICE = device;
        apiDescription = device.getImplementationInformation();
        // Constructed exactly as vanilla does (its ctor touches no device); cleared each frame
        // by flipFrame in normal mode and read during the loading-screen render.
        dynamicUniforms = new DynamicUniforms();
        // Vanilla builds a real 4-vertex quad here; nothing reads our stub's contents, so a tiny
        // placeholder keeps QUAD_VERTEX_BUFFER's getter from throwing.
        QUAD_VERTEX_BUFFER = device.createBuffer(() -> "Quad", GpuBuffer.USAGE_VERTEX, 48);
        MezzoClef.LOG.info("[no-gl] Installed GPU-free render device — OpenGL will never be initialised.");
        ci.cancel();
    }
}
