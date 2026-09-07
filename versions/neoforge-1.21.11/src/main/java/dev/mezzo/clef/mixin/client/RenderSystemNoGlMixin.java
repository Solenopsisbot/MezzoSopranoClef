package dev.mezzo.clef.mixin.client;

import com.mojang.blaze3d.systems.GpuDevice;
import dev.mezzo.clef.MezzoClef;
import dev.mezzo.clef.headless.HeadlessController;
import dev.mezzo.clef.headless.nogl.NoGlDevice;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.util.TimeSource;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Boots Minecraft with <b>no OpenGL context and (optionally) no display server</b> on 1.21.x.
 *
 * <ol>
 *   <li>{@code initBackendSystem} (HEAD) — runs just before GLFW is initialised. In no-window mode
 *       we request the GLFW <i>null platform</i>, so GLFW needs neither X11/Wayland nor a GPU.</li>
 *   <li>{@code initRenderer} (HEAD, cancelled) — this is the only call site that constructs
 *       {@code GlDevice}, whose constructor performs {@code glfwMakeContextCurrent} +
 *       {@code GL.createCapabilities()}. We install a {@link NoGlDevice} and reproduce the statics
 *       the original leaves behind, then cancel, so the GL backend is never built.</li>
 * </ol>
 *
 * <p>(26.2 moved device creation behind {@code GpuBackend.createDevice}; that target hooks there
 * instead. This is the pre-26 path.)
 */
@Mixin(com.mojang.blaze3d.systems.RenderSystem.class)
public class RenderSystemNoGlMixin {

    @Shadow private static GpuDevice DEVICE;
    @Shadow private static String apiDescription;
    @Shadow private static DynamicUniforms dynamicUniforms;

    @Inject(method = "initBackendSystem", at = @At("HEAD"))
    private static void clef$selectNullPlatform(CallbackInfoReturnable<TimeSource.NanoTimeSource> cir) {
        if (!HeadlessController.get().isNoWindow()) return;
        try {
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_NULL);
            MezzoClef.LOG.info("[no-gl] Requested GLFW null platform (no display server required).");
        } catch (Throwable t) {
            MezzoClef.LOG.warn("[no-gl] Could not request GLFW null platform; falling back: {}", t.toString());
        }
    }

    @Inject(method = "initRenderer(JIZLcom/mojang/blaze3d/shaders/ShaderSource;Z)V",
            at = @At("HEAD"), cancellable = true)
    private static void clef$installNoGlDevice(CallbackInfo ci) {
        if (!HeadlessController.get().isNoGl()) return;
        GpuDevice device = new NoGlDevice();
        DEVICE = device;
        apiDescription = device.getImplementationInformation();
        // Constructed exactly as vanilla does (its ctor touches no device); read during the
        // loading-screen render and cleared each frame in normal mode.
        dynamicUniforms = new DynamicUniforms();
        MezzoClef.LOG.info("[no-gl] Installed GPU-free render device — OpenGL will never be initialised.");
        ci.cancel();
    }
}
